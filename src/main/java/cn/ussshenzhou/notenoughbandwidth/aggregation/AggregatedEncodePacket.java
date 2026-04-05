package cn.ussshenzhou.notenoughbandwidth.aggregation;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single packet for encoding into an aggregated blob.
 * <p>
 * Vanilla game packets are encoded via their write() method.
 * Custom payloads have their raw data bytes captured from getData().
 */
@SuppressWarnings("DataFlowIssue")
public class AggregatedEncodePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Encode");

    public final Identifier type;
    private final boolean isCustomPayload;
    private final Packet<?> packet;
    private final byte[] customData;

    public AggregatedEncodePacket(Packet<?> p, Identifier type) {
        if (p instanceof CustomPayloadC2SPacket cp) {
            this.isCustomPayload = true;
            this.packet = null;
            PacketByteBuf data = cp.getData();
            this.customData = new byte[data.readableBytes()];
            data.getBytes(data.readerIndex(), this.customData);
        } else if (p instanceof CustomPayloadS2CPacket cp) {
            this.isCustomPayload = true;
            this.packet = null;
            PacketByteBuf data = cp.getData();
            this.customData = new byte[data.readableBytes()];
            data.getBytes(data.readerIndex(), this.customData);
        } else {
            this.isCustomPayload = false;
            this.packet = p;
            this.customData = null;
        }
        this.type = type;
    }

    public void encode(ByteBuf buf, NetworkSide side) {
        if (isCustomPayload) {
            encodeCustom(buf);
        } else {
            encodeVanilla(buf, side);
        }
    }

    private void encodeVanilla(ByteBuf buf, NetworkSide side) {
        PacketByteBuf pBuf = new PacketByteBuf(buf);
        try {
            packet.write(pBuf);
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to encode packet {}", type, e);
        }
    }

    private void encodeCustom(ByteBuf buf) {
        buf.writeBytes(customData);
    }
}
