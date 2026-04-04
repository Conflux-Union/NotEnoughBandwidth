package cn.ussshenzhou.notenoughbandwidth.zstd;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ClientConnection;

import java.util.concurrent.ExecutionException;

public class ZstdHelper {

    private static final Cache<ClientConnection, Context> ZSTD_CONTEXT_CACHE = CacheBuilder.newBuilder()
            .weakKeys()
            .removalListener((RemovalListener<ClientConnection, Context>) notification -> {
                if (notification.getValue() != null) {
                    notification.getValue().close();
                }
            })
            .build();

    public static ByteBuf compress(ClientConnection connection, ByteBuf raw) {
        return Unpooled.wrappedBuffer(get(connection).compress(raw.nioBuffer()));
    }

    public static ByteBuf decompress(ClientConnection connection, ByteBuf compressed, int originalSize) {
        if (compressed.isDirect()) {
            return Unpooled.wrappedBuffer(get(connection).decompress(compressed.nioBuffer(), originalSize));
        } else {
            var directBuf = Unpooled.directBuffer(compressed.readableBytes());
            compressed.getBytes(compressed.readerIndex(), directBuf);
            var decompressed = Unpooled.wrappedBuffer(get(connection).decompress(directBuf.nioBuffer(), originalSize));
            directBuf.release();
            return decompressed;
        }
    }

    private static Context get(ClientConnection connection) {
        try {
            return ZSTD_CONTEXT_CACHE.get(connection, () -> new Context(DictionaryManager.getDict()));
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Evict the cached Zstd context for a connection so the next call recreates
     * it with the current dictionary. Required on proxy server switches where
     * the same ClientConnection is reused with a different backend.
     */
    public static void evict(ClientConnection connection) {
        ZSTD_CONTEXT_CACHE.invalidate(connection);
    }
}
