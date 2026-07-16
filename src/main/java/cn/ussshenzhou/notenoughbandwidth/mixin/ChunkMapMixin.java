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
//#if MC<12002
//$$ import org.spongepowered.asm.mixin.injection.At;
//$$ import org.spongepowered.asm.mixin.injection.Inject;
//$$ import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

    //#if MC>=12002
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
    //#else
    //$$ @Shadow
    //$$ int viewDistance;
    //$$
    //$$ /**
    //$$  * 1.20.1 has no ChunkTrackingView/updateChunkTracking. Vanilla's own
    //$$  * move()/sendWatchPackets handles chunk data delivery — DCC just keeps
    //$$  * recently-left chunks loaded a bit longer via region tickets so a
    //$$  * returning player gets them resent instantly.
    //$$  */
    //$$ @Inject(method = "move", at = @At("HEAD"))
    //$$ private void nebDccMove(ServerPlayer player, CallbackInfo ci) {
    //$$     if (player.level() != this.level) {
    //$$         return;
    //$$     }
    //$$     CachedChunkTrackingView.onUpdateChunkTracking(player, viewDistance, (pos, ticks) -> {
    //$$         var ticketType = nebDccTicket.get();
    //$$         if (ticketType == null || ticketType.timeout() != ticks) {
    //$$             var newType = TicketType.<ChunkPos>create("neb_dcc",
    //$$                     java.util.Comparator.comparingLong(ChunkPos::toLong), ticks);
    //$$             nebDccTicket.compareAndSet(ticketType, newType);
    //$$             ticketType = nebDccTicket.get();
    //$$         }
    //$$         // radius 0 => ticket level 33 (border-full), enough to keep the chunk sendable
    //$$         level.getChunkSource().addRegionTicket(ticketType, pos, 0, pos);
    //$$     });
    //$$ }
    //$$
    //$$ /** Clean up per-player DCC state when a player is removed. */
    //$$ @Inject(method = "updatePlayerStatus", at = @At("HEAD"))
    //$$ private void nebDccPlayerRemoved(ServerPlayer player, boolean added, CallbackInfo ci) {
    //$$     if (!added) {
    //$$         CachedChunkTrackingView.removePlayer(player);
    //$$     }
    //$$ }
    //#endif
}
