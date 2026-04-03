package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.network.IndexSyncHandler;
import cn.ussshenzhou.notenoughbandwidth.network.ModNetworking;
import cn.ussshenzhou.notenoughbandwidth.stat.ModKey;
import net.fabricmc.api.ClientModInitializer;

public class NotEnoughBandwidthClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModKey.register();
        ModNetworking.registerClient();
        IndexSyncHandler.registerClient();
    }
}
