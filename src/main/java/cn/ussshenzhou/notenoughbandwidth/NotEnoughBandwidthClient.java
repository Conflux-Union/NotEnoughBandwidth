package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
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

public class NotEnoughBandwidthClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModKey.register();
        ModNetworking.registerClient();
        IndexSyncHandler.registerClient();
        SystemTrafficMonitor.init();

        ChunkCacheManager.setGameDir(MinecraftClient.getInstance().runDirectory.toPath());

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
