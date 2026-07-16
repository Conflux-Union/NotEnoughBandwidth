package cn.ussshenzhou.notenoughbandwidth.zstd;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;

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

    private static Context get(Connection connection) {
        try {
            return ZSTD_CONTEXT_CACHE.get(connection, () -> new Context(DictionaryManager.getDict()));
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Evict the cached Zstd context for a connection so the next call recreates
     * it with the current dictionary. Required on proxy server switches where
     * the same Connection is reused with a different backend.
     */
    public static void evict(Connection connection) {
        ZSTD_CONTEXT_CACHE.invalidate(connection);
    }
}
