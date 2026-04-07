package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Sent from client to server when a ChunkHashPayload was received but
 * the local DB lookup returned nothing (bloom-filter false positive or stale cache).
 * Server responds by re-sending the full ChunkDataS2CPacket.
 */
public record ChunkRequestPayload(int chunkX, int chunkZ) implements CustomPayload {
    public static final Id<ChunkRequestPayload> TYPE =
            new Id<>(Identifier.of(ModConstants.NETWORK_NAMESPACE, "chunk_request"));

    public static final PacketCodec<PacketByteBuf, ChunkRequestPayload> CODEC =
            PacketCodec.of(ChunkRequestPayload::write, ChunkRequestPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
    }

    private static ChunkRequestPayload read(PacketByteBuf buf) {
        return new ChunkRequestPayload(buf.readInt(), buf.readInt());
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }
}
