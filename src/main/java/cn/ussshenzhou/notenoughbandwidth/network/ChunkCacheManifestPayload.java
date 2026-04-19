package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Sent from client to server during handshake (after NebAck).
 * Carries the serialized Guava BloomFilter containing content hashes of all
 * locally cached chunks. The server uses this to skip sending full chunk data
 * when the client is likely to have a cached copy.
 */
public record ChunkCacheManifestPayload(byte[] bloomFilterBytes) implements CustomPacketPayload {
    public static final Type<ChunkCacheManifestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "chunk_cache_manifest"));

    private static final int MAX_BLOOM_FILTER_SIZE = 2 * 1024 * 1024; // 2MB max

    public static final StreamCodec<FriendlyByteBuf, ChunkCacheManifestPayload> CODEC =
            StreamCodec.ofMember(ChunkCacheManifestPayload::write, ChunkCacheManifestPayload::read);

    private void write(FriendlyByteBuf buf) {
        if (bloomFilterBytes != null && bloomFilterBytes.length > 0) {
            buf.writeVarInt(bloomFilterBytes.length);
            buf.writeBytes(bloomFilterBytes);
        } else {
            buf.writeVarInt(0);
        }
    }

    private static ChunkCacheManifestPayload read(FriendlyByteBuf buf) {
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
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
