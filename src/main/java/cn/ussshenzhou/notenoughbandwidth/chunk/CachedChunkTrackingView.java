package cn.ussshenzhou.notenoughbandwidth.chunk;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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
                    context.putTicket(chunkPos, chunkCacheTimeout * 20);
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
