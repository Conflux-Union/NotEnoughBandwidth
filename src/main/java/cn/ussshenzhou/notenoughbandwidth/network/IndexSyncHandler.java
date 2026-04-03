package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Handles index table synchronization between server and client.
 * <p>
 * On NeoForge, this was free via the modded network negotiation.
 * On Fabric, we implement it ourselves:
 * <ol>
 *   <li>Server collects all registered CustomPayload types from both S2C and C2S registries</li>
 *   <li>Server sorts them deterministically and builds its index table</li>
 *   <li>Server sends the sorted list to the client via IndexSyncPayload</li>
 *   <li>Client receives the list and builds the same index table</li>
 *   <li>Both sides are now synchronized for the PLAY phase</li>
 * </ol>
 */
public class IndexSyncHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-IndexSync");

    public static void registerServer() {
        PayloadTypeRegistry.playS2C().register(IndexSyncPayload.TYPE, IndexSyncPayload.CODEC);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            List<Identifier> types = collectRegisteredTypes();
            NamespaceIndexManager.init(types);
            AggregationManager.init();
            sender.sendPacket(new IndexSyncPayload(types));
            LOGGER.info("Sent index sync to {} ({} types)", handler.player.getName().getString(), types.size());
        });
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(IndexSyncPayload.TYPE, (payload, context) -> {
            LOGGER.info("Received index sync from server ({} types)", payload.types().size());
            NamespaceIndexManager.init(payload.types());
            AggregationManager.init();
        });
    }

    /**
     * Collect all registered custom payload type Identifiers from Fabric's
     * PayloadTypeRegistry for the PLAY phase (both S2C and C2S).
     */
    private static List<Identifier> collectRegisteredTypes() {
        var types = new ArrayList<Identifier>();

        // Collect from S2C registry
        var s2cRegistry = PayloadTypeRegistry.playS2C();
        // Collect from C2S registry
        var c2sRegistry = PayloadTypeRegistry.playC2S();

        // The PayloadTypeRegistry doesn't directly expose iteration,
        // so we need to collect types that were registered.
        // For now, we'll maintain a manual registry of known types.
        // TODO: Find a way to enumerate all registered payload types from Fabric API,
        // or maintain a parallel registry during mod initialization.
        //
        // As a workaround, we can mixin into PayloadTypeRegistry to intercept
        // registrations and collect the identifiers.

        types.sort(Comparator.comparing(Identifier::getNamespace).thenComparing(Identifier::getPath));
        return types;
    }
}
