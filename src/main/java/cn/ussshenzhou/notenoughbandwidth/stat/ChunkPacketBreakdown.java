package cn.ussshenzhou.notenoughbandwidth.stat;

import java.util.concurrent.atomic.AtomicLong;

public final class ChunkPacketBreakdown {
    public static final AtomicLong chunkDataBytes = new AtomicLong();
    public static final AtomicLong lightDataBytes = new AtomicLong();
    public static final AtomicLong chunkDataCalls = new AtomicLong();
    public static final AtomicLong lightDataCalls = new AtomicLong();

    private ChunkPacketBreakdown() {}

    public static void recordChunkData(long bytes) {
        if (bytes < 0) return;
        chunkDataBytes.addAndGet(bytes);
        chunkDataCalls.incrementAndGet();
    }

    public static void recordLightData(long bytes) {
        if (bytes < 0) return;
        lightDataBytes.addAndGet(bytes);
        lightDataCalls.incrementAndGet();
    }

    public static void reset() {
        chunkDataBytes.set(0);
        lightDataBytes.set(0);
        chunkDataCalls.set(0);
        lightDataCalls.set(0);
    }
}
