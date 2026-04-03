package cn.ussshenzhou.notenoughbandwidth.aggregation;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import net.minecraft.network.handler.PacketCodecDispatcher;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.PacketType;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single packet for encoding into an aggregated blob.
 * <p>
 * All packets (vanilla and modded custom payloads) are encoded through
 * the vanilla dispatch codec. Custom payloads are already wrapped in
 * CustomPayloadS2C/C2SPacket which the dispatch codec knows how to handle.
 * The type prefix is written separately by CustomPacketPrefixHelper.
 */
@SuppressWarnings("DataFlowIssue")
public class AggregatedEncodePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Encode");

    public final Identifier type;
    private final Packet<?> packet;

    public AggregatedEncodePacket(Packet<?> p, Identifier type) {
        this.packet = p;
        this.type = type;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public void encode(ByteBuf buf, NetworkState<?> protocolInfo, NetworkSide side) {
        PacketCodecDispatcher vanillaCodec = (PacketCodecDispatcher) protocolInfo.codec();
        var packetType = vanillaCodec.packetIdGetter.apply(packet);
        int id = vanillaCodec.typeToIndex.getOrDefault(packetType, -1);
        if (id == -1) {
            LOGGER.error("Skipped: Unknown packet type {}", type);
            return;
        }
        var entry = (PacketCodecDispatcher.PacketType) vanillaCodec.packetTypes.get(id);
        var codec = (PacketCodec<ByteBuf, Packet<?>>) entry.codec();
        try {
            codec.encode(buf, packet);
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to encode packet {}", type, e);
        }
    }
}
