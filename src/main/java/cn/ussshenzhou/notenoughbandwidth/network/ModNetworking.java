package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.mixin.ClientPlayNetworkHandlerInvoker;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.stat.SystemTrafficMonitor;
import cn.ussshenzhou.notenoughbandwidth.zstd.DictionaryManager;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
//#if MC>=12005
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
//#else
//$$ import cn.ussshenzhou.notenoughbandwidth.chunkcache.PendingChunkQueue;
//$$ import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
//$$ import net.minecraft.network.Connection;
//$$ import net.minecraft.network.FriendlyByteBuf;
//$$
//$$ import java.io.ByteArrayOutputStream;
//$$ import java.util.concurrent.ConcurrentHashMap;
//#endif
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
//#if MC>=12111
import net.minecraft.server.permissions.Permissions;
//#endif
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;

import static cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager.LOCAL;

public class ModNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Network");

    //#if MC>=12005
    public static void registerCommon() {
        // Register payload types
        PayloadTypeRegistry.clientboundPlay().register(PacketAggregationPacket.TYPE, PacketAggregationPacket.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PacketAggregationPacket.TYPE, PacketAggregationPacket.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StatQueryPayload.TYPE, StatQueryPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StatRespondPayload.TYPE, StatRespondPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(NebAckPayload.TYPE, NebAckPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ChunkCacheManifestPayload.TYPE, ChunkCacheManifestPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ChunkRequestPayload.TYPE, ChunkRequestPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ChunkHashPayload.TYPE, ChunkHashPayload.CODEC);

        // Server-side handlers
        ServerPlayNetworking.registerGlobalReceiver(PacketAggregationPacket.TYPE, (payload, context) -> {
            payload.handle(context.player().connection.connection);
        });

        // Client confirmed NEB presence: enable compression path for this connection
        ServerPlayNetworking.registerGlobalReceiver(NebAckPayload.TYPE, (payload, context) -> {
            var connection = context.player().connection.connection;
            NebConnectionRegistry.markEnabled(connection);
            AggregationManager.init();
            LOGGER.info("NEB ack received from {}, compression path enabled",
                    context.player().getName().getString());
        });

        // Client uploads its bloom filter; store it for chunk-send optimization.
        ServerPlayNetworking.registerGlobalReceiver(ChunkCacheManifestPayload.TYPE, (payload, context) -> {
            var connection = context.player().connection.connection;
            if (payload.bloomFilterBytes() != null && payload.bloomFilterBytes().length > 0) {
                ChunkCacheManager.setServerBloomFilter(connection, payload.bloomFilterBytes());
                LOGGER.info("Received chunk cache manifest from {} ({} bytes)",
                        context.player().getName().getString(), payload.bloomFilterBytes().length);
            }
        });

        // Client didn't have the chunk despite bloom-filter hit; resend full data.
        ServerPlayNetworking.registerGlobalReceiver(ChunkRequestPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            ServerLevel world = player.level();
            ChunkPos pos = new ChunkPos(payload.chunkX(), payload.chunkZ());
            world.getServer().execute(() -> {
                var chunk = world.getChunkSource().getChunkNow(pos.x(), pos.z());
                if (chunk != null) {
                    BitSet lightMask = cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig.get().lightStripEnabled
                            ? new BitSet()
                            : null;
                    player.connection.send(
                            new ClientboundLevelChunkWithLightPacket(chunk, world.getLightEngine(), lightMask, lightMask));
                    LOGGER.debug("Resent chunk ({},{}) after cache miss for {}",
                            pos.x(), pos.z(), player.getName().getString());
                } else {
                    // Chunk was unloaded between the bloom-filter hit and the client's cache-miss request.
                    // The server's PlayerChunkSender will re-send it normally when the chunk reloads.
                    LOGGER.warn("Could not resend chunk ({},{}) for {}: chunk not loaded",
                            pos.x(), pos.z(), player.getName().getString());
                }
            });
        });

        ServerPlayNetworking.registerGlobalReceiver(StatQueryPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            //#if MC>=12111
            if (player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            //#else
            //$$ if (player.hasPermissions(2)) {
            //#endif
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
            payload.handle(context.player().connection.connection);
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
            var registryManager = context.player().connection.registryAccess();
            // wrappedBuffer does not copy the array; release after deserialization (before execute).
            var inner = Unpooled.wrappedBuffer(cachedBytes);
            ClientboundLevelChunkPacketData chunkData;
            ClientboundLightUpdatePacketData lightData;
            try {
                var buf = new RegistryFriendlyByteBuf(inner, registryManager);
                int x = payload.chunkX();
                int z = payload.chunkZ();
                chunkData = new ClientboundLevelChunkPacketData(buf, x, z);
                lightData = new ClientboundLightUpdatePacketData(buf, x, z);
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
            var invoker = (ClientPlayNetworkHandlerInvoker) context.player().connection;
            int x = payload.chunkX();
            int z = payload.chunkZ();
            context.client().execute(() -> {
                try {
                    invoker.nebLoadChunk(x, z, chunkData);
                    var world = context.client().level;
                    if (world != null) {
                        // 26.1: ClientLevel.enqueueChunkUpdate is gone; just run on the client thread.
                        net.minecraft.client.Minecraft.getInstance().execute(() -> {
                            invoker.nebReadLightData(x, z, lightData, false);
                            var worldChunk = world.getChunkSource().getChunkNow(x, z);
                            if (worldChunk != null) {
                                invoker.nebScheduleRenderChunk(worldChunk, x, z);
                                // 26.1: LevelRenderer.scheduleNeighborUpdates is gone; onChunkLoaded
                                // triggers section re-render through the occlusion graph.
                                world.onChunkLoaded(worldChunk.getPos());
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
    //#else
    //$$ private static final ConcurrentHashMap<Connection, ByteArrayOutputStream> MANIFEST_CHUNKS = new ConcurrentHashMap<>();
    //$$
    //$$ public static void registerCommon() {
    //$$     // 1.20.1 has no PayloadTypeRegistry — track all channels for index sync manually.
    //$$     IndexSyncHandler.registerChannel(PacketAggregationPacket.CHANNEL);
    //$$     IndexSyncHandler.registerChannel(NebAckPayload.CHANNEL);
    //$$     IndexSyncHandler.registerChannel(ChunkCacheManifestPayload.CHANNEL);
    //$$     IndexSyncHandler.registerChannel(ChunkRequestPayload.CHANNEL);
    //$$     IndexSyncHandler.registerChannel(ChunkHashPayload.CHANNEL);
    //$$     IndexSyncHandler.registerChannel(StatQueryPayload.CHANNEL);
    //$$     IndexSyncHandler.registerChannel(StatRespondPayload.CHANNEL);
    //$$
    //$$     // Server-side handlers
    //$$     ServerPlayNetworking.registerGlobalReceiver(PacketAggregationPacket.CHANNEL, (server, player, handler, buf, responseSender) -> {
    //$$         var payload = PacketAggregationPacket.read(new FriendlyByteBuf(buf.copy()));
    //$$         payload.handle(handler.connection);
    //$$     });
    //$$
    //$$     // Client confirmed NEB presence: promote from pending to enabled and
    //$$     // flush all packets that were buffered during the handshake.
    //$$     // The client sends the bloom filter manifest BEFORE NebAck, so by the
    //$$     // time we reach this handler the bloom filter is already stored.
    //$$     ServerPlayNetworking.registerGlobalReceiver(NebAckPayload.CHANNEL, (server, player, handler, buf, responseSender) -> {
    //$$         var connection = handler.connection;
    //$$         NebConnectionRegistry.markEnabled(connection);
    //$$         AggregationManager.init();
    //$$         AggregationManager.flushConnection(connection);
    //$$         // Replay chunk sends that were queued during PENDING — now with
    //$$         // the bloom filter available for PCC decisions.
    //$$         server.execute(() -> PendingChunkQueue.drainAndSend(connection));
    //$$         LOGGER.info("NEB ack received from {}, compression path enabled, flushing buffered packets",
    //$$                 player.getName().getString());
    //$$     });
    //$$
    //$$     // Client uploads its bloom filter in chunks (32 KB C2S payload limit);
    //$$     // accumulate and apply when the last chunk arrives.
    //$$     ServerPlayNetworking.registerGlobalReceiver(ChunkCacheManifestPayload.CHANNEL, (server, player, handler, buf, responseSender) -> {
    //$$         var payload = ChunkCacheManifestPayload.read(new FriendlyByteBuf(buf.copy()));
    //$$         var connection = handler.connection;
    //$$         if (payload.bloomFilterBytes() == null || payload.bloomFilterBytes().length == 0) {
    //$$             return;
    //$$         }
    //$$         var accumulator = MANIFEST_CHUNKS.computeIfAbsent(connection, k -> new ByteArrayOutputStream());
    //$$         accumulator.write(payload.bloomFilterBytes(), 0, payload.bloomFilterBytes().length);
    //$$         if (!payload.hasMore()) {
    //$$             byte[] fullBloom = accumulator.toByteArray();
    //$$             MANIFEST_CHUNKS.remove(connection);
    //$$             ChunkCacheManager.setServerBloomFilter(connection, fullBloom);
    //$$             LOGGER.info("Received chunk cache manifest from {} ({} bytes)",
    //$$                     player.getName().getString(), fullBloom.length);
    //$$         }
    //$$     });
    //$$
    //$$     // Client didn't have the chunk despite bloom-filter hit; resend full data.
    //$$     ServerPlayNetworking.registerGlobalReceiver(ChunkRequestPayload.CHANNEL, (server, player, handler, buf, responseSender) -> {
    //$$         var payload = ChunkRequestPayload.read(new FriendlyByteBuf(buf.copy()));
    //$$         ServerLevel world = player.serverLevel();
    //$$         ChunkPos pos = new ChunkPos(payload.chunkX(), payload.chunkZ());
    //$$         server.execute(() -> {
    //$$             var chunk = world.getChunkSource().getChunkNow(pos.x, pos.z);
    //$$             if (chunk != null) {
    //$$                 BitSet lightMask = cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig.get().lightStripEnabled
    //$$                         ? new BitSet()
    //$$                         : null;
    //$$                 player.connection.send(
    //$$                         new ClientboundLevelChunkWithLightPacket(chunk, world.getLightEngine(), lightMask, lightMask));
    //$$                 LOGGER.debug("Resent chunk ({},{}) after cache miss for {}",
    //$$                         pos.x, pos.z, player.getName().getString());
    //$$             } else {
    //$$                 LOGGER.warn("Could not resend chunk ({},{}) for {}: chunk not loaded",
    //$$                         pos.x, pos.z, player.getName().getString());
    //$$             }
    //$$         });
    //$$     });
    //$$
    //$$     ServerPlayNetworking.registerGlobalReceiver(StatQueryPayload.CHANNEL, (server, player, handler, buf, responseSender) -> {
    //$$         if (player.hasPermissions(2)) {
    //$$             var respond = new StatRespondPayload(
    //$$                     LOCAL.inboundBytesBaked().get(),
    //$$                     LOCAL.inboundBytesRaw().get(),
    //$$                     LOCAL.outboundBytesBaked().get(),
    //$$                     LOCAL.outboundBytesRaw().get(),
    //$$                     LOCAL.inboundSpeedBaked().averageIn1s(),
    //$$                     LOCAL.inboundSpeedRaw().averageIn1s(),
    //$$                     LOCAL.outboundSpeedBaked().averageIn1s(),
    //$$                     LOCAL.outboundSpeedRaw().averageIn1s(),
    //$$                     DictionaryManager.getDictSize(),
    //$$                     DictionaryManager.getSampleCount(),
    //$$                     DictionaryManager.getSampleThreshold(),
    //$$                     SimpleStatManager.chunkCacheHits.get(),
    //$$                     SimpleStatManager.chunkCacheMisses.get(),
    //$$                     SimpleStatManager.chunkCacheSavedBytes.get(),
    //$$                     SystemTrafficMonitor.getInboundBytesPerSec(),
    //$$                     SystemTrafficMonitor.getOutboundBytesPerSec()
    //$$             );
    //$$             FriendlyByteBuf sendBuf = PacketByteBufs.create();
    //$$             respond.write(sendBuf);
    //$$             ServerPlayNetworking.send(player, StatRespondPayload.CHANNEL, sendBuf);
    //$$         }
    //$$     });
    //$$ }
    //$$
    //$$ public static void registerClient() {
    //$$     ClientPlayNetworking.registerGlobalReceiver(PacketAggregationPacket.CHANNEL, (client, handler, buf, responseSender) -> {
    //$$         var payload = PacketAggregationPacket.read(new FriendlyByteBuf(buf.copy()));
    //$$         payload.handle(handler.getConnection());
    //$$     });
    //$$
    //$$     // Server says: "you probably have this chunk cached".
    //$$     // Load from local DB, or fall back to requesting the full packet.
    //$$     ClientPlayNetworking.registerGlobalReceiver(ChunkHashPayload.CHANNEL, (client, handler, buf, responseSender) -> {
    //$$         var payload = ChunkHashPayload.read(new FriendlyByteBuf(buf.copy()));
    //$$         byte[] cachedBytes = ChunkCacheManager.getClientCachedChunk(payload.contentHash());
    //$$         if (cachedBytes != null) {
    //$$             // Count the skipped chunk payload in raw stats so Ratio reflects PCC savings.
    //$$             SimpleStatManager.inRaw(cachedBytes.length);
    //$$         }
    //$$         if (cachedBytes == null) {
    //$$             // Bloom-filter false positive or stale cache — request full data.
    //$$             FriendlyByteBuf reqBuf = PacketByteBufs.create();
    //$$             new ChunkRequestPayload(payload.chunkX(), payload.chunkZ()).write(reqBuf);
    //$$             ClientPlayNetworking.send(ChunkRequestPayload.CHANNEL, reqBuf);
    //$$             LOGGER.debug("Cache miss for chunk ({},{}), requesting full data", payload.chunkX(), payload.chunkZ());
    //$$             return;
    //$$         }
    //$$         // Deserialize the cached bytes and apply to the world on the main thread.
    //$$         // wrappedBuffer does not copy the array; release after deserialization (before execute).
    //$$         var inner = Unpooled.wrappedBuffer(cachedBytes);
    //$$         ClientboundLevelChunkPacketData chunkData;
    //$$         ClientboundLightUpdatePacketData lightData;
    //$$         try {
    //$$             var packetBuf = new FriendlyByteBuf(inner);
    //$$             int x = payload.chunkX();
    //$$             int z = payload.chunkZ();
    //$$             chunkData = new ClientboundLevelChunkPacketData(packetBuf, x, z);
    //$$             lightData = new ClientboundLightUpdatePacketData(packetBuf, x, z);
    //$$         } catch (Exception e) {
    //$$             LOGGER.error("Failed to deserialize cached chunk ({},{}), evicting corrupt entry and requesting full data",
    //$$                     payload.chunkX(), payload.chunkZ(), e);
    //$$             // Remove the corrupt entry so we don't hit the same failure on every future load.
    //$$             ChunkCacheManager.deleteClientCachedChunk(payload.contentHash());
    //$$             FriendlyByteBuf reqBuf = PacketByteBufs.create();
    //$$             new ChunkRequestPayload(payload.chunkX(), payload.chunkZ()).write(reqBuf);
    //$$             ClientPlayNetworking.send(ChunkRequestPayload.CHANNEL, reqBuf);
    //$$             return;
    //$$         } finally {
    //$$             // Release wrappedBuffer immediately after deserialization; the parsed objects hold their own copies.
    //$$             inner.release();
    //$$         }
    //$$         var invoker = (ClientPlayNetworkHandlerInvoker) handler;
    //$$         int x = payload.chunkX();
    //$$         int z = payload.chunkZ();
    //$$         client.execute(() -> {
    //$$             try {
    //$$                 invoker.nebLoadChunk(x, z, chunkData);
    //$$                 var world = client.level;
    //$$                 if (world != null) {
    //$$                     invoker.nebReadLightData(x, z, lightData);
    //$$                     var worldChunk = world.getChunkSource().getChunkNow(x, z);
    //$$                     if (worldChunk != null) {
    //$$                         invoker.nebScheduleRenderChunk(worldChunk, x, z);
    //$$                         client.levelRenderer.allChanged();
    //$$                     }
    //$$                 }
    //$$             } catch (Exception e) {
    //$$                 LOGGER.error("Failed to apply cached chunk ({},{}) to world", x, z, e);
    //$$             }
    //$$         });
    //$$         LOGGER.debug("Cache hit for chunk ({},{})", payload.chunkX(), payload.chunkZ());
    //$$     });
    //$$
    //$$     ClientPlayNetworking.registerGlobalReceiver(StatRespondPayload.CHANNEL, (client, handler, buf, responseSender) -> {
    //$$         var payload = StatRespondPayload.read(new FriendlyByteBuf(buf.copy()));
    //$$         SimpleStatManager.inboundBytesBakedServer = payload.inboundBytesBaked();
    //$$         SimpleStatManager.inboundBytesRawServer = payload.inboundBytesRaw();
    //$$         SimpleStatManager.outboundBytesBakedServer = payload.outboundBytesBaked();
    //$$         SimpleStatManager.outboundBytesRawServer = payload.outboundBytesRaw();
    //$$         SimpleStatManager.inboundSpeedBakedServer = payload.inboundSpeedBaked();
    //$$         SimpleStatManager.inboundSpeedRawServer = payload.inboundSpeedRaw();
    //$$         SimpleStatManager.outboundSpeedBakedServer = payload.outboundSpeedBaked();
    //$$         SimpleStatManager.outboundSpeedRawServer = payload.outboundSpeedRaw();
    //$$         SimpleStatManager.dictSizeServer = payload.dictSize();
    //$$         SimpleStatManager.dictSampleCountServer = payload.dictSampleCount();
    //$$         SimpleStatManager.dictSampleThresholdServer = payload.dictSampleThreshold();
    //$$         SimpleStatManager.chunkCacheHitsServer = payload.chunkCacheHits();
    //$$         SimpleStatManager.chunkCacheMissesServer = payload.chunkCacheMisses();
    //$$         SimpleStatManager.chunkCacheSavedBytesServer = payload.chunkCacheSavedBytes();
    //$$         SimpleStatManager.nicInboundSpeedServer = payload.nicInboundSpeed();
    //$$         SimpleStatManager.nicOutboundSpeedServer = payload.nicOutboundSpeed();
    //$$     });
    //$$ }
    //$$
    //$$ public static void clearManifestChunks(Connection connection) {
    //$$     MANIFEST_CHUNKS.remove(connection);
    //$$ }
    //#endif
}
