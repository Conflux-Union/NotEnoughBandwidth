package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.bench.BenchmarkRunner;
import cn.ussshenzhou.notenoughbandwidth.command.NebCommand;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.network.IndexSyncHandler;
import cn.ussshenzhou.notenoughbandwidth.network.ModNetworking;
import cn.ussshenzhou.notenoughbandwidth.stat.SystemTrafficMonitor;
import cn.ussshenzhou.notenoughbandwidth.zstd.DictionaryManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class NotEnoughBandwidth implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID);

    @Override
    public void onInitialize() {
        if (Boolean.getBoolean("neb.disableMod")) {
            LOGGER.warn("NEB runtime initialization skipped because neb.disableMod=true");
            maybeInstallServerBenchmark();
            return;
        }
        ConfigHelper.loadConfig(new NotEnoughBandwidthConfig());
        ensureServerUUID();
        DictionaryManager.loadFromDisk();
        ModNetworking.registerCommon();
        IndexSyncHandler.registerServer();
        NebCommand.register();
        maybeInstallServerBenchmark();
        SystemTrafficMonitor.init();
        LOGGER.info("NEB initialized.");
    }

    private static void maybeInstallServerBenchmark() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            BenchmarkRunner.maybeInstall();
        }
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
