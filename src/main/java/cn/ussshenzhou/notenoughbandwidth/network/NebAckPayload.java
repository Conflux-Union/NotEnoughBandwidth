package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.FriendlyByteBuf;
//#if MC>=12005
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#endif
import net.minecraft.resources.Identifier;

/**
 * Sent from client to server during PLAY phase initialization to signal
 * that the client has NEB installed and can handle aggregated packets.
 * Server enables the compression path for this connection only after receiving this.
 */
//#if MC>=12005
public record NebAckPayload() implements CustomPacketPayload {
    public static final Type<NebAckPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "ack"));

    public static final StreamCodec<FriendlyByteBuf, NebAckPayload> CODEC =
            StreamCodec.of((payload, buf) -> {}, buf -> new NebAckPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
//#else
//$$ public record NebAckPayload() {
//$$     public static final ResourceLocation CHANNEL = new ResourceLocation(ModConstants.NETWORK_NAMESPACE, "ack");
//#endif

    public void write(FriendlyByteBuf buf) {
        // no-op: marker packet with no payload
    }

    public static NebAckPayload read(FriendlyByteBuf buf) {
        return new NebAckPayload();
    }
}
