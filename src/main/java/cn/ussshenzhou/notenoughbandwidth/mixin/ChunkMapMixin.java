package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.chunk.CachedChunkTrackingView;
import net.minecraft.server.network.ChunkFilter;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.*;

/**
 * In 1.21.4 Yarn, the chunk tracking update is handled by:
 * - sendWatchPackets(ServerPlayerEntity) — computes new filter, checks change, applies
 * - sendWatchPackets(ServerPlayerEntity, ChunkFilter) — applies diff + setChunkFilter
 * - track(ServerPlayerEntity, ChunkPos) — start sending chunk to player
 * - untrack(ServerPlayerEntity, ChunkPos) — stop sending chunk to player
 * - getViewDistance(ServerPlayerEntity) — per-player view distance
 */
@Mixin(ServerChunkLoadingManager.class)
public abstract class ChunkMapMixin {

    @Shadow
    @Final
    ServerWorld world;

    @Shadow
    int getViewDistance(ServerPlayerEntity player) { throw new AssertionError(); }

    @Shadow
    private void track(ServerPlayerEntity player, ChunkPos pos) {}

    @Shadow
    private static void untrack(ServerPlayerEntity player, ChunkPos pos) {}

    /**
     * @author NEB
     * @reason Replace vanilla chunk tracking with DCC-aware version
     */
    @Overwrite
    private void sendWatchPackets(ServerPlayerEntity player) {
        if (player.getWorld() != this.world) {
            return;
        }
        CachedChunkTrackingView.onUpdateChunkTracking(player, getViewDistance(player), new CachedChunkTrackingView.Context() {
            @Override
            public void startChunkTracking(ChunkPos pos) {
                track(player, pos);
            }

            @Override
            public void stopChunkTracking(ChunkPos pos) {
                untrack(player, pos);
            }

            @Override
            public void putTicket(ChunkPos pos, int ticks) {
                // Chunk ticket to keep cached chunks loaded.
                // In vanilla there's no direct equivalent;
                // the DCC relies on the chunk still being in memory.
                // For a proper implementation, use the TicketManager.
                // TODO: integrate with ServerChunkLoadingManager's ticket system
            }
        });
    }

    @Shadow
    protected abstract void setViewDistance(int viewDistance);
}
