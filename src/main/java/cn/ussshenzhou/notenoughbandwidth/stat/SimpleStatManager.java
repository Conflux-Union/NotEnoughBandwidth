package cn.ussshenzhou.notenoughbandwidth.stat;

import java.util.concurrent.atomic.AtomicLong;

public class SimpleStatManager {
    public static final SimpleStatData LOCAL = new SimpleStatData();

    public static void inBaked(int size) {
        LOCAL.inboundBytesBaked().addAndGet(size);
        LOCAL.inboundSpeedBaked().put(size);
    }

    public static void inRaw(int size) {
        LOCAL.inboundBytesRaw().addAndGet(size);
        LOCAL.inboundSpeedRaw().put(size);
    }

    public static void outBaked(int size) {
        LOCAL.outboundBytesBaked().addAndGet(size);
        LOCAL.outboundSpeedBaked().put(size);
    }

    public static void outRaw(int size) {
        LOCAL.outboundBytesRaw().addAndGet(size);
        LOCAL.outboundSpeedRaw().put(size);
    }

    public static volatile long inboundBytesBakedServer;
    public static volatile long inboundBytesRawServer;
    public static volatile long outboundBytesBakedServer;
    public static volatile long outboundBytesRawServer;
    public static volatile double inboundSpeedBakedServer;
    public static volatile double inboundSpeedRawServer;
    public static volatile double outboundSpeedBakedServer;
    public static volatile double outboundSpeedRawServer;
    public static volatile int dictSizeServer;
    public static volatile int dictSampleCountServer;
    public static volatile int dictSampleThresholdServer;

    // Server-side chunk cache counters (written by ChunkDataSenderMixin on the server).
    public static final AtomicLong chunkCacheHits = new AtomicLong();
    public static final AtomicLong chunkCacheMisses = new AtomicLong();
    public static final AtomicLong chunkCacheSavedBytes = new AtomicLong();

    // Client-side display copies received via StatRespondPayload.
    public static volatile long chunkCacheHitsServer;
    public static volatile long chunkCacheMissesServer;
    public static volatile long chunkCacheSavedBytesServer;
}
