package cn.ussshenzhou.notenoughbandwidth.zstd;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;

import java.io.Closeable;
import java.nio.ByteBuffer;

public class Context implements Closeable {
    private final ZstdCompressCtx compressCtx;
    private final ZstdDecompressCtx decompressCtx;

    public Context() {
        compressCtx = new ZstdCompressCtx();
        compressCtx.setLevel(NotEnoughBandwidthConfig.get().getCompressionLevel());
        compressCtx.setContentSize(false);
        compressCtx.setMagicless(true);
        compressCtx.setWindowLog(NotEnoughBandwidthConfig.get().getContextLevel());
        decompressCtx = new ZstdDecompressCtx();
        decompressCtx.setMagicless(true);
    }

    public ByteBuffer compress(ByteBuffer raw) {
        int maxDstSize = (int) Zstd.compressBound(raw.remaining());
        var dst = ByteBuffer.allocateDirect(maxDstSize);
        compressCtx.compressDirectByteBufferStream(dst, raw, EndDirective.FLUSH);
        dst.flip();
        return dst;
    }

    public ByteBuffer decompress(ByteBuffer compressed, int originalSize) {
        var dst = ByteBuffer.allocateDirect(originalSize);
        decompressCtx.decompressDirectByteBufferStream(dst, compressed);
        dst.flip();
        return dst;
    }

    @Override
    public void close() {
        compressCtx.close();
        decompressCtx.close();
    }
}
