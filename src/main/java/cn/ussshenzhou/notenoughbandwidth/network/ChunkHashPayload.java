package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.FriendlyByteBuf;
//#if MC>=12005
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#endif
import net.minecraft.resources.Identifier;

/**
 * Sent from server to client as a lightweight replacement for ClientboundLevelChunkWithLightPacket
 * when the server's bloom-filter check indicates the client has this chunk cached.
 * About 20 bytes vs ~10-20KB for a full chunk packet.
 *
 * Client looks up contentHash in its local DB. On hit: applies from cache.
 * On miss (bloom-filter false positive): sends ChunkRequestPayload to get full data.
 */
//#if MC>=12005
public record ChunkHashPayload(int chunkX, int chunkZ, long contentHash) implements CustomPacketPayload {
    public static final Type<ChunkHashPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "chunk_hash"));

    public static final StreamCodec<FriendlyByteBuf, ChunkHashPayload> CODEC =
            StreamCodec.ofMember(ChunkHashPayload::write, ChunkHashPayload::read);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
//#else
//$$ public record ChunkHashPayload(int chunkX, int chunkZ, long contentHash) {
//$$     public static final ResourceLocation CHANNEL = new ResourceLocation(ModConstants.NETWORK_NAMESPACE, "chunk_hash");
//#endif

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
        buf.writeLong(contentHash);
    }

    public static ChunkHashPayload read(FriendlyByteBuf buf) {
        int x = buf.readInt();
        int z = buf.readInt();
        long hash = buf.readLong();
        return new ChunkHashPayload(x, z, hash);
    }
}
