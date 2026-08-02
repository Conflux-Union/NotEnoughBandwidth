package cn.ussshenzhou.notenoughbandwidth.bench.scenarios;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
//#if MC>=12104
import net.minecraft.world.entity.EntitySpawnReason;
//#endif
//#if MC>=260200
import net.minecraft.world.entity.EntityTypes;
//#else
//$$ import net.minecraft.world.entity.EntityType;
//#endif
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.Random;

/**
 * Spawns a fixed ring of 200 villagers around the player's stationary position,
 * then holds still for the full scenario. Captures entity-spawn, entity-motion
 * and entity-metadata S2C traffic: the "crowded server" bandwidth profile.
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
    public void setup(MinecraftServer server, ServerPlayer player) {
        player.setGameMode(GameType.SPECTATOR);
        ServerLevel world = (ServerLevel) player.level();
        //#if MC>=12104
        player.teleportTo(
                world,
                CENTER_X, CENTER_Y, CENTER_Z,
                Collections.<Relative>emptySet(),
                0f, 0f, false
        );
        //#else
        //$$ player.teleportTo(
        //$$         world,
        //$$         CENTER_X, CENTER_Y, CENTER_Z,
        //$$         Collections.<RelativeMovement>emptySet(),
        //$$         0f, 0f
        //$$ );
        //#endif
        Random rng = new Random(42L);
        for (int i = 0; i < COUNT; i++) {
            double angle = (Math.PI * 2.0 * i) / COUNT;
            double x = CENTER_X + Math.cos(angle) * RING_RADIUS;
            double z = CENTER_Z + Math.sin(angle) * RING_RADIUS;
            //#if MC>=260200
            Villager v = EntityTypes.VILLAGER.create(world, EntitySpawnReason.EVENT);
            //#elseif MC>=12104
            //$$ Villager v = EntityType.VILLAGER.create(world, EntitySpawnReason.EVENT);
            //#else
            //$$ Villager v = EntityType.VILLAGER.create(world);
            //#endif
            if (v == null) continue;
            v.setPos(x, CENTER_Y, z);
            v.setYRot(rng.nextFloat() * 360f);
            v.setXRot(0f);
            world.addFreshEntity(v);
        }
    }

    @Override
    public void tick(int tick, MinecraftServer server, ServerPlayer player) {
        // Stand still and let the AI + entity-tracker produce natural S2C updates.
        // Every ~100 ticks nudge the player to keep chunk tracker awake; otherwise
        // the server may throttle update rates for a fully idle player.
        if (tick % 100 == 0) {
            Vec3 p = player.position();
            //#if MC>=12104
            player.teleportTo(
                    (ServerLevel) player.level(),
                    p.x, p.y, p.z,
                    Collections.<Relative>emptySet(),
                    (tick / 100f) * 37f, 0f, false
            );
            //#else
            //$$ player.teleportTo(
            //$$         (ServerLevel) player.level(),
            //$$         p.x, p.y, p.z,
            //$$         Collections.<RelativeMovement>emptySet(),
            //$$         (tick / 100f) * 37f, 0f
            //$$ );
            //#endif
        }
    }
}
