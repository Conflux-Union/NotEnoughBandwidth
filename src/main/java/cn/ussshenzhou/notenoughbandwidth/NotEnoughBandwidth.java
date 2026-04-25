package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.bench.BenchmarkRunner;
import cn.ussshenzhou.notenoughbandwidth.command.NebCommand;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.network.IndexSyncHandler;
import cn.ussshenzhou.notenoughbandwidth.network.ModNetworking;
import cn.ussshenzhou.notenoughbandwidth.stat.SystemTrafficMonitor;
import cn.ussshenzhou.notenoughbandwidth.zstd.DictionaryManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class NotEnoughBandwidth implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModConstants.MOD_ID);

    /** `-Dneb.disableMod=true` turns NEB into a no-op mod (for baseline benchmarks). */
    public static final String PROP_DISABLE = "neb.disableMod";

    @Override
    public void onInitialize() {
        // Benchmark orchestrator runs regardless of disable flag — it only reads
        // server events and drives scenarios; it needs to fire even when NEB itself
        // is disabled so we can measure the vanilla baseline.
        BenchmarkRunner.maybeInstall();

        if (Boolean.getBoolean(PROP_DISABLE)) {
            LOGGER.info("NEB disabled via -D{}=true (baseline mode). Skipping mod init.", PROP_DISABLE);
            return;
        }

        ConfigHelper.loadConfig(new NotEnoughBandwidthConfig());
        ensureServerUUID();
        DictionaryManager.loadFromDisk();
        ModNetworking.registerCommon();
        IndexSyncHandler.registerServer();
        SystemTrafficMonitor.init();
        NebCommand.register();
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
