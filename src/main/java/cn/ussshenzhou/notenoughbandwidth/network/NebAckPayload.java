package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Sent from client to server during PLAY phase initialization to signal
 * that the client has NEB installed and can handle aggregated packets.
 * Server enables the compression path for this connection only after receiving this.
 */
public record NebAckPayload() implements CustomPayload {
    public static final Id<NebAckPayload> TYPE =
            new Id<>(Identifier.of(ModConstants.MOD_ID, "ack"));

    public static final PacketCodec<PacketByteBuf, NebAckPayload> CODEC =
            PacketCodec.of((payload, buf) -> {}, buf -> new NebAckPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }
}
