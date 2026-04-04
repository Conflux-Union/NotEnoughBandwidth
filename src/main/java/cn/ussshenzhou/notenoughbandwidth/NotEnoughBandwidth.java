package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.network.IndexSyncHandler;
import cn.ussshenzhou.notenoughbandwidth.network.ModNetworking;
import cn.ussshenzhou.notenoughbandwidth.zstd.DictionaryManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class NotEnoughBandwidth implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID);

    @Override
    public void onInitialize() {
        ConfigHelper.loadConfig(new NotEnoughBandwidthConfig());
        ensureServerUUID();
        DictionaryManager.loadFromDisk();
        ModNetworking.registerCommon();
        IndexSyncHandler.registerServer();
        LOGGER.info("NEB initialized.");
    }

    private static void ensureServerUUID() {
        var cfg = NotEnoughBandwidthConfig.get();
        if (cfg.serverUUID == null || cfg.serverUUID.isEmpty()) {
            ConfigHelper.getConfigWrite(NotEnoughBandwidthConfig.class,
                    c -> c.serverUUID = UUID.randomUUID().toString());
            LOGGER.info("Generated new server UUID: {}", NotEnoughBandwidthConfig.get().serverUUID);
        }
    }
}
