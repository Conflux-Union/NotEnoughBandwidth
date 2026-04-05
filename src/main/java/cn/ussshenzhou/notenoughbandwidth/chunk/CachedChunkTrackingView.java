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
import org.intellij.lang.annotations.MagicConstant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * DCC (Delayed Chunk Cache) tracking for 1.20.1.
 * Since ChunkFilter does not exist in 1.20.1, this uses an external
 * per-player cache of recently-left chunks with ticket-based retention.
 */
public class CachedChunkTrackingView {
    private static final long NO_CACHE = -1;
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-DCC");

    private static final ConcurrentHashMap<ServerPlayerEntity, CachedChunkTrackingView> PLAYER_VIEWS = new ConcurrentHashMap<>();

    private ChunkSectionPos lastSection;
    private int lastViewDistance;
    private final Long2LongLinkedOpenHashMap cache = new Long2LongLinkedOpenHashMap();

    public CachedChunkTrackingView() {
        cache.defaultReturnValue(NO_CACHE);
    }

    public interface Context {
        void startChunkTracking(ChunkPos pos);
        void stopChunkTracking(ChunkPos pos);
        void putTicket(ChunkPos pos, int ticks);
    }

    public static void onUpdateChunkTracking(ServerPlayerEntity player, int viewDistance, Context context) {
        ChunkSectionPos currentSection = player.getWatchedSection();
        ChunkPos playerChunkPos = currentSection.toChunkPos();

        var view = PLAYER_VIEWS.computeIfAbsent(player, k -> new CachedChunkTrackingView());
        view.tick(player, playerChunkPos, viewDistance, context);
    }

    public static void removePlayer(ServerPlayerEntity player) {
        PLAYER_VIEWS.remove(player);
    }

    private void tick(ServerPlayerEntity player, ChunkPos playerChunkPos, int viewDistance, Context context) {
        long now = System.currentTimeMillis();
        var cfg = ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class);
        int chunkCacheBufferSize = cfg.dccSizeLimit;
        int chunkCacheDistance = cfg.dccDistance;
        int chunkCacheTimeout = cfg.dccTimeout;
        long chunkCacheTimeoutMilli = TimeUnit.SECONDS.toMillis(chunkCacheTimeout);

        boolean moved = lastSection == null
                || !lastSection.toChunkPos().equals(playerChunkPos)
                || lastViewDistance != viewDistance;

        if (moved && lastSection != null) {
            ChunkPos oldCenter = lastSection.toChunkPos();
            // Chunks that were in old range but not in new range: add to DCC cache
            for (int x = oldCenter.x - lastViewDistance; x <= oldCenter.x + lastViewDistance; x++) {
                for (int z = oldCenter.z - lastViewDistance; z <= oldCenter.z + lastViewDistance; z++) {
                    if (!isInRange(x, z, playerChunkPos, viewDistance)) {
                        ChunkPos pos = new ChunkPos(x, z);
                        if (playerChunkPos.getChebyshevDistance(pos) <= chunkCacheDistance + viewDistance) {
                            context.putTicket(pos, chunkCacheTimeout * 20);
                            cache.put(pos.toLong(), now);
                        }
                    }
                }
            }
            // Chunks re-entering range: remove from cache
            for (int x = playerChunkPos.x - viewDistance; x <= playerChunkPos.x + viewDistance; x++) {
                for (int z = playerChunkPos.z - viewDistance; z <= playerChunkPos.z + viewDistance; z++) {
                    cache.remove(ChunkPos.toLong(x, z));
                }
            }
        }

        // Evict expired or overflow entries
        enumerate((pos, time) -> {
            boolean legacy = time <= now - chunkCacheTimeoutMilli;
            if (legacy || cache.size() >= chunkCacheBufferSize) {
                LOGGER.trace("Evict {} from {}'s DCC cache: {}",
                        new ChunkPos(pos), player.getName().getString(),
                        legacy ? "timeout" : "buffer full");
                return CacheConsumer.REMOVE;
            } else {
                return CacheConsumer.STOP;
            }
        });

        lastSection = player.getWatchedSection();
        lastViewDistance = viewDistance;
    }

    private static boolean isInRange(int x, int z, ChunkPos center, int viewDistance) {
        return Math.abs(x - center.x) <= viewDistance && Math.abs(z - center.z) <= viewDistance;
    }

    @FunctionalInterface
    private interface CacheConsumer {
        byte CONTINUE = 0, REMOVE = 1, STOP = 2;

        @MagicConstant(flags = {CONTINUE, REMOVE, STOP})
        byte accept(long pos, long time);
    }

    private void enumerate(CacheConsumer consumer) {
        ObjectIterator<Long2LongMap.Entry> iterator = Long2LongMaps.fastIterator(cache);
        while (iterator.hasNext()) {
            Long2LongMap.Entry entry = iterator.next();
            byte v = consumer.accept(entry.getLongKey(), entry.getLongValue());
            if ((v & CacheConsumer.REMOVE) != 0) iterator.remove();
            if ((v & CacheConsumer.STOP) != 0) return;
        }
    }
}
