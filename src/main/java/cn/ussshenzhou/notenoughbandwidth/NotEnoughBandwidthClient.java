package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.bench.BenchClientAutoJoin;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.network.IndexSyncHandler;
import cn.ussshenzhou.notenoughbandwidth.network.ModNetworking;
import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.ModKey;
import cn.ussshenzhou.notenoughbandwidth.stat.SystemTrafficMonitor;
import cn.ussshenzhou.notenoughbandwidth.zstd.ZstdHelper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NotEnoughBandwidthClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Client");

    @Override
    public void onInitializeClient() {
        // Bench auto-join runs regardless of NEB disable flag — it drives the real
        // Minecraft client into the benchmark server on CI (measured on the server side).
        BenchClientAutoJoin.maybeInstall();

        if (Boolean.getBoolean(NotEnoughBandwidth.PROP_DISABLE)) {
            LOGGER.info("NEB disabled via -D{}=true (baseline mode). Skipping client init.",
                    NotEnoughBandwidth.PROP_DISABLE);
            return;
        }

        ModKey.register();
        ModNetworking.registerClient();
        IndexSyncHandler.registerClient();
        SystemTrafficMonitor.init();

        ChunkCacheManager.setGameDir(MinecraftClient.getInstance().runDirectory.toPath());

        // On server switch (Velocity), the same ClientConnection is reused but the
        // backend server changes. Disable NEB and discard stale buffered packets so
        // everything flows vanilla until the new server's handshake re-enables NEB.
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            var conn = handler.getConnection();
            NebConnectionRegistry.markDisabled(conn);
            AggregationManager.discardConnection(conn);
            ZstdHelper.evict(conn);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                ChunkCacheManager.onClientDisconnect());
    }
}
