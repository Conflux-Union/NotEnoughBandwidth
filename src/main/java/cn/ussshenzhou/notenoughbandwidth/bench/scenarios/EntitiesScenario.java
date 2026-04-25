package cn.ussshenzhou.notenoughbandwidth.bench.scenarios;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.util.Collections;
import java.util.Random;

/**
 * Spawns a fixed ring of 200 villagers around the player's stationary position,
 * then holds still for the full scenario. Captures entity-spawn, entity-motion
 * and entity-metadata S2C traffic — the "crowded server" bandwidth profile.
 */
public final class EntitiesScenario implements BenchScenario {
    private static final int COUNT = 200;
    private static final double RING_RADIUS = 12.0;
    private static final double CENTER_X = 0.5;
    private static final double CENTER_Y = 80.0;
    private static final double CENTER_Z = 0.5;

    @Override
    public String name() {
        return "entities";
    }

    @Override
    public void setup(MinecraftServer server, ServerPlayerEntity player) {
        player.changeGameMode(GameMode.SPECTATOR);
        player.teleport(
                player.getEntityWorld(),
                CENTER_X, CENTER_Y, CENTER_Z,
                Collections.<PositionFlag>emptySet(),
                0f, 0f,
                false
        );
        ServerWorld world = player.getEntityWorld();
        Random rng = new Random(42L);
        for (int i = 0; i < COUNT; i++) {
            double angle = (Math.PI * 2.0 * i) / COUNT;
            double x = CENTER_X + Math.cos(angle) * RING_RADIUS;
            double z = CENTER_Z + Math.sin(angle) * RING_RADIUS;
            VillagerEntity v = EntityType.VILLAGER.create(world, SpawnReason.COMMAND);
            if (v == null) continue;
            v.refreshPositionAndAngles(x, CENTER_Y, z, rng.nextFloat() * 360f, 0f);
            world.spawnEntity(v);
        }
    }

    @Override
    public void tick(int tick, MinecraftServer server, ServerPlayerEntity player) {
        // Stand still — let the AI + entity-tracker produce natural S2C updates.
        // Every ~100 ticks nudge the player to keep chunk tracker awake; otherwise
        // the server may throttle update rates for a fully idle player.
        if (tick % 100 == 0) {
            Vec3d p = player.getEntityPos();
            player.teleport(
                    player.getEntityWorld(),
                    p.x, p.y, p.z,
                    Collections.<PositionFlag>emptySet(),
                    (tick / 100f) * 37f, 0f,
                    false
            );
        }
    }
}
