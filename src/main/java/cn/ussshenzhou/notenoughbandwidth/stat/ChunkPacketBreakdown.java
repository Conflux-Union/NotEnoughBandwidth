package cn.ussshenzhou.notenoughbandwidth.stat;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Sub-probe for {@code minecraft:level_chunk_with_light}: separately measures
 * the pre-compression wire bytes written by {@code ChunkData.write} (terrain +
 * heightmaps + block entities) and {@code LightData.write} (sky + block light
 * nibble layers + masks). Together with the total chunk-packet raw bytes
 * recorded by {@link PacketTypeStatManager}, this lets us compute what share
 * of the chunk packet is light data — the target of P0-1.
 */
public final class ChunkPacketBreakdown {

    private ChunkPacketBreakdown() {}

    public static final AtomicLong chunkDataBytes = new AtomicLong();
    public static final AtomicLong chunkDataCalls = new AtomicLong();
    public static final AtomicLong lightDataBytes = new AtomicLong();
    public static final AtomicLong lightDataCalls = new AtomicLong();

    public static void recordChunkData(int bytes) {
        if (bytes <= 0) return;
        chunkDataBytes.addAndGet(bytes);
        chunkDataCalls.incrementAndGet();
    }

    public static void recordLightData(int bytes) {
        if (bytes <= 0) return;
        lightDataBytes.addAndGet(bytes);
        lightDataCalls.incrementAndGet();
    }

    public static void reset() {
        chunkDataBytes.set(0);
        chunkDataCalls.set(0);
        lightDataBytes.set(0);
        lightDataCalls.set(0);
    }
}
