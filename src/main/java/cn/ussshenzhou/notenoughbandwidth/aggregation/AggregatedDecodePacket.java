package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single sub-packet extracted from an aggregated blob for decoding.
 * <p>
 * Vanilla game packets are decoded via NetworkState.PLAY.getPacketHandler().
 * Custom payloads are reconstructed into the appropriate CustomPayload packet.
 */
public class AggregatedDecodePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Decode");

    private final Identifier type;
    private final ByteBuf data;

    public AggregatedDecodePacket(Identifier type, ByteBuf data) {
        this.type = type;
        this.data = data;
    }

    public Packet<?> decode(NetworkSide side) {
        Integer vanillaId = NamespaceIndexManager.getVanillaPacketId(type, side);
        if (vanillaId != null) {
            return decodeVanilla(side, vanillaId);
        }
        return decodeCustom(side);
    }

    private Packet<?> decodeVanilla(NetworkSide side, int id) {
        PacketByteBuf pBuf = new PacketByteBuf(data);
        try {
            return NetworkState.PLAY.getPacketHandler(side, id, pBuf);
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to decode packet {} (id={})", type, id, e);
            return null;
        }
    }

    private Packet<?> decodeCustom(NetworkSide side) {
        PacketByteBuf pBuf = new PacketByteBuf(data);
        try {
            if (side == NetworkSide.CLIENTBOUND) {
                return new CustomPayloadS2CPacket(type, pBuf);
            } else {
                return new CustomPayloadC2SPacket(type, pBuf);
            }
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to decode custom payload {}", type, e);
            return null;
        }
    }

    public Identifier getType() {
        return type;
    }

    public ByteBuf getData() {
        return data;
    }
}
