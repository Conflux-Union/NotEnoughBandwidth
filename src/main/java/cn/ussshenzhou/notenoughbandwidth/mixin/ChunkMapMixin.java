package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.chunk.CachedChunkTrackingView;
import net.minecraft.server.network.ChunkFilter;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.*;

import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;

@Mixin(ServerChunkLoadingManager.class)
public abstract class ChunkMapMixin {

    @Shadow
    @Final
    ServerWorld world;

    @Unique
    private static final AtomicReference<ChunkTicketType<ChunkPos>> nebDccTicket = new AtomicReference<>();

    @Shadow
    int getViewDistance(ServerPlayerEntity player) { throw new AssertionError(); }

    @Shadow
    private void track(ServerPlayerEntity player, ChunkPos pos) {}

    @Shadow
    private static void untrack(ServerPlayerEntity player, ChunkPos pos) {}

    @Shadow
    public abstract net.minecraft.server.world.ChunkTicketManager getTicketManager();

    /**
     * @author NEB
     * @reason Replace vanilla chunk tracking with DCC-aware version
     */
    @Overwrite
    private void sendWatchPackets(ServerPlayerEntity player) {
        if (player.getWorld() != this.world) {
            return;
        }
        var ticketManager = getTicketManager();
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
                var ticketType = nebDccTicket.get();
                if (ticketType == null || ticketType.getExpiryTicks() != ticks) {
                    var newType = ChunkTicketType.<ChunkPos>create("neb_dcc",
                            Comparator.comparingLong(ChunkPos::toLong), ticks);
                    nebDccTicket.compareAndSet(ticketType, newType);
                    ticketType = nebDccTicket.get();
                }
                ticketManager.addTicket(ticketType, pos, 1, pos);
            }
        });
    }
}
