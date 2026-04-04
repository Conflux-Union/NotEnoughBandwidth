package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
import cn.ussshenzhou.notenoughbandwidth.zstd.DictionaryManager;
import cn.ussshenzhou.notenoughbandwidth.zstd.ZstdHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Synchronizes the payload type index table between server and client.
 * <p>
 * On NeoForge this was free via modded network negotiation.
 * On Fabric we do it ourselves: server collects all registered CustomPayload
 * types, sorts them, sends the list to the client on join.
 */
public class IndexSyncHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-IndexSync");
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
        PayloadTypeRegistry.playS2C().register(DictionarySyncPayload.TYPE, DictionarySyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(IndexSyncPayload.TYPE, IndexSyncPayload.CODEC);

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
            var conn = context.player().networkHandler.connection;
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
            var connection = context.player().networkHandler.connection;
            NebConnectionRegistry.markEnabled(connection);

            // Open chunk cache keyed by server UUID (reliable behind proxies).
            // Fall back to connection address if the server is an old NEB version without UUID.
            String cacheKey = payload.serverId().isEmpty()
                    ? connection.getAddress().toString()
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
                var s2cMap = (Map<Identifier, ?>) packetTypesField.get(PayloadTypeRegistryImpl.PLAY_S2C);
                var c2sMap = (Map<Identifier, ?>) packetTypesField.get(PayloadTypeRegistryImpl.PLAY_C2S);
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
}
