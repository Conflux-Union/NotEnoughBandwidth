package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Sent from client to server during PLAY phase initialization to signal
 * that the client has NEB installed and can handle aggregated packets.
 * Server enables the compression path for this connection only after receiving this.
 */
public record NebAckPayload() implements CustomPacketPayload {
    public static final Type<NebAckPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "ack"));

    public static final StreamCodec<FriendlyByteBuf, NebAckPayload> CODEC =
            StreamCodec.of((payload, buf) -> {}, buf -> new NebAckPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
