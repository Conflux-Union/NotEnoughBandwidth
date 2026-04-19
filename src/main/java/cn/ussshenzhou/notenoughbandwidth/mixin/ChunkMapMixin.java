package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.chunk.CachedChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.*;

import java.util.concurrent.atomic.AtomicReference;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow
    @Final
    ServerLevel level;

    @Unique
    private static final AtomicReference<TicketType> nebDccTicket = new AtomicReference<>();

    @Shadow
    int getPlayerViewDistance(ServerPlayer player) { throw new AssertionError(); }

    @Shadow
    private void markChunkPendingToSend(ServerPlayer player, ChunkPos pos) {}

    @Shadow
    private static void dropChunk(ServerPlayer player, ChunkPos pos) {}

    @Shadow
    @Final
    private TicketStorage ticketStorage;

    /**
     * @author NEB
     * @reason Replace vanilla chunk tracking with DCC-aware version
     */
    @Overwrite
    private void updateChunkTracking(ServerPlayer player) {
        if (player.level() != this.level) {
            return;
        }
        CachedChunkTrackingView.onUpdateChunkTracking(player, getPlayerViewDistance(player), new CachedChunkTrackingView.Context() {
            @Override
            public void startChunkTracking(ChunkPos pos) {
                markChunkPendingToSend(player, pos);
            }

            @Override
            public void stopChunkTracking(ChunkPos pos) {
                dropChunk(player, pos);
            }

            @Override
            public void putTicket(ChunkPos pos, int ticks) {
                var ticketType = nebDccTicket.get();
                if (ticketType == null || ticketType.timeout() != ticks) {
                    var newType = new TicketType(ticks, TicketType.FLAG_LOADING);
                    nebDccTicket.compareAndSet(ticketType, newType);
                    ticketType = nebDccTicket.get();
                }
                ticketStorage.addTicketWithRadius(ticketType, pos, 1);
            }
        });
    }
}
