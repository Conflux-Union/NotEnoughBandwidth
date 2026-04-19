package cn.ussshenzhou.notenoughbandwidth.bench;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Real-client bench driver: when `-Dneb.benchClientAutoJoin=host:port` is set,
 * hooks the first client tick to programmatically drive MinecraftClient through
 * the real JoinMultiplayer flow, then quits the JVM when the server disconnects.
 * <p>
 * Unlike the old Proxy-based fake client, this goes through actual Minecraft
 * client code — so we measure whatever a real player would produce: full
 * LOGIN/CONFIG/PLAY handshake, chunk rendering, entity tracking, NEB mod
 * handshake (when enabled), everything.
 * <p>
 * Why START_CLIENT_TICK rather than CLIENT_STARTED: CLIENT_STARTED fires
 * before the first render pass completes, and `ConnectScreen.connect` spawns
 * a worker thread that can reach into the debug overlay for the bandwidth
 * logger. Waiting one tick guarantees all startup-phase fields are populated.
 */
public final class BenchClientAutoJoin {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Bench-Client");
    public static final String PROP_TARGET = "neb.benchClientAutoJoin";

    public static void maybeInstall() {
        String target = System.getProperty(PROP_TARGET, "").trim();
        if (target.isEmpty()) return;

        int colon = target.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(PROP_TARGET + " must be host:port, got: " + target);
        }
        String host = target.substring(0, colon);
        int port = Integer.parseInt(target.substring(colon + 1));
        LOGGER.info("BenchClientAutoJoin armed: target={}:{}", host, port);

        AtomicBoolean fired = new AtomicBoolean();
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (fired.compareAndSet(false, true)) {
                connect(client, host, port);
            }
        });

        // When the server finishes its scenario it calls server.stop(), which sends us
        // a DisconnectS2CPacket (or drops the socket). Either path fires DISCONNECT here.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("BenchClientAutoJoin: server disconnected — scheduling client shutdown");
            client.scheduleStop();
        });
    }

    private static void connect(MinecraftClient client, String host, int port) {
        try {
            ServerInfo info = new ServerInfo("NEB Bench Server", host + ":" + port, ServerInfo.ServerType.OTHER);
            ConnectScreen.connect(
                    new TitleScreen(),
                    client,
                    new ServerAddress(host, port),
                    info,
                    false,
                    null
            );
            LOGGER.info("BenchClientAutoJoin: ConnectScreen.connect dispatched to {}:{}", host, port);
        } catch (Throwable t) {
            LOGGER.error("BenchClientAutoJoin: connect failed", t);
            client.scheduleStop();
        }
    }
}
