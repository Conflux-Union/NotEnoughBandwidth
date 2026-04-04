package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Sent from client to server during handshake (after NebAck).
 * Carries the serialized Guava BloomFilter containing content hashes of all
 * locally cached chunks. The server uses this to skip sending full chunk data
 * when the client is likely to have a cached copy.
 */
public record ChunkCacheManifestPayload(byte[] bloomFilterBytes) implements CustomPayload {
    public static final Id<ChunkCacheManifestPayload> TYPE =
            new Id<>(Identifier.of(ModConstants.MOD_ID, "chunk_cache_manifest"));

    private static final int MAX_BLOOM_FILTER_SIZE = 2 * 1024 * 1024; // 2MB max

    public static final PacketCodec<PacketByteBuf, ChunkCacheManifestPayload> CODEC =
            PacketCodec.of(ChunkCacheManifestPayload::write, ChunkCacheManifestPayload::read);

    private void write(PacketByteBuf buf) {
        if (bloomFilterBytes != null && bloomFilterBytes.length > 0) {
            buf.writeVarInt(bloomFilterBytes.length);
            buf.writeBytes(bloomFilterBytes);
        } else {
            buf.writeVarInt(0);
        }
    }

    private static ChunkCacheManifestPayload read(PacketByteBuf buf) {
        int length = buf.readVarInt();
        if (length > MAX_BLOOM_FILTER_SIZE) {
            throw new IllegalArgumentException("Bloom filter too large: " + length + " bytes");
        }
        if (length > 0) {
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            return new ChunkCacheManifestPayload(bytes);
        }
        return new ChunkCacheManifestPayload(new byte[0]);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }
}
