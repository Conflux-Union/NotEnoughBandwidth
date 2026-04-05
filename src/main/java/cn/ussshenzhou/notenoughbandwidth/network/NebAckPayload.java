package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * Sent from client to server during PLAY phase initialization to signal
 * that the client has NEB installed and can handle aggregated packets.
 * Server enables the compression path for this connection only after receiving this.
 */
public record NebAckPayload() {
    public static final Identifier CHANNEL = new Identifier("neb", "ack");

    public void write(PacketByteBuf buf) {
        // no-op: marker packet with no payload
    }

    public static NebAckPayload read(PacketByteBuf buf) {
        return new NebAckPayload();
    }
}
