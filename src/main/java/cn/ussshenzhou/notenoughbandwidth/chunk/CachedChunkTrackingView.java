package cn.ussshenzhou.notenoughbandwidth.chunk;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * DCC (Delayed Chunk Cache) tracking for 1.20.1.
 * <p>
 * Keeps recently-left chunks loaded via chunk tickets so that if the player
 * reverses direction, vanilla's sendWatchPackets finds the chunk already loaded
 * and resends it immediately — no regeneration or disk I/O needed.
 * <p>
 * No ChunkFilter/ChunkTrackingView exists in 1.20.1, so we track
 * per-player old/new chunk positions externally and compute the diff ourselves.
 */
public class CachedChunkTrackingView {
    private static final long NO_CACHE = -1;
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-DCC");

    private static final ConcurrentHashMap<ServerPlayerEntity, CachedChunkTrackingView> PLAYER_VIEWS = new ConcurrentHashMap<>();

    private ChunkPos lastCenter;
    private int lastViewDistance;
    private final Long2LongLinkedOpenHashMap cache = new Long2LongLinkedOpenHashMap();

    public CachedChunkTrackingView() {
        cache.defaultReturnValue(NO_CACHE);
    }

    @FunctionalInterface
    public interface TicketPlacer {
        void putTicket(ChunkPos pos, int ticks);
    }

    public static void onUpdateChunkTracking(ServerPlayerEntity player, int viewDistance, TicketPlacer ticketPlacer) {
        ChunkSectionPos currentSection = player.getWatchedSection();
        ChunkPos playerChunkPos = currentSection.toChunkPos();

        CachedChunkTrackingView view = PLAYER_VIEWS.computeIfAbsent(player, k -> new CachedChunkTrackingView());
        view.tick(player, playerChunkPos, viewDistance, ticketPlacer);
    }

    public static void removePlayer(ServerPlayerEntity player) {
        PLAYER_VIEWS.remove(player);
    }

    private void tick(ServerPlayerEntity player, ChunkPos center, int viewDistance, TicketPlacer ticketPlacer) {
        long now = System.currentTimeMillis();
        NotEnoughBandwidthConfig cfg = ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class);
        int sizeLimit = cfg.dccSizeLimit;
        int dccDistance = cfg.dccDistance;
        int dccTimeoutTicks = cfg.dccTimeout * 20;
        long timeoutMillis = TimeUnit.SECONDS.toMillis(cfg.dccTimeout);

        boolean moved = lastCenter == null
                || !lastCenter.equals(center)
                || lastViewDistance != viewDistance;

        if (moved && lastCenter != null) {
            // Identify chunks that left the view range and add DCC tickets.
            int oldMinX = lastCenter.x - lastViewDistance;
            int oldMaxX = lastCenter.x + lastViewDistance;
            int oldMinZ = lastCenter.z - lastViewDistance;
            int oldMaxZ = lastCenter.z + lastViewDistance;

            for (int x = oldMinX; x <= oldMaxX; x++) {
                for (int z = oldMinZ; z <= oldMaxZ; z++) {
                    if (isInRange(x, z, center, viewDistance)) {
                        continue;
                    }
                    // This chunk left the player's view. Keep it loaded if close enough.
                    ChunkPos pos = new ChunkPos(x, z);
                    if (center.getChebyshevDistance(pos) <= viewDistance + dccDistance) {
                        ticketPlacer.putTicket(pos, dccTimeoutTicks);
                        cache.put(pos.toLong(), now);
                    }
                }
            }

            // Chunks that re-entered the view range: remove from DCC cache.
            int newMinX = center.x - viewDistance;
            int newMaxX = center.x + viewDistance;
            int newMinZ = center.z - viewDistance;
            int newMaxZ = center.z + viewDistance;

            for (int x = newMinX; x <= newMaxX; x++) {
                for (int z = newMinZ; z <= newMaxZ; z++) {
                    cache.remove(ChunkPos.toLong(x, z));
                }
            }
        }

        // Evict expired or overflow entries (oldest first, thanks to insertion order).
        evict(now, timeoutMillis, sizeLimit);

        lastCenter = center;
        lastViewDistance = viewDistance;
    }

    private static boolean isInRange(int x, int z, ChunkPos center, int viewDistance) {
        return Math.abs(x - center.x) <= viewDistance && Math.abs(z - center.z) <= viewDistance;
    }

    private void evict(long now, long timeoutMillis, int sizeLimit) {
        ObjectIterator<Long2LongMap.Entry> it = Long2LongMaps.fastIterator(cache);
        while (it.hasNext()) {
            Long2LongMap.Entry entry = it.next();
            boolean expired = entry.getLongValue() <= now - timeoutMillis;
            if (expired || cache.size() > sizeLimit) {
                it.remove();
            } else {
                // Oldest entries are first; if this one isn't expired, none after it are.
                break;
            }
        }
    }
}
