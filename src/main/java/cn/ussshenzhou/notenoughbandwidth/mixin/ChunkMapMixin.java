package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.chunk.CachedChunkTrackingView;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Comparator;

@Mixin(ThreadedAnvilChunkStorage.class)
public abstract class ChunkMapMixin {

    @Shadow
    @Final
    ServerWorld world;

    @Shadow
    int watchDistance;

    @Unique
    private static ChunkTicketType<ChunkPos> nebDccTicketType;

    @Unique
    private static ChunkTicketType<ChunkPos> nebGetOrCreateTicketType(int expiryTicks) {
        ChunkTicketType<ChunkPos> current = nebDccTicketType;
        if (current == null || current.getExpiryTicks() != expiryTicks) {
            current = ChunkTicketType.create("neb_dcc", Comparator.comparingLong(ChunkPos::toLong), expiryTicks);
            nebDccTicketType = current;
        }
        return current;
    }

    /**
     * Inject at HEAD of updatePosition to apply DCC chunk tickets.
     * Vanilla's own sendWatchPackets handles actual chunk data delivery —
     * DCC just ensures chunks stay loaded a bit longer via tickets.
     */
    @Inject(method = "updatePosition", at = @At("HEAD"))
    private void nebDccUpdatePosition(ServerPlayerEntity player, CallbackInfo ci) {
        if (player.getWorld() != this.world) {
            return;
        }
        CachedChunkTrackingView.onUpdateChunkTracking(player, watchDistance, (pos, ticks) -> {
            ChunkTicketType<ChunkPos> ticketType = nebGetOrCreateTicketType(ticks);
            // radius 0 => level FULL (33), sufficient for chunk data to be sendable
            world.getChunkManager().addTicket(ticketType, pos, 0, pos);
        });
    }

    /**
     * Clean up per-player DCC state when a player is removed.
     */
    @Inject(method = "handlePlayerAddedOrRemoved", at = @At("HEAD"))
    private void nebDccHandlePlayerRemoved(ServerPlayerEntity player, boolean added, CallbackInfo ci) {
        if (!added) {
            CachedChunkTrackingView.removePlayer(player);
        }
    }
}
