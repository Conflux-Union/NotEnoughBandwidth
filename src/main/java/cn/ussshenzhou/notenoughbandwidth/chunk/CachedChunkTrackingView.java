package cn.ussshenzhou.notenoughbandwidth.chunk;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
//#if MC>=12002
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ChunkTrackingView;
import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
//#else
//$$ import net.minecraft.core.SectionPos;
//$$
//$$ import java.util.concurrent.ConcurrentHashMap;
//#endif
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//#if MC>=12002
import java.util.Objects;
import java.util.function.Consumer;
//#endif
import java.util.concurrent.TimeUnit;

//#if MC>=12002
public class CachedChunkTrackingView implements ChunkTrackingView {
    private static final long NO_CACHE = -1;
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-DCC");

    private ChunkTrackingView.Positioned major;
    private final Long2LongLinkedOpenHashMap cache = new Long2LongLinkedOpenHashMap();

    public CachedChunkTrackingView(ChunkTrackingView.Positioned major) {
        this.major = major;
        cache.defaultReturnValue(NO_CACHE);
    }

    @Override
    public boolean contains(int x, int z, boolean includeEdge) {
        return major.contains(x, z, includeEdge) || cache.containsKey(ChunkPos.pack(x, z));
    }

    @Override
    public void forEach(@NotNull Consumer<ChunkPos> consumer) {
        major.forEach(consumer);
        LongIterator it = cache.keySet().iterator();
        while (it.hasNext()) {
            consumer.accept(ChunkPos.unpack(it.nextLong()));
        }
    }

    public interface Context {
        void startChunkTracking(ChunkPos pos);
        void stopChunkTracking(ChunkPos pos);
        void putTicket(ChunkPos pos, int ticks);
    }

    public static void onUpdateChunkTracking(ServerPlayer player, int playerViewDistance, Context context) {
        ChunkTrackingView currentView = player.getChunkTrackingView();
        ChunkPos playerChunkPos = player.chunkPosition();

        ChunkTrackingView.Positioned nextPositioned = null;
        ChunkTrackingView.Positioned lastPositioned = currentView instanceof CachedChunkTrackingView cached
                ? cached.major
                : (currentView instanceof ChunkTrackingView.Positioned p ? p : null);

        if (lastPositioned == null
                || !lastPositioned.center().equals(playerChunkPos)
                || lastPositioned.viewDistance() != playerViewDistance) {
            nextPositioned = new ChunkTrackingView.Positioned(playerChunkPos, playerViewDistance);
            player.connection.send(
                    new ClientboundSetChunkCacheCenterPacket(playerChunkPos.x(), playerChunkPos.z()));
        }

        if (currentView instanceof CachedChunkTrackingView cachedView) {
            cachedView.tick(player, Objects.requireNonNullElse(nextPositioned, cachedView.major), context);
        } else if (nextPositioned != null) {
            CachedChunkTrackingView cachedView = new CachedChunkTrackingView(nextPositioned);
            ChunkTrackingView.difference(currentView, cachedView,
                    context::startChunkTracking, context::stopChunkTracking);
            player.setChunkTrackingView(cachedView);
        }
    }

    private void tick(ServerPlayer player, ChunkTrackingView.Positioned next, Context context) {
        long now = System.currentTimeMillis();
        var cfg = ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class);
        int chunkCacheBufferSize = cfg.dccSizeLimit;
        int chunkCacheDistance = cfg.dccDistance;
        int chunkCacheTimeout = cfg.dccTimeout;
        long chunkCacheTimeoutMilli = TimeUnit.SECONDS.toMillis(chunkCacheTimeout);

        if (!major.equals(next)) {
            ChunkTrackingView.difference(major, next, chunkPos -> {
                if (cache.remove(ChunkPos.pack(chunkPos.x(), chunkPos.z())) == NO_CACHE) {
                    context.startChunkTracking(chunkPos);
                    LOGGER.trace("Cache miss at {} for {}", chunkPos, player.getName().getString());
                } else {
                    LOGGER.trace("Cache hit at {} for {}", chunkPos, player.getName().getString());
                }
            }, chunkPos -> {
                if (next.center().getChessboardDistance(chunkPos) <= chunkCacheDistance) {
                    // Anchor the ticket at the player, not the departed chunk: TicketStorage
                    // dedupes/refreshes same-type tickets at the same pos, so this collapses
                    // up to dccSizeLimit per-chunk tickets into one refreshed radius-1 ticket
                    // that follows the player, instead of pinning every departed chunk's 3x3
                    // area individually.
                    context.putTicket(player.chunkPosition(), chunkCacheTimeout * 20);
                    cache.put(ChunkPos.pack(chunkPos.x(), chunkPos.z()), now);
                }
            });

            enumerate((pos, unused) -> {
                ChunkPos cp = ChunkPos.unpack(pos);
                if (next.center().getChessboardDistance(cp) > chunkCacheDistance) {
                    context.stopChunkTracking(cp);
                    LOGGER.trace("Evict {} from {}'s cache: too far", cp, player.getName().getString());
                    return CacheConsumer.REMOVE;
                }
                return CacheConsumer.CONTINUE;
            });
        }

        enumerate((pos, time) -> {
            boolean legacy = time <= now - chunkCacheTimeoutMilli;
            if (legacy || cache.size() >= chunkCacheBufferSize) {
                ChunkPos cp = ChunkPos.unpack(pos);
                context.stopChunkTracking(cp);
                LOGGER.trace("Evict {} from {}'s cache: {}", cp, player.getName().getString(),
                        legacy ? "timeout" : "buffer full");
                return CacheConsumer.REMOVE;
            } else {
                return CacheConsumer.STOP;
            }
        });

