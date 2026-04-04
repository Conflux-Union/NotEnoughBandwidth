package cn.ussshenzhou.notenoughbandwidth.chunkcache;

import com.google.common.hash.Hashing;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.registry.DynamicRegistryManager;

/**
 * Deterministic content hash for chunk data packets.
 * Both server and client compute this independently on the same serialized bytes,
 * so they always agree on the hash without extra round-trips.
 */
public final class ChunkHashUtil {

    private ChunkHashUtil() {}

    /** Hash + serialized byte count for the chunk. */
    public record Result(long hash, int dataBytes) {}

    /**
     * Serializes ChunkData + LightData, returns the 64-bit murmur3 hash and the serialized size.
     * The serialization order matches ChunkDataS2CPacket.write(): chunkData then lightData.
     * The size is used by the server to record how many bytes were saved on a cache hit.
     */
    @SuppressWarnings("deprecation")
    public static Result compute(ChunkData chunkData, LightData lightData, DynamicRegistryManager registryManager) {
        // RegistryByteBuf is needed because ChunkData.BlockEntityData uses registry codecs.
        var inner = Unpooled.buffer(8192);
        var buf = new RegistryByteBuf(inner, registryManager);
        try {
            chunkData.write(buf);
            lightData.write(buf);
            int dataBytes = buf.readableBytes();
            byte[] bytes = new byte[dataBytes];
            buf.readBytes(bytes);
            return new Result(Hashing.murmur3_128().hashBytes(bytes).asLong(), dataBytes + 8); // +8 for chunkX/chunkZ ints
        } finally {
            inner.release();
        }
    }
}
