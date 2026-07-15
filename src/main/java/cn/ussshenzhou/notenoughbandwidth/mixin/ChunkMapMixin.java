package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.chunk.CachedChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
//#if MC>=12106
import net.minecraft.world.level.TicketStorage;
//#endif
import org.spongepowered.asm.mixin.*;

import java.util.concurrent.atomic.AtomicReference;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow
    @Final
    ServerLevel level;

    @Unique
    //#if MC>=12106
    private static final AtomicReference<TicketType> nebDccTicket = new AtomicReference<>();
    //#else
    //$$ private static final AtomicReference<TicketType<ChunkPos>> nebDccTicket = new AtomicReference<>();
    //#endif

    @Shadow
    int getPlayerViewDistance(ServerPlayer player) { throw new AssertionError(); }

    @Shadow
    private void markChunkPendingToSend(ServerPlayer player, ChunkPos pos) {}

    @Shadow
    private static void dropChunk(ServerPlayer player, ChunkPos pos) {}

    //#if MC>=12106
    @Shadow
    @Final
    private TicketStorage ticketStorage;
    //#else
    //$$ @Shadow
    //$$ protected abstract net.minecraft.server.level.DistanceManager getDistanceManager();
    //#endif

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
                    //#if MC>=12110
                    var newType = new TicketType(ticks, TicketType.FLAG_LOADING);
                    //#elseif MC>=12106
                    //$$ var newType = new TicketType(ticks, false, TicketType.TicketUse.LOADING);
                    //#else
                    //$$ var newType = TicketType.<ChunkPos>create("neb_dcc",
                    //$$         java.util.Comparator.comparingLong(ChunkPos::toLong), ticks);
                    //#endif
                    nebDccTicket.compareAndSet(ticketType, newType);
                    ticketType = nebDccTicket.get();
                }
                //#if MC>=12106
                ticketStorage.addTicketWithRadius(ticketType, pos, 1);
                //#else
                //$$ getDistanceManager().addTicket(ticketType, pos, 1, pos);
                //#endif
            }
        });
    }
}
