package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Sent from client to server when a ChunkHashPayload was received but
 * the local DB lookup returned nothing (bloom-filter false positive or stale cache).
 * Server responds by re-sending the full ClientboundLevelChunkWithLightPacket.
 */
public record ChunkRequestPayload(int chunkX, int chunkZ) implements CustomPacketPayload {
    public static final Type<ChunkRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "chunk_request"));

    public static final StreamCodec<FriendlyByteBuf, ChunkRequestPayload> CODEC =
            StreamCodec.ofMember(ChunkRequestPayload::write, ChunkRequestPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
    }

    private static ChunkRequestPayload read(FriendlyByteBuf buf) {
        return new ChunkRequestPayload(buf.readInt(), buf.readInt());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
