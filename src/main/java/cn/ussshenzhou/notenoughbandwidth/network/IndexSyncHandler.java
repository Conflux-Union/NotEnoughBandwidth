package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
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
        PayloadTypeRegistry.playS2C().register(IndexSyncPayload.TYPE, IndexSyncPayload.CODEC);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            List<Identifier> types = collectRegisteredTypes();
            NamespaceIndexManager.init(types);
            // Do NOT init AggregationManager or mark connection here.
            // We wait for the client to send NebAckPayload before enabling the compression path.
            sender.sendPacket(new IndexSyncPayload(types));
            LOGGER.info("Sent index sync to {} ({} types), awaiting NEB ack",
                    handler.player.getName().getString(), types.size());
        });
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(IndexSyncPayload.TYPE, (payload, context) -> {
            LOGGER.info("Received index sync from server ({} types)", payload.types().size());
            NamespaceIndexManager.init(payload.types());
            AggregationManager.init();
            // Mark our outbound connection as NEB-capable (server always has NEB if it sent this).
            NebConnectionRegistry.markEnabled(context.player().networkHandler.connection);
            // Tell the server we have NEB installed so it enables the compression path for us.
            ClientPlayNetworking.send(new NebAckPayload());
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