        major = next;
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
//#else
//$$ /**
//$$  * DCC (Delayed Chunk Cache) tracking for 1.20.1.
//$$  * <p>
//$$  * Keeps recently-left chunks loaded via chunk tickets so that if the player
//$$  * reverses direction, vanilla's sendWatchPackets finds the chunk already loaded
//$$  * and resends it immediately — no regeneration or disk I/O needed.
//$$  * <p>
//$$  * No ChunkTrackingView exists in 1.20.1, so per-player old/new chunk positions
//$$  * are tracked externally and the diff is computed manually.
//$$  */
//$$ public class CachedChunkTrackingView {
//$$     private static final long NO_CACHE = -1;
//$$     private static final Logger LOGGER = LoggerFactory.getLogger("NEB-DCC");
//$$
//$$     private static final ConcurrentHashMap<ServerPlayer, CachedChunkTrackingView> PLAYER_VIEWS = new ConcurrentHashMap<>();
//$$
//$$     private ChunkPos lastCenter;
//$$     private int lastViewDistance;
//$$     private final Long2LongLinkedOpenHashMap cache = new Long2LongLinkedOpenHashMap();
//$$
//$$     public CachedChunkTrackingView() {
//$$         cache.defaultReturnValue(NO_CACHE);
//$$     }
//$$
//$$     @FunctionalInterface
//$$     public interface TicketPlacer {
//$$         void putTicket(ChunkPos pos, int ticks);
//$$     }
//$$
//$$     public static void onUpdateChunkTracking(ServerPlayer player, int viewDistance, TicketPlacer ticketPlacer) {
//$$         SectionPos currentSection = player.getLastSectionPos();
//$$         ChunkPos playerChunkPos = currentSection.chunk();
//$$
//$$         CachedChunkTrackingView view = PLAYER_VIEWS.computeIfAbsent(player, k -> new CachedChunkTrackingView());
//$$         view.tick(playerChunkPos, viewDistance, ticketPlacer);
//$$     }
//$$
//$$     public static void removePlayer(ServerPlayer player) {
//$$         PLAYER_VIEWS.remove(player);
//$$     }
//$$
//$$     private void tick(ChunkPos center, int viewDistance, TicketPlacer ticketPlacer) {
//$$         long now = System.currentTimeMillis();
//$$         NotEnoughBandwidthConfig cfg = ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class);
//$$         int sizeLimit = cfg.dccSizeLimit;
//$$         int dccDistance = cfg.dccDistance;
//$$         int dccTimeoutTicks = cfg.dccTimeout * 20;
//$$         long timeoutMillis = TimeUnit.SECONDS.toMillis(cfg.dccTimeout);
//$$
//$$         boolean moved = lastCenter == null
//$$                 || !lastCenter.equals(center)
//$$                 || lastViewDistance != viewDistance;
//$$
//$$         if (moved && lastCenter != null) {
//$$             // Chunks that left the view range: keep them loaded via DCC tickets.
//$$             int oldMinX = lastCenter.x - lastViewDistance;
//$$             int oldMaxX = lastCenter.x + lastViewDistance;
//$$             int oldMinZ = lastCenter.z - lastViewDistance;
//$$             int oldMaxZ = lastCenter.z + lastViewDistance;
//$$
//$$             for (int x = oldMinX; x <= oldMaxX; x++) {
//$$                 for (int z = oldMinZ; z <= oldMaxZ; z++) {
//$$                     if (isInRange(x, z, center, viewDistance)) {
//$$                         continue;
//$$                     }
//$$                     ChunkPos pos = new ChunkPos(x, z);
//$$                     if (center.getChessboardDistance(pos) <= viewDistance + dccDistance) {
//$$                         ticketPlacer.putTicket(pos, dccTimeoutTicks);
//$$                         cache.put(pos.toLong(), now);
//$$                     }
//$$                 }
//$$             }
//$$
//$$             // Chunks that re-entered the view range: drop them from the DCC cache.
//$$             int newMinX = center.x - viewDistance;
//$$             int newMaxX = center.x + viewDistance;
//$$             int newMinZ = center.z - viewDistance;
//$$             int newMaxZ = center.z + viewDistance;
//$$
//$$             for (int x = newMinX; x <= newMaxX; x++) {
//$$                 for (int z = newMinZ; z <= newMaxZ; z++) {
//$$                     cache.remove(ChunkPos.asLong(x, z));
//$$                 }
//$$             }
//$$         }
//$$
//$$         // Evict expired or overflow entries (oldest first, thanks to insertion order).
//$$         evict(now, timeoutMillis, sizeLimit);
//$$
//$$         lastCenter = center;
//$$         lastViewDistance = viewDistance;
//$$     }
//$$
//$$     private static boolean isInRange(int x, int z, ChunkPos center, int viewDistance) {
//$$         return Math.abs(x - center.x) <= viewDistance && Math.abs(z - center.z) <= viewDistance;
//$$     }
//$$
//$$     private void evict(long now, long timeoutMillis, int sizeLimit) {
//$$         ObjectIterator<Long2LongMap.Entry> it = Long2LongMaps.fastIterator(cache);
//$$         while (it.hasNext()) {
//$$             Long2LongMap.Entry entry = it.next();
//$$             boolean expired = entry.getLongValue() <= now - timeoutMillis;
//$$             if (expired || cache.size() > sizeLimit) {
//$$                 it.remove();
//$$             } else {
//$$                 // Oldest entries are first; if this one isn't expired, none after it are.
//$$                 break;
//$$             }
//$$         }
//$$     }
//$$ }
//#endif
