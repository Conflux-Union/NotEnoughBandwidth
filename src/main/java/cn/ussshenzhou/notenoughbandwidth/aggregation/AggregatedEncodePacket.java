package cn.ussshenzhou.notenoughbandwidth.aggregation;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single outbound packet for inclusion in an aggregated blob.
 * <p>
 * Vanilla packets are encoded through {@link ProtocolInfo#codec()}, whose
 * {@code StreamCodec} writes the VarInt packet id followed by the packet body.
 * Custom payloads are encoded through their Fabric-registered payload codec
 * directly (skipping the outer {@code CustomPacketPayload} wrapper) to avoid
 * re-transmitting the payload-type identifier that already lives in the
 * aggregation blob's per-sub-packet prefix.
 */
public class AggregatedEncodePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Encode");

    public final Identifier type;
    private final boolean isCustomPayload;
    private final Packet<?> packet;
    private final CustomPacketPayload payload;

    public AggregatedEncodePacket(Packet<?> p, Identifier type) {
        if (p instanceof ServerboundCustomPayloadPacket cp) {
            this.isCustomPayload = true;
            this.packet = null;
            this.payload = cp.payload();
        } else if (p instanceof ClientboundCustomPayloadPacket cp) {
            this.isCustomPayload = true;
            this.packet = null;
            this.payload = cp.payload();
        } else {
            this.isCustomPayload = false;
            this.packet = p;
            this.payload = null;
        }
        this.type = type;
    }

    public void encode(ByteBuf buf, ProtocolInfo<?> protocolInfo, PacketFlow side) {
        if (isCustomPayload) {
            encodeCustom(buf, side);
        } else {
            encodeVanilla(buf, protocolInfo);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void encodeVanilla(ByteBuf buf, ProtocolInfo<?> protocolInfo) {
        // ProtocolInfo.codec() is StreamCodec<ByteBuf, Packet<? super L>>.
        // It writes VarInt(packet-id) + packet body, handling all vanilla dispatch internally.
        StreamCodec codec = protocolInfo.codec();
        try {
            codec.encode(buf, packet);
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to encode packet {}", type, e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void encodeCustom(ByteBuf buf, PacketFlow side) {
        var registry = side == PacketFlow.CLIENTBOUND
                ? PayloadTypeRegistryImpl.CLIENTBOUND_PLAY
                : PayloadTypeRegistryImpl.SERVERBOUND_PLAY;
        var payloadType = registry.get(type);
        if (payloadType == null) {
            LOGGER.error("Skipped: Unknown custom payload type {}", type);
            return;
        }
        StreamCodec codec = (StreamCodec) payloadType.codec();
        try {
            codec.encode(buf, payload);
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to encode custom payload {}", type, e);
        }
    }
}
