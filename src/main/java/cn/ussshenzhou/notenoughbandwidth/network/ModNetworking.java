package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.mixin.ClientPlayNetworkHandlerInvoker;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.zstd.DictionaryManager;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentHashMap;

import static cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager.LOCAL;

public class ModNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Network");
    private static final ConcurrentHashMap<ClientConnection, ByteArrayOutputStream> MANIFEST_CHUNKS = new ConcurrentHashMap<>();

    public static void registerCommon() {
        // Track all channels for index sync
        IndexSyncHandler.registerChannel(PacketAggregationPacket.CHANNEL);
        IndexSyncHandler.registerChannel(NebAckPayload.CHANNEL);
        IndexSyncHandler.registerChannel(ChunkCacheManifestPayload.CHANNEL);
        IndexSyncHandler.registerChannel(ChunkRequestPayload.CHANNEL);
        IndexSyncHandler.registerChannel(ChunkHashPayload.CHANNEL);
        IndexSyncHandler.registerChannel(StatQueryPayload.CHANNEL);
        IndexSyncHandler.registerChannel(StatRespondPayload.CHANNEL);

        // Server-side handlers
        ServerPlayNetworking.registerGlobalReceiver(PacketAggregationPacket.CHANNEL, (server, player, handler, buf, responseSender) -> {
            var payload = PacketAggregationPacket.read(new PacketByteBuf(buf.copy()));
            payload.handle(handler.connection);
        });

        // Client confirmed NEB presence: promote from pending to enabled and
        // flush all packets that were buffered during the handshake.
        ServerPlayNetworking.registerGlobalReceiver(NebAckPayload.CHANNEL, (server, player, handler, buf, responseSender) -> {
            var connection = handler.connection;
            NebConnectionRegistry.markEnabled(connection);
            AggregationManager.init();
            AggregationManager.flushConnection(connection);
            LOGGER.info("NEB ack received from {}, compression path enabled, flushing buffered packets",
                    player.getName().getString());
        });

        // Client uploads its bloom filter in chunks; accumulate and apply when complete.
        ServerPlayNetworking.registerGlobalReceiver(ChunkCacheManifestPayload.CHANNEL, (server, player, handler, buf, responseSender) -> {
            var payload = ChunkCacheManifestPayload.read(new PacketByteBuf(buf.copy()));
            var connection = handler.connection;
            if (payload.bloomFilterBytes() == null || payload.bloomFilterBytes().length == 0) {
                return;
            }
            var accumulator = MANIFEST_CHUNKS.computeIfAbsent(connection, k -> new ByteArrayOutputStream());
            accumulator.write(payload.bloomFilterBytes(), 0, payload.bloomFilterBytes().length);
            if (!payload.hasMore()) {
                byte[] fullBloom = accumulator.toByteArray();
                MANIFEST_CHUNKS.remove(connection);
                ChunkCacheManager.setServerBloomFilter(connection, fullBloom);
                LOGGER.info("Received chunk cache manifest from {} ({} bytes)",
                        player.getName().getString(), fullBloom.length);
            }
        });

        // Client didn't have the chunk despite bloom-filter hit; resend full data.
        ServerPlayNetworking.registerGlobalReceiver(ChunkRequestPayload.CHANNEL, (server, player, handler, buf, responseSender) -> {
            var payload = ChunkRequestPayload.read(new PacketByteBuf(buf.copy()));
            ServerWorld world = player.getServerWorld();
            ChunkPos pos = new ChunkPos(payload.chunkX(), payload.chunkZ());
            server.execute(() -> {
                var chunk = world.getChunkManager().getWorldChunk(pos.x, pos.z);
                if (chunk != null) {
                    player.networkHandler.sendPacket(
                            new ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null));
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

        ServerPlayNetworking.registerGlobalReceiver(StatQueryPayload.CHANNEL, (server, player, handler, buf, responseSender) -> {
            if (player.hasPermissionLevel(2)) {
                var respond = new StatRespondPayload(
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
                        SimpleStatManager.chunkCacheSavedBytes.get()
                );
                PacketByteBuf sendBuf = PacketByteBufs.create();
                respond.write(sendBuf);
                ServerPlayNetworking.send(player, StatRespondPayload.CHANNEL, sendBuf);
            }
        });
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(PacketAggregationPacket.CHANNEL, (client, handler, buf, responseSender) -> {
            var payload = PacketAggregationPacket.read(new PacketByteBuf(buf.copy()));
            payload.handle(handler.getConnection());
        });

        // Server says: "you probably have this chunk cached".
        // Load from local DB, or fall back to requesting the full packet.
        ClientPlayNetworking.registerGlobalReceiver(ChunkHashPayload.CHANNEL, (client, handler, buf, responseSender) -> {
            var payload = ChunkHashPayload.read(new PacketByteBuf(buf.copy()));
            byte[] cachedBytes = ChunkCacheManager.getClientCachedChunk(payload.contentHash());
            if (cachedBytes != null) {
                // Count the skipped chunk payload in raw stats so Ratio reflects PCC savings.
                SimpleStatManager.inRaw(cachedBytes.length);
            }
            if (cachedBytes == null) {
                // Bloom-filter false positive or stale cache — request full data.
                PacketByteBuf reqBuf = PacketByteBufs.create();
                new ChunkRequestPayload(payload.chunkX(), payload.chunkZ()).write(reqBuf);
                ClientPlayNetworking.send(ChunkRequestPayload.CHANNEL, reqBuf);
                LOGGER.debug("Cache miss for chunk ({},{}), requesting full data", payload.chunkX(), payload.chunkZ());
                return;
            }
            // Deserialize the cached bytes and apply to the world on the main thread.
            // wrappedBuffer does not copy the array; release after deserialization (before execute).
            var inner = Unpooled.wrappedBuffer(cachedBytes);
            ChunkData chunkData;
            LightData lightData;
            try {
                var packetBuf = new PacketByteBuf(inner);
                int x = payload.chunkX();
                int z = payload.chunkZ();
                chunkData = new ChunkData(packetBuf, x, z);
                lightData = new LightData(packetBuf, x, z);
            } catch (Exception e) {
                LOGGER.error("Failed to deserialize cached chunk ({},{}), evicting corrupt entry and requesting full data",
                        payload.chunkX(), payload.chunkZ(), e);
                // Remove the corrupt entry so we don't hit the same failure on every future load.
                ChunkCacheManager.deleteClientCachedChunk(payload.contentHash());
                PacketByteBuf reqBuf = PacketByteBufs.create();
                new ChunkRequestPayload(payload.chunkX(), payload.chunkZ()).write(reqBuf);
                ClientPlayNetworking.send(ChunkRequestPayload.CHANNEL, reqBuf);
                return;
            } finally {
                // Release wrappedBuffer immediately after deserialization; the parsed objects hold their own copies.
                inner.release();
            }
            var invoker = (ClientPlayNetworkHandlerInvoker) handler;
            int x = payload.chunkX();
            int z = payload.chunkZ();
            client.execute(() -> {
                try {
                    invoker.nebLoadChunk(x, z, chunkData);
                    var world = client.world;
                    if (world != null) {
                        invoker.nebReadLightData(x, z, lightData);
                        var worldChunk = world.getChunkManager().getWorldChunk(x, z, false);
                        if (worldChunk != null) {
                            invoker.nebScheduleRenderChunk(worldChunk, x, z);
                            client.worldRenderer.scheduleTerrainUpdate();
                        }
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to apply cached chunk ({},{}) to world", x, z, e);
                }
            });
            LOGGER.debug("Cache hit for chunk ({},{})", payload.chunkX(), payload.chunkZ());
        });

        ClientPlayNetworking.registerGlobalReceiver(StatRespondPayload.CHANNEL, (client, handler, buf, responseSender) -> {
            var payload = StatRespondPayload.read(new PacketByteBuf(buf.copy()));
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
        });
    }

    /**
     * Helper to send a payload from server to a specific player.
     */
    public static void sendToPlayer(ServerPlayerEntity player, net.minecraft.util.Identifier channel, PacketByteBuf buf) {
        ServerPlayNetworking.send(player, channel, buf);
    }

    public static void clearManifestChunks(ClientConnection connection) {
        MANIFEST_CHUNKS.remove(connection);
    }
}
