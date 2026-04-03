package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.chunk.CachedChunkTrackingView;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.*;

@Mixin(ServerChunkLoadingManager.class)
public abstract class ChunkMapMixin {

    @Shadow
    @Final
    ServerWorld world;

    @Overwrite
    private void updateChunkTracking(ServerPlayerEntity player) {
        if (player.getWorld() != this.world) {
            return;
        }
        CachedChunkTrackingView.onUpdateChunkTracking(player, getWatchDistance(), new CachedChunkTrackingView.Context() {
            @Override
            public void startChunkTracking(ChunkPos pos) {
                // TODO: call markChunkPendingToSend equivalent
            }

            @Override
            public void stopChunkTracking(ChunkPos pos) {
                // TODO: call dropChunk equivalent
            }

            @Override
            public void putTicket(ChunkPos pos, int ticks) {
                // TODO: add chunk ticket
            }
        });
    }

    @Shadow
    protected abstract int getWatchDistance();
}
