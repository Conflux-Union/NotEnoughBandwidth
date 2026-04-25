package cn.ussshenzhou.notenoughbandwidth.bench.scenarios;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;

import java.util.Collections;

/**
 * Teleports the player along a circular path, crossing a new chunk every ~16
 * ticks, for the full scenario duration. Forces continuous chunk sends: the
 * dominant S2C bandwidth in real play.
 */
public final class RoamScenario implements BenchScenario {
    private static final double RADIUS = 256.0;
    private static final double Y = 80.0;
    private static final double STEP = 1.0;

    @Override
    public String name() {
        return "roam";
    }

    @Override
    public void setup(MinecraftServer server, ServerPlayer player) {
        player.setGameMode(GameType.SPECTATOR);
        teleportTo(player, RADIUS, 0);
    }

    @Override
    public void tick(int tick, MinecraftServer server, ServerPlayer player) {
        double angle = (tick * STEP) / RADIUS;
        double x = Math.cos(angle) * RADIUS;
        double z = Math.sin(angle) * RADIUS;
        teleportTo(player, x, z);
    }

    private static void teleportTo(ServerPlayer player, double x, double z) {
        player.teleportTo(
                (ServerLevel) player.level(),
                x, Y, z,
                Collections.<Relative>emptySet(),
                0f, 0f, false
        );
    }
}
