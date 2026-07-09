package cn.ussshenzhou.notenoughbandwidth;

import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.bench.BenchClientAutoJoin;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.network.IndexSyncHandler;
import cn.ussshenzhou.notenoughbandwidth.network.ModNetworking;
import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.ModKey;
import cn.ussshenzhou.notenoughbandwidth.stat.SystemTrafficMonitor;
import cn.ussshenzhou.notenoughbandwidth.update.UpdateChecker;
import cn.ussshenzhou.notenoughbandwidth.zstd.ZstdHelper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public class NotEnoughBandwidthClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Client");
    private static final AtomicBoolean updateNotified = new AtomicBoolean(false);

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

        // Fire-and-forget: check GitHub releases for a newer version on every launch.
        if (NotEnoughBandwidthConfig.get().checkUpdate) {
            UpdateChecker.checkAsync();
        }

        // Notify the player once when they enter a world, if an update was found.
        // Handles both the common case (check done before join) and the slow-network
        // case (check finishes after join — deferred to the client thread via execute()).
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!NotEnoughBandwidthConfig.get().checkUpdate) return;
            maybeNotifyUpdate(client);
        });

        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            var conn = handler.getConnection();
            NebConnectionRegistry.markDisabled(conn);
            AggregationManager.discardConnection(conn);
            ZstdHelper.evict(conn);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                ChunkCacheManager.onClientDisconnect());
    }

    private static void maybeNotifyUpdate(MinecraftClient client) {
        if (updateNotified.get()) return;

        var info = UpdateChecker.getUpdateInfo();
        if (info != null) {
            if (updateNotified.compareAndSet(false, true)) {
                sendUpdateMessage(client, info);
            }
            return;
        }

        // Check still in flight — show the message when it completes.
        if (!UpdateChecker.isCheckComplete()) {
            UpdateChecker.onComplete(() -> {
                var lateInfo = UpdateChecker.getUpdateInfo();
                if (lateInfo == null) return;
                // Schedule on the client thread — the CompletableFuture callback
                // may run on the HTTP worker thread.
                client.execute(() -> {
                    if (updateNotified.compareAndSet(false, true)) {
                        sendUpdateMessage(client, lateInfo);
                    }
                });
            });
        }
    }

    private static void sendUpdateMessage(MinecraftClient client, UpdateChecker.UpdateInfo info) {
        if (client.player == null) return;

        String currentVersion = UpdateChecker.getLocalVersion();

        var link = Text.translatable("neb.update.download")
                .styled(s -> s
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, info.releaseUrl()))
                        .withColor(Formatting.BLUE)
                        .withUnderline(true));

        var message = Text.translatable("neb.update.available", info.latestVersion(), currentVersion)
                .append(Text.literal(" "))
                .append(link);

        client.player.sendMessage(message, false);
    }
}
