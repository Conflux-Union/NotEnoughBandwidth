package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
import cn.ussshenzhou.notenoughbandwidth.zstd.DictionaryManager;
import cn.ussshenzhou.notenoughbandwidth.zstd.ZstdHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
//#if MC>=12005
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
//#else
//$$ import cn.ussshenzhou.notenoughbandwidth.chunkcache.PendingChunkQueue;
//$$ import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
//$$ import net.minecraft.network.FriendlyByteBuf;
//$$ import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
//$$
//$$ import java.util.concurrent.Executors;
//$$ import java.util.concurrent.ScheduledExecutorService;
//$$ import java.util.concurrent.TimeUnit;
//#endif
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//#if MC>=12005
import java.lang.reflect.Field;
//#endif
import java.util.*;
import java.util.stream.Collectors;

/**
 * Synchronizes the payload type index table between server and client.
 * <p>
 * On NeoForge this was free via modded network negotiation.
 * On Fabric we do it ourselves: server collects all registered CustomPacketPayload
 * types, sorts them, sends the list to the client on join.
 * <p>
 * On 1.20.1 there is no PayloadTypeRegistry, so all channels NEB registers are
 * tracked manually via {@code registerChannel}.
 */
public class IndexSyncHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-IndexSync");

    //#if MC>=12005
    private static Field packetTypesField;

    static {
        try {
            packetTypesField = PayloadTypeRegistryImpl.class.getDeclaredField("packetTypes");
            packetTypesField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            LOGGER.error("Failed to access PayloadTypeRegistryImpl.packetTypes", e);
        }
    }

    public static void registerServer() {
        PayloadTypeRegistry.clientboundPlay().register(DictionarySyncPayload.TYPE, DictionarySyncPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(IndexSyncPayload.TYPE, IndexSyncPayload.CODEC);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var connection = handler.connection;
            ChunkCacheManager.removeServerBloomFilter(connection);
            AggregationManager.discardConnection(connection);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // Send dictionary first so the client has it before compression starts.
            byte[] dict = DictionaryManager.getDict();
            sender.sendPacket(new DictionarySyncPayload(dict));

            List<Identifier> types = collectRegisteredTypes();
            // Only init once on dedicated server — registered types don't change after startup,
            // and re-init would race with readers that don't hold the lock.
            if (!NamespaceIndexManager.ready()) {
                NamespaceIndexManager.init(types);
            }
            // Do NOT init AggregationManager or mark connection here.
            // We wait for the client to send NebAckPayload before enabling the compression path.
            String serverId = NotEnoughBandwidthConfig.get().serverUUID;
            sender.sendPacket(new IndexSyncPayload(types, serverId));
            LOGGER.info("Sent dictionary ({}) and index sync to {} ({} types, serverId={}), awaiting NEB ack",
                    dict != null ? dict.length + " bytes" : "none",
                    handler.player.getName().getString(), types.size(), serverId);
        });
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(DictionarySyncPayload.TYPE, (payload, context) -> {
            DictionaryManager.setDict(payload.dictionary());
            var conn = context.player().connection.connection;
            ZstdHelper.evict(conn);
            if (payload.dictionary() != null && payload.dictionary().length > 0) {
                LOGGER.info("Received dictionary from server ({} bytes)", payload.dictionary().length);
            } else {
                LOGGER.info("Server has no trained dictionary yet");
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(IndexSyncPayload.TYPE, (payload, context) -> {
            LOGGER.info("Received index sync from server ({} types, serverId={})",
                    payload.types().size(), payload.serverId());
            NamespaceIndexManager.init(payload.types());
            AggregationManager.init();
            var connection = context.player().connection.connection;
            NebConnectionRegistry.markEnabled(connection);

            // Open chunk cache keyed by server UUID (reliable behind proxies).
            // Fall back to connection address if the server is an old NEB version without UUID.
            String cacheKey = payload.serverId().isEmpty()
                    ? connection.getRemoteAddress().toString()
                    : payload.serverId();
            ChunkCacheManager.onClientConnect(cacheKey);

            ClientPlayNetworking.send(new NebAckPayload());
            byte[] bloomBytes = ChunkCacheManager.getClientBloomFilterBytes();
            if (bloomBytes != null && bloomBytes.length > 0) {
                ClientPlayNetworking.send(new ChunkCacheManifestPayload(bloomBytes));
                LOGGER.info("Sent chunk cache manifest ({} bytes)", bloomBytes.length);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Identifier> collectRegisteredTypes() {
        Set<Identifier> types = new LinkedHashSet<>();
        try {
            if (packetTypesField != null) {
                var s2cMap = (Map<Identifier, ?>) packetTypesField.get(PayloadTypeRegistryImpl.CLIENTBOUND_PLAY);
                var c2sMap = (Map<Identifier, ?>) packetTypesField.get(PayloadTypeRegistryImpl.SERVERBOUND_PLAY);
                types.addAll(s2cMap.keySet());
                types.addAll(c2sMap.keySet());
            }
        } catch (IllegalAccessException e) {
            LOGGER.error("Failed to read registered payload types", e);
        }
        return types.stream()
                .sorted(Comparator.comparing(Identifier::getNamespace).thenComparing(Identifier::getPath))
                .collect(Collectors.toList());
    }
    //#else
    //$$ private static final int PENDING_TIMEOUT_SECONDS = 5;
    //$$ private static final ScheduledExecutorService TIMEOUT_TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
    //$$     var t = new Thread(r, "NEB-PendingTimeout");
    //$$     t.setDaemon(true);
    //$$     return t;
    //$$ });
    //$$
    //$$ private static final Set<ResourceLocation> REGISTERED_CHANNELS = new LinkedHashSet<>();
    //$$
    //$$ /**
    //$$  * Called by ModNetworking and IndexSyncHandler during registration to track
    //$$  * all custom payload channels for index sync.
    //$$  */
    //$$ public static void registerChannel(ResourceLocation channel) {
    //$$     REGISTERED_CHANNELS.add(channel);
    //$$ }
    //$$
    //$$ public static void registerServer() {
    //$$     // Track S2C-only channels that are registered here (not in ModNetworking).
    //$$     registerChannel(DictionarySyncPayload.CHANNEL);
    //$$     registerChannel(IndexSyncPayload.CHANNEL);
    //$$
    //$$     ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
    //$$         var connection = handler.connection;
    //$$         NebConnectionRegistry.markDisabled(connection);
    //$$         ChunkCacheManager.removeServerBloomFilter(connection);
    //$$         AggregationManager.discardConnection(connection);
    //$$         ModNetworking.clearManifestChunks(connection);
    //$$         PendingChunkQueue.discard(connection);
    //$$     });
    //$$
    //$$     ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
    //$$         // Send dictionary first so the client has it before compression starts.
    //$$         byte[] dict = DictionaryManager.getDict();
    //$$         FriendlyByteBuf dictBuf = PacketByteBufs.create();
    //$$         new DictionarySyncPayload(dict).write(dictBuf);
    //$$         sender.sendPacket(new ClientboundCustomPayloadPacket(DictionarySyncPayload.CHANNEL, dictBuf));
    //$$
    //$$         List<ResourceLocation> types = collectRegisteredTypes();
    //$$         // Only init once on dedicated server — registered types don't change after startup,
    //$$         // and re-init would race with readers that don't hold the lock.
    //$$         if (!NamespaceIndexManager.ready()) {
    //$$             NamespaceIndexManager.init(types);
    //$$         }
    //$$         String serverId = NotEnoughBandwidthConfig.get().serverUUID;
    //$$         FriendlyByteBuf indexBuf = PacketByteBufs.create();
    //$$         new IndexSyncPayload(types, serverId).write(indexBuf);
    //$$         sender.sendPacket(new ClientboundCustomPayloadPacket(IndexSyncPayload.CHANNEL, indexBuf));
    //$$
    //$$         // Start buffering packets immediately so the initial chunk burst
    //$$         // is captured. Flush is deferred until NebAck arrives.
    //$$         AggregationManager.init();
    //$$         var connection = handler.connection;
    //$$         NebConnectionRegistry.markPending(connection);
    //$$
    //$$         // Safety timeout: if NebAck never arrives (vanilla client without NEB),
    //$$         // atomically demote from pending and discard the buffer so vanilla packets
    //$$         // flow normally. Capture only the player name to avoid holding handler/player refs.
    //$$         String playerName = handler.player.getName().getString();
    //$$         TIMEOUT_TIMER.schedule(() -> {
    //$$             if (NebConnectionRegistry.tryDemoteFromPending(connection)) {
    //$$                 AggregationManager.discardConnection(connection);
    //$$                 PendingChunkQueue.discard(connection);
    //$$                 LOGGER.warn("NEB ack timeout for {}, disabling NEB for this connection",
    //$$                         playerName);
    //$$             }
    //$$         }, PENDING_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    //$$
    //$$         LOGGER.info("Sent dictionary ({}) and index sync to {} ({} types, serverId={}), buffering until NEB ack",
    //$$                 dict != null ? dict.length + " bytes" : "none",
    //$$                 handler.player.getName().getString(), types.size(), serverId);
    //$$     });
    //$$ }
    //$$
    //$$ public static void registerClient() {
    //$$     ClientPlayNetworking.registerGlobalReceiver(DictionarySyncPayload.CHANNEL, (client, handler, buf, responseSender) -> {
    //$$         var payload = DictionarySyncPayload.read(new FriendlyByteBuf(buf.copy()));
    //$$         DictionaryManager.setDict(payload.dictionary());
    //$$         var conn = handler.getConnection();
    //$$         ZstdHelper.evict(conn);
    //$$         if (payload.dictionary() != null && payload.dictionary().length > 0) {
    //$$             LOGGER.info("Received dictionary from server ({} bytes)", payload.dictionary().length);
    //$$         } else {
    //$$             LOGGER.info("Server has no trained dictionary yet");
    //$$         }
    //$$     });
    //$$
    //$$     ClientPlayNetworking.registerGlobalReceiver(IndexSyncPayload.CHANNEL, (client, handler, buf, responseSender) -> {
    //$$         var payload = IndexSyncPayload.read(new FriendlyByteBuf(buf.copy()));
    //$$         LOGGER.info("Received index sync from server ({} types, serverId={})",
    //$$                 payload.types().size(), payload.serverId());
    //$$         NamespaceIndexManager.init(payload.types());
    //$$         AggregationManager.init();
    //$$         var connection = handler.getConnection();
    //$$         NebConnectionRegistry.markEnabled(connection);
    //$$
    //$$         // Open chunk cache keyed by server UUID (reliable behind proxies).
    //$$         // Fall back to connection address if the server is an old NEB version without UUID.
    //$$         String cacheKey = payload.serverId().isEmpty()
    //$$                 ? connection.getRemoteAddress().toString()
    //$$                 : payload.serverId();
    //$$         ChunkCacheManager.onClientConnect(cacheKey);
    //$$
    //$$         // Must send on the client thread — Fabric 1.20.1 rejects sends from
    //$$         // the Netty IO thread during early PLAY phase.
    //$$         // Send bloom filter manifest BEFORE NebAck so the server has the
    //$$         // bloom filter stored by the time NebAck triggers the flush and
    //$$         // pending chunk drain.
    //$$         client.execute(() -> {
    //$$             try {
    //$$                 byte[] bloomBytes = ChunkCacheManager.getClientBloomFilterBytes();
    //$$                 if (bloomBytes != null && bloomBytes.length > 0) {
    //$$                     sendChunkedManifest(bloomBytes);
    //$$                     LOGGER.info("Sent chunk cache manifest ({} bytes)", bloomBytes.length);
    //$$                 }
    //$$                 FriendlyByteBuf ackBuf = PacketByteBufs.create();
    //$$                 new NebAckPayload().write(ackBuf);
    //$$                 ClientPlayNetworking.send(NebAckPayload.CHANNEL, ackBuf);
    //$$             } catch (Exception e) {
    //$$                 LOGGER.warn("Failed to send NEB ack/manifest", e);
    //$$             }
    //$$         });
    //$$     });
    //$$ }
    //$$
    //$$ /**
    //$$  * Sends a bloom filter to the server, splitting into chunks that fit in
    //$$  * the 1.20.1 C2S custom payload size limit (32 767 bytes).
    //$$  */
    //$$ public static void sendChunkedManifest(byte[] bloomBytes) {
    //$$     int offset = 0;
    //$$     while (offset < bloomBytes.length) {
    //$$         int remaining = bloomBytes.length - offset;
    //$$         int chunkSize = Math.min(remaining, ChunkCacheManifestPayload.MAX_CHUNK_SIZE);
    //$$         boolean hasMore = offset + chunkSize < bloomBytes.length;
    //$$         byte[] chunk = new byte[chunkSize];
    //$$         System.arraycopy(bloomBytes, offset, chunk, 0, chunkSize);
    //$$         FriendlyByteBuf buf = PacketByteBufs.create();
    //$$         new ChunkCacheManifestPayload(chunk, hasMore).write(buf);
    //$$         ClientPlayNetworking.send(ChunkCacheManifestPayload.CHANNEL, buf);
    //$$         offset += chunkSize;
    //$$     }
    //$$ }
    //$$
    //$$ private static List<ResourceLocation> collectRegisteredTypes() {
    //$$     return REGISTERED_CHANNELS.stream()
    //$$             .sorted(Comparator.comparing(ResourceLocation::getNamespace).thenComparing(ResourceLocation::getPath))
    //$$             .collect(Collectors.toList());
    //$$ }
    //#endif
}
