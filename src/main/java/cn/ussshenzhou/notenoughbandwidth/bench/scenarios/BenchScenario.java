package cn.ussshenzhou.notenoughbandwidth.bench.scenarios;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * A benchmark scenario that runs for a fixed number of ticks, driven from the
 * server main thread. Each scenario is responsible for its own setup on tick 0
 * and per-tick progression; teardown (despawn, reset) is not needed because
 * the server halts after the final tick.
 */
public interface BenchScenario {
    String name();

    /** Called once on the tick the player has fully entered PLAY state. */
    void setup(MinecraftServer server, ServerPlayerEntity player);

    /** Called every server tick, tick index starts at 0. */
    void tick(int tick, MinecraftServer server, ServerPlayerEntity player);
}
