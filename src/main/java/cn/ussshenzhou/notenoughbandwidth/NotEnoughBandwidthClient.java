package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.network.IndexSyncHandler;
import cn.ussshenzhou.notenoughbandwidth.network.ModNetworking;
import cn.ussshenzhou.notenoughbandwidth.stat.ModKey;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;

public class NotEnoughBandwidthClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModKey.register();
        ModNetworking.registerClient();
        IndexSyncHandler.registerClient();

        // Set game dir so ChunkCacheManager knows where to store the DB.
        ChunkCacheManager.setGameDir(MinecraftClient.getInstance().runDirectory.toPath());

        // Open/close the chunk cache DB around server connections.
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            String address = handler.getConnection().getAddress().toString();
            ChunkCacheManager.onClientConnect(address);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                ChunkCacheManager.onClientDisconnect());
    }
}
