package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * Sent from client to server during handshake (after NebAck) and periodically
 * when the local chunk cache grows.
 * <p>
 * Carries the serialized Guava BloomFilter.  Because 1.20.1 limits C2S custom
 * payloads to 32 767 bytes, the bloom filter is split into chunks.  Each chunk
 * carries a {@code hasMore} flag; the server accumulates chunks until it
 * receives one with {@code hasMore == false}, then assembles the full filter.
 */
public record ChunkCacheManifestPayload(byte[] bloomFilterBytes, boolean hasMore) {
    public static final Identifier CHANNEL = new Identifier("neb", "chunk_cache_manifest");

    private static final int MAX_BLOOM_FILTER_SIZE = 2 * 1024 * 1024; // 2MB max

    /** Max bytes per chunk — leaves room for varint + boolean + channel overhead. */
    public static final int MAX_CHUNK_SIZE = 30_000;

    public void write(PacketByteBuf buf) {
        buf.writeBoolean(hasMore);
        if (bloomFilterBytes != null && bloomFilterBytes.length > 0) {
            buf.writeVarInt(bloomFilterBytes.length);
            buf.writeBytes(bloomFilterBytes);
        } else {
            buf.writeVarInt(0);
        }
    }

    public static ChunkCacheManifestPayload read(PacketByteBuf buf) {
        boolean more = buf.readBoolean();
        int length = buf.readVarInt();
        if (length > MAX_BLOOM_FILTER_SIZE) {
            throw new IllegalArgumentException("Bloom filter chunk too large: " + length + " bytes");
        }
        if (length > 0) {
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            return new ChunkCacheManifestPayload(bytes, more);
        }
        return new ChunkCacheManifestPayload(new byte[0], more);
    }
}
