package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.mixin.ClientPlayNetworkHandlerInvoker;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.stat.SystemTrafficMonitor;
import cn.ussshenzhou.notenoughbandwidth.zstd.DictionaryManager;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;

import static cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager.LOCAL;

public class ModNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Network");

    public static void registerCommon() {
        // Register payload types
        PayloadTypeRegistry.playS2C().register(PacketAggregationPacket.TYPE, PacketAggregationPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(PacketAggregationPacket.TYPE, PacketAggregationPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(StatQueryPayload.TYPE, StatQueryPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(StatRespondPayload.TYPE, StatRespondPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(NebAckPayload.TYPE, NebAckPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ChunkCacheManifestPayload.TYPE, ChunkCacheManifestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ChunkRequestPayload.TYPE, ChunkRequestPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ChunkHashPayload.TYPE, ChunkHashPayload.CODEC);

        // Server-side handlers
        ServerPlayNetworking.registerGlobalReceiver(PacketAggregationPacket.TYPE, (payload, context) -> {
            payload.handle(context.player().networkHandler.connection);
        });

        // Client confirmed NEB presence: enable compression path for this connection
        ServerPlayNetworking.registerGlobalReceiver(NebAckPayload.TYPE, (payload, context) -> {
            var connection = context.player().networkHandler.connection;
            NebConnectionRegistry.markEnabled(connection);
            AggregationManager.init();
            LOGGER.info("NEB ack received from {}, compression path enabled",
                    context.player().getName().getString());
        });

        // Client uploads its bloom filter; store it for chunk-send optimization.
        ServerPlayNetworking.registerGlobalReceiver(ChunkCacheManifestPayload.TYPE, (payload, context) -> {
            var connection = context.player().networkHandler.connection;
            if (payload.bloomFilterBytes() != null && payload.bloomFilterBytes().length > 0) {
                ChunkCacheManager.setServerBloomFilter(connection, payload.bloomFilterBytes());
                LOGGER.info("Received chunk cache manifest from {} ({} bytes)",
                        context.player().getName().getString(), payload.bloomFilterBytes().length);
            }
        });

        // Client didn't have the chunk despite bloom-filter hit; resend full data.
        ServerPlayNetworking.registerGlobalReceiver(ChunkRequestPayload.TYPE, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            ServerWorld world = player.getServerWorld();
            ChunkPos pos = new ChunkPos(payload.chunkX(), payload.chunkZ());
            world.getServer().execute(() -> {
                var chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
                if (chunk != null) {
                    BitSet lightMask = NotEnoughBandwidthConfig.get().lightStripEnabled ? new BitSet() : null;
                    player.networkHandler.sendPacket(
                            new ChunkDataS2CPacket(chunk, world.getLightingProvider(), lightMask, lightMask));
                    LOGGER.debug("Resent chunk ({},{}) after cache miss for {}",
                            pos.x, pos.z, player.getName().getString());
                } else {
                    // Chunk was unloaded between the bloom-filter hit and the client's cache-miss request.
                    // The server's ChunkDataSender will re-send it normally when the chunk reloads.
                    LOGGER.warn("Could not resend chunk ({},{}) for {}: chunk not loaded",
                            pos.x, pos.z, player.getName().getString());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(StatQueryPayload.TYPE, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            if (player.hasPermissionLevel(2)) {
                ServerPlayNetworking.send(player, new StatRespondPayload(
                        LOCAL.inboundBytesBaked().get(),
                        LOCAL.inboundBytesRaw().get(),
                        LOCAL.outboundBytesBaked().get(),
                        LOCAL.outboundBytesRaw().get(),
                        LOCAL.inboundSpeedBaked().averageIn1s(),
                        LOCAL.inboundSpeedRaw().averageIn1s(),
                        LOCAL.outboundSpeedBaked().averageIn1s(),
                        LOCAL.outboundSpeedRaw().averageIn1s(),
                        DictionaryManager.getDictSize(),
                        DictionaryManager.getSampleCount(),
                        DictionaryManager.getSampleThreshold(),
                        SimpleStatManager.chunkCacheHits.get(),
                        SimpleStatManager.chunkCacheMisses.get(),
                        SimpleStatManager.chunkCacheSavedBytes.get(),
                        SystemTrafficMonitor.getInboundBytesPerSec(),
                        SystemTrafficMonitor.getOutboundBytesPerSec()
                ));
            }
        });
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(PacketAggregationPacket.TYPE, (payload, context) -> {
            payload.handle(context.player().networkHandler.connection);
        });

        // Server says: "you probably have this chunk cached".
        // Load from local DB, or fall back to requesting the full packet.
        ClientPlayNetworking.registerGlobalReceiver(ChunkHashPayload.TYPE, (payload, context) -> {
            byte[] cachedBytes = ChunkCacheManager.getClientCachedChunk(payload.contentHash());
            if (cachedBytes != null) {
                // Count the skipped chunk payload in raw stats so Ratio reflects PCC savings.
                SimpleStatManager.inRaw(cachedBytes.length);
            }
            if (cachedBytes == null) {
                // Bloom-filter false positive or stale cache — request full data.
                ClientPlayNetworking.send(new ChunkRequestPayload(payload.chunkX(), payload.chunkZ()));
                LOGGER.debug("Cache miss for chunk ({},{}), requesting full data", payload.chunkX(), payload.chunkZ());
                return;
            }
            // Deserialize the cached bytes and apply to the world on the main thread.
            var registryManager = context.player().networkHandler.getRegistryManager();
            // wrappedBuffer does not copy the array; release after deserialization (before execute).
            var inner = Unpooled.wrappedBuffer(cachedBytes);
            ChunkData chunkData;
            LightData lightData;
            try {
                var buf = new RegistryByteBuf(inner, registryManager);
                int x = payload.chunkX();
                int z = payload.chunkZ();
                chunkData = new ChunkData(buf, x, z);
                lightData = new LightData(buf, x, z);
            } catch (Exception e) {
                LOGGER.error("Failed to deserialize cached chunk ({},{}), evicting corrupt entry and requesting full data",
                        payload.chunkX(), payload.chunkZ(), e);
                // Remove the corrupt entry so we don't hit the same failure on every future load.
                ChunkCacheManager.deleteClientCachedChunk(payload.contentHash());
                ClientPlayNetworking.send(new ChunkRequestPayload(payload.chunkX(), payload.chunkZ()));
                return;
            } finally {
                // Release wrappedBuffer immediately after deserialization; the parsed objects hold their own copies.
                inner.release();
            }
            var invoker = (ClientPlayNetworkHandlerInvoker) context.player().networkHandler;
            int x = payload.chunkX();
            int z = payload.chunkZ();
            context.client().execute(() -> {
                try {
                    invoker.nebLoadChunk(x, z, chunkData);
                    var world = context.client().world;
                    if (world != null) {
                        world.enqueueChunkUpdate(() -> {
                            invoker.nebReadLightData(x, z, lightData);
                            var worldChunk = world.getChunkManager().getWorldChunk(x, z, false);
                            if (worldChunk != null) {
                                invoker.nebScheduleRenderChunk(worldChunk, x, z);
                            }
                        });
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to apply cached chunk ({},{}) to world", x, z, e);
                }
            });
            LOGGER.debug("Cache hit for chunk ({},{})", payload.chunkX(), payload.chunkZ());
        });

        ClientPlayNetworking.registerGlobalReceiver(StatRespondPayload.TYPE, (payload, context) -> {
            SimpleStatManager.inboundBytesBakedServer = payload.inboundBytesBaked();
            SimpleStatManager.inboundBytesRawServer = payload.inboundBytesRaw();
            SimpleStatManager.outboundBytesBakedServer = payload.outboundBytesBaked();
            SimpleStatManager.outboundBytesRawServer = payload.outboundBytesRaw();
            SimpleStatManager.inboundSpeedBakedServer = payload.inboundSpeedBaked();
            SimpleStatManager.inboundSpeedRawServer = payload.inboundSpeedRaw();
            SimpleStatManager.outboundSpeedBakedServer = payload.outboundSpeedBaked();
            SimpleStatManager.outboundSpeedRawServer = payload.outboundSpeedRaw();
            SimpleStatManager.dictSizeServer = payload.dictSize();
            SimpleStatManager.dictSampleCountServer = payload.dictSampleCount();
            SimpleStatManager.dictSampleThresholdServer = payload.dictSampleThreshold();
            SimpleStatManager.chunkCacheHitsServer = payload.chunkCacheHits();
            SimpleStatManager.chunkCacheMissesServer = payload.chunkCacheMisses();
            SimpleStatManager.chunkCacheSavedBytesServer = payload.chunkCacheSavedBytes();
            SimpleStatManager.nicInboundSpeedServer = payload.nicInboundSpeed();
            SimpleStatManager.nicOutboundSpeedServer = payload.nicOutboundSpeed();
        });
    }
}
