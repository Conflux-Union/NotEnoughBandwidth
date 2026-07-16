package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.FriendlyByteBuf;
//#if MC>=12005
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#endif
import net.minecraft.resources.Identifier;

/**
 * Sent from client to server during handshake (after NebAck).
 * Carries the serialized Guava BloomFilter containing content hashes of all
 * locally cached chunks. The server uses this to skip sending full chunk data
 * when the client is likely to have a cached copy.
 * <p>
 * On 1.20.1 the C2S custom payload limit is 32 767 bytes, so the bloom filter
 * is split into chunks; each chunk carries a {@code hasMore} flag and the
 * server accumulates until it receives one with {@code hasMore == false}.
 */
//#if MC>=12005
public record ChunkCacheManifestPayload(byte[] bloomFilterBytes) implements CustomPacketPayload {
    public static final Type<ChunkCacheManifestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "chunk_cache_manifest"));

    private static final int MAX_BLOOM_FILTER_SIZE = 2 * 1024 * 1024; // 2MB max

    public static final StreamCodec<FriendlyByteBuf, ChunkCacheManifestPayload> CODEC =
            StreamCodec.ofMember(ChunkCacheManifestPayload::write, ChunkCacheManifestPayload::read);

    public void write(FriendlyByteBuf buf) {
        if (bloomFilterBytes != null && bloomFilterBytes.length > 0) {
            buf.writeVarInt(bloomFilterBytes.length);
            buf.writeBytes(bloomFilterBytes);
        } else {
            buf.writeVarInt(0);
        }
    }

    public static ChunkCacheManifestPayload read(FriendlyByteBuf buf) {
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
//#else
//$$ public record ChunkCacheManifestPayload(byte[] bloomFilterBytes, boolean hasMore) {
//$$     public static final ResourceLocation CHANNEL = new ResourceLocation(ModConstants.NETWORK_NAMESPACE, "chunk_cache_manifest");
//$$
//$$     private static final int MAX_BLOOM_FILTER_SIZE = 2 * 1024 * 1024; // 2MB max
//$$
//$$     /** Max bytes per chunk — leaves room for varint + boolean + channel overhead. */
//$$     public static final int MAX_CHUNK_SIZE = 30_000;
//$$
//$$     public void write(FriendlyByteBuf buf) {
//$$         buf.writeBoolean(hasMore);
//$$         if (bloomFilterBytes != null && bloomFilterBytes.length > 0) {
//$$             buf.writeVarInt(bloomFilterBytes.length);
//$$             buf.writeBytes(bloomFilterBytes);
//$$         } else {
//$$             buf.writeVarInt(0);
//$$         }
//$$     }
//$$
//$$     public static ChunkCacheManifestPayload read(FriendlyByteBuf buf) {
//$$         boolean more = buf.readBoolean();
//$$         int length = buf.readVarInt();
//$$         if (length > MAX_BLOOM_FILTER_SIZE) {
//$$             throw new IllegalArgumentException("Bloom filter chunk too large: " + length + " bytes");
//$$         }
//$$         if (length > 0) {
//$$             byte[] bytes = new byte[length];
//$$             buf.readBytes(bytes);
//$$             return new ChunkCacheManifestPayload(bytes, more);
//$$         }
//$$         return new ChunkCacheManifestPayload(new byte[0], more);
//$$     }
//$$ }
//#endif
