package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.network.IndexSyncHandler;
import cn.ussshenzhou.notenoughbandwidth.network.ModNetworking;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NotEnoughBandwidth implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID);

    @Override
    public void onInitialize() {
        ConfigHelper.loadConfig(new NotEnoughBandwidthConfig());
        ModNetworking.registerCommon();
        IndexSyncHandler.registerServer();
        LOGGER.info("NEB initialized.");
    }
}
