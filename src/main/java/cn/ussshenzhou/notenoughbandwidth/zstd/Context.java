package cn.ussshenzhou.notenoughbandwidth.zstd;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import com.github.luben.zstd.EndDirective;
import com.github.luben.zstd.Zstd;
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
        //#if MC>=12005
        int maxDstSize = (int) Zstd.compressBound(raw.remaining());
        var dst = ByteBuffer.allocateDirect(maxDstSize);
        compressCtx.compressDirectByteBufferStream(dst, raw, EndDirective.FLUSH);
        dst.flip();
        return dst;
        //#else
        //$$ // Stateless per-blob compression: AggregationManager.sendBatched() may
        //$$ // serialize a payload, discard it (over the 1.20.1 size limit) and
        //$$ // re-compress two halves — a streaming context would corrupt its shared
        //$$ // stream state on the discarded attempt.
        //$$ return compressCtx.compress(raw);
        //#endif
    }

    public ByteBuffer decompress(ByteBuffer compressed, int originalSize) {
        //#if MC>=12005
        var dst = ByteBuffer.allocateDirect(originalSize);
        decompressCtx.decompressDirectByteBufferStream(dst, compressed);
        dst.flip();
        return dst;
        //#else
        //$$ return decompressCtx.decompress(compressed, originalSize);
        //#endif
    }

    @Override
    public void close() {
        compressCtx.close();
        decompressCtx.close();
    }
}
