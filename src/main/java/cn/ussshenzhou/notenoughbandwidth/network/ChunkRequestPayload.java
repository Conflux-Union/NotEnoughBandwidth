package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * Sent from client to server when a ChunkHashPayload was received but
 * the local DB lookup returned nothing (bloom-filter false positive or stale cache).
 * Server responds by re-sending the full ChunkDataS2CPacket.
 */
public record ChunkRequestPayload(int chunkX, int chunkZ) {
    public static final Identifier CHANNEL = new Identifier("neb", "chunk_request");

    public void write(PacketByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
    }

    public static ChunkRequestPayload read(PacketByteBuf buf) {
        return new ChunkRequestPayload(buf.readInt(), buf.readInt());
    }
}
