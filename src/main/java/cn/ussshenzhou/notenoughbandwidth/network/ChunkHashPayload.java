package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * Sent from server to client as a lightweight replacement for ChunkDataS2CPacket
 * when the server's bloom-filter check indicates the client has this chunk cached.
 * About 20 bytes vs ~10-20KB for a full chunk packet.
 *
 * Client looks up contentHash in its local DB. On hit: applies from cache.
 * On miss (bloom-filter false positive): sends ChunkRequestPayload to get full data.
 */
public record ChunkHashPayload(int chunkX, int chunkZ, long contentHash) {
    public static final Identifier CHANNEL = new Identifier("neb", "chunk_hash");

    public void write(PacketByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeLong(contentHash);
    }

    public static ChunkHashPayload read(PacketByteBuf buf) {
        int x = buf.readInt();
        int z = buf.readInt();
        long hash = buf.readLong();
        return new ChunkHashPayload(x, z, hash);
    }
}
