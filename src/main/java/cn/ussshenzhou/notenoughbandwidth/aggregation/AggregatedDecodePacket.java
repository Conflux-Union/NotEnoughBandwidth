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
 * Wraps a single sub-packet slice extracted from an aggregated blob for decoding.
 * <p>
 * Dispatch rule: if {@code type} matches a Fabric-registered custom payload,
 * decode the payload bytes via its registered codec and wrap in the appropriate
 * {@code *CustomPayloadPacket}. Otherwise treat the slice as a vanilla packet
 * body prefixed with its VarInt id and let {@link ProtocolInfo#codec()} decode it.
 */
public class AggregatedDecodePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Decode");

    private final Identifier type;
    private final ByteBuf data;

    public AggregatedDecodePacket(Identifier type, ByteBuf data) {
        this.type = type;
        this.data = data;
    }

    public Packet<?> decode(ProtocolInfo<?> protocolInfo) {
        PacketFlow side = protocolInfo.flow();
        var registry = side == PacketFlow.CLIENTBOUND
                ? PayloadTypeRegistryImpl.CLIENTBOUND_PLAY
                : PayloadTypeRegistryImpl.SERVERBOUND_PLAY;
        var payloadType = registry.get(type);
        if (payloadType != null) {
            return decodeCustom(payloadType, side);
        }
        return decodeVanilla(protocolInfo);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Packet<?> decodeVanilla(ProtocolInfo<?> protocolInfo) {
        // Mirror image of AggregatedEncodePacket.encodeVanilla: the ProtocolInfo
        // codec reads VarInt(packet-id) + body and returns the reconstructed packet.
        StreamCodec codec = protocolInfo.codec();
        try {
            return (Packet<?>) codec.decode(data);
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to decode packet {}", type, e);
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Packet<?> decodeCustom(CustomPacketPayload.TypeAndCodec<?, ?> payloadType, PacketFlow side) {
        StreamCodec codec = (StreamCodec) payloadType.codec();
        try {
            CustomPacketPayload payload = (CustomPacketPayload) codec.decode(data);
            if (side == PacketFlow.CLIENTBOUND) {
                return new ClientboundCustomPayloadPacket(payload);
            }
            return new ServerboundCustomPayloadPacket(payload);
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
