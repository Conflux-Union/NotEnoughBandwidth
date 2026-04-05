package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
import cn.ussshenzhou.notenoughbandwidth.zstd.DictionaryManager;
import cn.ussshenzhou.notenoughbandwidth.zstd.ZstdHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Synchronizes the payload type index table between server and client.
 * <p>
 * On NeoForge this was free via modded network negotiation.
 * On Fabric we do it ourselves: server collects all registered custom channel
 * identifiers, sorts them, sends the list to the client on join.
 * <p>
 * In 1.20.1 there is no PayloadTypeRegistry, so we manually track all
 * channels that NEB registers via {@link #registerChannel(Identifier)}.
 */
public class IndexSyncHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-IndexSync");

    private static final Set<Identifier> REGISTERED_CHANNELS = new LinkedHashSet<>();

    /**
     * Called by ModNetworking and IndexSyncHandler during registration to track
     * all custom payload channels for index sync.
     */
    public static void registerChannel(Identifier channel) {
        REGISTERED_CHANNELS.add(channel);
    }

    public static void registerServer() {
        // Track S2C-only channels that are registered here (not in ModNetworking).
        registerChannel(DictionarySyncPayload.CHANNEL);
        registerChannel(IndexSyncPayload.CHANNEL);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var connection = handler.connection;
            ChunkCacheManager.removeServerBloomFilter(connection);
            AggregationManager.discardConnection(connection);
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // Send dictionary first so the client has it before compression starts.
            byte[] dict = DictionaryManager.getDict();
            PacketByteBuf dictBuf = PacketByteBufs.create();
            new DictionarySyncPayload(dict).write(dictBuf);
            sender.sendPacket(new CustomPayloadS2CPacket(DictionarySyncPayload.CHANNEL, dictBuf));

            List<Identifier> types = collectRegisteredTypes();
            // Only init once on dedicated server — registered types don't change after startup,
            // and re-init would race with readers that don't hold the lock.
            if (!NamespaceIndexManager.ready()) {
                NamespaceIndexManager.init(types);
            }
            // Do NOT init AggregationManager or mark connection here.
            // We wait for the client to send NebAckPayload before enabling the compression path.
            String serverId = NotEnoughBandwidthConfig.get().serverUUID;
            PacketByteBuf indexBuf = PacketByteBufs.create();
            new IndexSyncPayload(types, serverId).write(indexBuf);
            sender.sendPacket(new CustomPayloadS2CPacket(IndexSyncPayload.CHANNEL, indexBuf));
            LOGGER.info("Sent dictionary ({}) and index sync to {} ({} types, serverId={}), awaiting NEB ack",
                    dict != null ? dict.length + " bytes" : "none",
                    handler.player.getName().getString(), types.size(), serverId);
        });
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(DictionarySyncPayload.CHANNEL, (client, handler, buf, responseSender) -> {
            var payload = DictionarySyncPayload.read(new PacketByteBuf(buf.copy()));
            DictionaryManager.setDict(payload.dictionary());
            var conn = handler.getConnection();
            ZstdHelper.evict(conn);
            if (payload.dictionary() != null && payload.dictionary().length > 0) {
                LOGGER.info("Received dictionary from server ({} bytes)", payload.dictionary().length);
            } else {
                LOGGER.info("Server has no trained dictionary yet");
            }
        });

        ClientPlayNetworking.registerGlobalReceiver(IndexSyncPayload.CHANNEL, (client, handler, buf, responseSender) -> {
            var payload = IndexSyncPayload.read(new PacketByteBuf(buf.copy()));
            LOGGER.info("Received index sync from server ({} types, serverId={})",
                    payload.types().size(), payload.serverId());
            NamespaceIndexManager.init(payload.types());
            AggregationManager.init();
            var connection = handler.getConnection();
            NebConnectionRegistry.markEnabled(connection);

            // Open chunk cache keyed by server UUID (reliable behind proxies).
            // Fall back to connection address if the server is an old NEB version without UUID.
            String cacheKey = payload.serverId().isEmpty()
                    ? connection.getAddress().toString()
                    : payload.serverId();
            ChunkCacheManager.onClientConnect(cacheKey);

            // Must send on the client thread — Fabric 1.20.1 rejects sends from
            // the Netty IO thread during early PLAY phase.
            client.execute(() -> {
                try {
                    PacketByteBuf ackBuf = PacketByteBufs.create();
                    new NebAckPayload().write(ackBuf);
                    ClientPlayNetworking.send(NebAckPayload.CHANNEL, ackBuf);
                    byte[] bloomBytes = ChunkCacheManager.getClientBloomFilterBytes();
                    if (bloomBytes != null && bloomBytes.length > 0) {
                        PacketByteBuf manifestBuf = PacketByteBufs.create();
                        new ChunkCacheManifestPayload(bloomBytes).write(manifestBuf);
                        ClientPlayNetworking.send(ChunkCacheManifestPayload.CHANNEL, manifestBuf);
                        LOGGER.info("Sent chunk cache manifest ({} bytes)", bloomBytes.length);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to send NEB ack/manifest", e);
                }
            });
        });
    }

    private static List<Identifier> collectRegisteredTypes() {
        return REGISTERED_CHANNELS.stream()
                .sorted(Comparator.comparing(Identifier::getNamespace).thenComparing(Identifier::getPath))
                .collect(Collectors.toList());
    }
}
