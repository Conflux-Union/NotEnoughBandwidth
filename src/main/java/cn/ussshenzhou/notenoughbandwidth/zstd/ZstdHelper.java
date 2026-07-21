package cn.ussshenzhou.notenoughbandwidth.zstd;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutionException;

public class ZstdHelper {

    private static final Cache<Connection, Context> ZSTD_CONTEXT_CACHE = CacheBuilder.newBuilder()
            .weakKeys()
            .removalListener((RemovalListener<Connection, Context>) notification -> {
                if (notification.getValue() != null) {
                    notification.getValue().close();
                }
            })
            .build();

    /**
     * Dict + useContext snapshot taken once at server JOIN and pinned here so the
     * Context lazily created on first compress() always agrees with what was already
     * sent to the client in DictionarySyncPayload — fixes the dictionary-handoff race
     * where a dict trained between JOIN and first compress would silently diverge.
     */
    private record ConnectionSettings(@Nullable byte[] dict, boolean useContext) {
    }

    private static final Cache<Connection, ConnectionSettings> CONNECTION_SETTINGS_CACHE = CacheBuilder.newBuilder()
            .weakKeys()
            .build();

    public static ByteBuf compress(Connection connection, ByteBuf raw) {
        // zstd-jni's ByteBuffer entry points require direct buffers; the netty
        // default allocator is usually direct, but not guaranteed on every platform.
        if (raw.isDirect()) {
            return Unpooled.wrappedBuffer(get(connection).compress(raw.nioBuffer()));
        }
        var directBuf = Unpooled.directBuffer(raw.readableBytes());
        try {
            raw.getBytes(raw.readerIndex(), directBuf);
            return Unpooled.wrappedBuffer(get(connection).compress(directBuf.nioBuffer()));
        } finally {
            directBuf.release();
        }
    }

    public static ByteBuf decompress(Connection connection, ByteBuf compressed, int originalSize) {
        try {
            if (compressed.isDirect()) {
                return Unpooled.wrappedBuffer(get(connection).decompress(compressed.nioBuffer(), originalSize));
            } else {
                var directBuf = Unpooled.directBuffer(compressed.readableBytes());
                try {
                    compressed.getBytes(compressed.readerIndex(), directBuf);
                    return Unpooled.wrappedBuffer(get(connection).decompress(directBuf.nioBuffer(), originalSize));
                } finally {
                    directBuf.release();
                }
            }
        } finally {
            compressed.release();
        }
    }

    /**
     * Pin the dict/useContext snapshot for a connection, taken at server JOIN time
     * from the SAME dict byte[] written into DictionarySyncPayload. Must be called
     * before the connection's first compress() so the lazily-created Context below
     * never disagrees with what the client received.
     */
    public static void pin(Connection connection, @Nullable byte[] dict, boolean useContext) {
        CONNECTION_SETTINGS_CACHE.put(connection, new ConnectionSettings(dict, useContext));
    }

    private static Context get(Connection connection) {
        // Dead connections otherwise pin a native zstd context (~8MB at windowLog 23)
        // until GC runs the weakKeys removal listener; sweep eagerly like upstream does.
        ZSTD_CONTEXT_CACHE.asMap().entrySet().removeIf(e -> !e.getKey().isConnected());
        CONNECTION_SETTINGS_CACHE.asMap().entrySet().removeIf(e -> !e.getKey().isConnected());
        try {
            return ZSTD_CONTEXT_CACHE.get(connection, () -> {
                var pinned = CONNECTION_SETTINGS_CACHE.getIfPresent(connection);
                if (pinned != null) {
                    return new Context(pinned.dict(), pinned.useContext());
                }
                // No pin: this is the client's own outbound connection (server never
                // pins it), or compression happened before JOIN wiring ran. Fall back
                // to the live dict with context reuse enabled.
                return new Context(DictionaryManager.getDict(), true);
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Evict the cached Zstd context and pinned settings for a connection so the next
     * call recreates it with the current dictionary. Required on proxy server switches
     * where the same Connection is reused with a different backend — the new backend's
     * JOIN re-pins before any compression happens — and on disconnect to release the
     * native context promptly instead of waiting on GC.
     */
    public static void evict(Connection connection) {
        ZSTD_CONTEXT_CACHE.invalidate(connection);
        CONNECTION_SETTINGS_CACHE.invalidate(connection);
    }
}
