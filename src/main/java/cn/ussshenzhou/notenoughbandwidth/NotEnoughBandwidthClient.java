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
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

public class NotEnoughBandwidthClient implements ClientModInitializer {
    private static final AtomicBoolean updateNotified = new AtomicBoolean(false);

    @Override
    public void onInitializeClient() {
        if (Boolean.getBoolean("neb.disableMod")) {
            BenchClientAutoJoin.maybeInstall();
            return;
        }
        ModKey.register();
        ModNetworking.registerClient();
        IndexSyncHandler.registerClient();
        BenchClientAutoJoin.maybeInstall();
        SystemTrafficMonitor.init();

        ChunkCacheManager.setGameDir(Minecraft.getInstance().gameDirectory.toPath());

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

        // On server switch (Velocity), the same Connection is reused but the
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

    private static void maybeNotifyUpdate(Minecraft client) {
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

    private static void sendUpdateMessage(Minecraft client, UpdateChecker.UpdateInfo info) {
        if (client.player == null) return;

        String currentVersion = UpdateChecker.getLocalVersion();

        var link = Component.translatable("neb.update.download")
                .withStyle(s -> s
                        //#if MC>=12106
                        .withClickEvent(new ClickEvent.OpenUrl(URI.create(info.releaseUrl())))
                        //#else
                        //$$ .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, info.releaseUrl()))
                        //#endif
                        .withColor(ChatFormatting.BLUE)
                        .withUnderlined(true));

        var message = Component.translatable("neb.update.available", info.latestVersion(), currentVersion)
                .append(Component.literal(" "))
                .append(link);

        //#if MC>=260100
        client.player.sendSystemMessage(message);
        //#else
        //$$ client.player.displayClientMessage(message, false);
        //#endif
    }
}
