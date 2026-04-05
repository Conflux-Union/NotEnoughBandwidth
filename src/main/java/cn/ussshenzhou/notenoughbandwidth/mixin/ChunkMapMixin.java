package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.chunk.CachedChunkTrackingView;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import org.apache.commons.lang3.mutable.MutableObject;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class ChunkMapMixin {

    @Shadow
    @Final
    ServerWorld world;

    @Shadow
    int watchDistance;

    @Unique
    private static final AtomicReference<ChunkTicketType<ChunkPos>> nebDccTicket = new AtomicReference<>();

    /**
     * Inject at HEAD of updatePosition to apply DCC chunk tracking.
     * In 1.20.1, updatePosition handles player movement and chunk tracking updates.
     */
    @Inject(method = "updatePosition", at = @At("HEAD"))
    private void nebDccUpdatePosition(ServerPlayerEntity player, CallbackInfo ci) {
        if (player.getWorld() != this.world) {
            return;
        }
        CachedChunkTrackingView.onUpdateChunkTracking(player, watchDistance, new CachedChunkTrackingView.Context() {
            @Override
            public void startChunkTracking(ChunkPos pos) {
                // Chunk tracking start is handled by vanilla's updatePosition
            }

            @Override
            public void stopChunkTracking(ChunkPos pos) {
                // Chunk untracking is handled by vanilla's updatePosition
            }

            @Override
            public void putTicket(ChunkPos pos, int ticks) {
                var ticketType = nebDccTicket.get();
                if (ticketType == null || ticketType.getExpiryTicks() != ticks) {
                    var newType = ChunkTicketType.<ChunkPos>create("neb_dcc",
                            Comparator.comparingLong(ChunkPos::toLong), ticks);
                    nebDccTicket.compareAndSet(ticketType, newType);
                    ticketType = nebDccTicket.get();
                }
                world.getChunkManager().addTicket(ticketType, pos, 1, pos);
            }
        });
    }
}
