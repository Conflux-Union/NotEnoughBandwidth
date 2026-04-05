package cn.ussshenzhou.notenoughbandwidth.aggregation;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.handler.PacketCodecDispatcher;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single packet for encoding into an aggregated blob.
 * <p>
 * Vanilla game packets are encoded through the PacketCodecDispatcher codec.
 * Custom payloads are encoded through their Fabric-registered payload codec
 * directly (without the outer CustomPayload wrapper), matching the NeoForge design.
 */
@SuppressWarnings("DataFlowIssue")
public class AggregatedEncodePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Encode");

    public final Identifier type;
    private final boolean isCustomPayload;
    private final Packet<?> packet;
    private final CustomPayload payload;

    public AggregatedEncodePacket(Packet<?> p, Identifier type) {
        if (p instanceof CustomPayloadC2SPacket cp) {
            this.isCustomPayload = true;
            this.packet = null;
            this.payload = cp.payload();
        } else if (p instanceof CustomPayloadS2CPacket cp) {
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

    public void encode(ByteBuf buf, NetworkState<?> protocolInfo, NetworkSide side) {
        if (isCustomPayload) {
            encodeCustom(buf, side);
        } else {
            encodeVanilla(buf, protocolInfo);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void encodeVanilla(ByteBuf buf, NetworkState<?> protocolInfo) {
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void encodeCustom(ByteBuf buf, NetworkSide side) {
        var registry = side == NetworkSide.CLIENTBOUND
                ? PayloadTypeRegistryImpl.PLAY_S2C
                : PayloadTypeRegistryImpl.PLAY_C2S;
        var payloadType = registry.get(type);
        if (payloadType == null) {
            LOGGER.error("Skipped: Unknown custom payload type {}", type);
            return;
        }
        var codec = (PacketCodec) payloadType.codec();
        try {
            codec.encode(buf, payload);
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to encode custom payload {}", type, e);
        }
    }
}
