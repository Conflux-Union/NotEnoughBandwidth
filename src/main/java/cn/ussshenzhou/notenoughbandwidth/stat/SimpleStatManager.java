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

    public static long inboundBytesBakedServer;
    public static long inboundBytesRawServer;
    public static long outboundBytesBakedServer;
    public static long outboundBytesRawServer;
    public static double inboundSpeedBakedServer;
    public static double inboundSpeedRawServer;
    public static double outboundSpeedBakedServer;
    public static double outboundSpeedRawServer;
    public static int dictSizeServer;
    public static int dictSampleCountServer;
    public static int dictSampleThresholdServer;

    // Server-side chunk cache counters (written by ChunkDataSenderMixin on the server).
    public static final AtomicLong chunkCacheHits = new AtomicLong();
    public static final AtomicLong chunkCacheMisses = new AtomicLong();
    public static final AtomicLong chunkCacheSavedBytes = new AtomicLong();

    // Client-side display copies received via StatRespondPayload.
    public static long chunkCacheHitsServer;
    public static long chunkCacheMissesServer;
    public static long chunkCacheSavedBytesServer;
}
