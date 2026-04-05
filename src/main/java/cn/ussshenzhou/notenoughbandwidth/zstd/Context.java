package cn.ussshenzhou.notenoughbandwidth.zstd;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.nio.ByteBuffer;

public class Context implements Closeable {
    private final ZstdCompressCtx compressCtx;
    private final ZstdDecompressCtx decompressCtx;

    public Context(@Nullable byte[] dict) {
        compressCtx = new ZstdCompressCtx();
        compressCtx.setLevel(NotEnoughBandwidthConfig.get().getCompressionLevel());
        compressCtx.setContentSize(false);
        compressCtx.setMagicless(true);
        compressCtx.setWindowLog(NotEnoughBandwidthConfig.get().getContextLevel());
        if (dict != null) {
            compressCtx.loadDict(dict);
        }
        decompressCtx = new ZstdDecompressCtx();
        decompressCtx.setMagicless(true);
        if (dict != null) {
            decompressCtx.loadDict(dict);
        }
    }

    public ByteBuffer compress(ByteBuffer raw) {
        return compressCtx.compress(raw);
    }

    public ByteBuffer decompress(ByteBuffer compressed, int originalSize) {
        return decompressCtx.decompress(compressed, originalSize);
    }

    @Override
    public void close() {
        compressCtx.close();
        decompressCtx.close();
    }
}
