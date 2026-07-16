package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.FriendlyByteBuf;
//#if MC>=12005
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#endif
import net.minecraft.resources.Identifier;

/**
 * Sent from client to server when a ChunkHashPayload was received but
 * the local DB lookup returned nothing (bloom-filter false positive or stale cache).
 * Server responds by re-sending the full ClientboundLevelChunkWithLightPacket.
 */
//#if MC>=12005
public record ChunkRequestPayload(int chunkX, int chunkZ) implements CustomPacketPayload {
    public static final Type<ChunkRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "chunk_request"));

    public static final StreamCodec<FriendlyByteBuf, ChunkRequestPayload> CODEC =
            StreamCodec.ofMember(ChunkRequestPayload::write, ChunkRequestPayload::read);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
//#else
//$$ public record ChunkRequestPayload(int chunkX, int chunkZ) {
//$$     public static final ResourceLocation CHANNEL = new ResourceLocation(ModConstants.NETWORK_NAMESPACE, "chunk_request");
//#endif

    public void write(FriendlyByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
    }

    public static ChunkRequestPayload read(FriendlyByteBuf buf) {
        return new ChunkRequestPayload(buf.readInt(), buf.readInt());
    }
}
