package cn.ussshenzhou.notenoughbandwidth.aggregation;

import io.netty.buffer.ByteBuf;
//#if MC>=12005
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#else
//$$ import net.minecraft.network.FriendlyByteBuf;
//$$ import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
//$$ import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
//#endif
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single outbound packet for inclusion in an aggregated blob.
 * <p>
 * On 1.20.5+, vanilla packets are encoded through {@code ProtocolInfo.codec()},
 * whose {@code StreamCodec} writes the VarInt packet id followed by the packet
 * body, and custom payloads go through their Fabric-registered payload codec
 * directly (skipping the outer {@code CustomPacketPayload} wrapper).
 * <p>
 * On 1.20.1, vanilla packets are encoded via their {@code write()} method (the
 * per-sub-packet prefix already identifies the type), and custom payloads have
 * their raw data bytes captured from {@code getData()}.
 */
public class AggregatedEncodePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Encode");

    public final Identifier type;
    private final boolean isCustomPayload;
    private final Packet<?> packet;
    //#if MC>=12005
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
    //#else
    //$$ private final byte[] customData;
    //$$
    //$$ public AggregatedEncodePacket(Packet<?> p, ResourceLocation type) {
    //$$     if (p instanceof ServerboundCustomPayloadPacket cp) {
    //$$         this.isCustomPayload = true;
    //$$         this.packet = null;
    //$$         FriendlyByteBuf data = cp.getData();
    //$$         this.customData = new byte[data.readableBytes()];
    //$$         data.getBytes(data.readerIndex(), this.customData);
    //$$     } else if (p instanceof ClientboundCustomPayloadPacket cp) {
    //$$         this.isCustomPayload = true;
    //$$         this.packet = null;
    //$$         FriendlyByteBuf data = cp.getData();
    //$$         this.customData = new byte[data.readableBytes()];
    //$$         data.getBytes(data.readerIndex(), this.customData);
    //$$     } else {
    //$$         this.isCustomPayload = false;
    //$$         this.packet = p;
    //$$         this.customData = null;
    //$$     }
    //$$     this.type = type;
    //$$ }
    //$$
    //$$ public void encode(ByteBuf buf, PacketFlow side) {
    //$$     if (isCustomPayload) {
    //$$         buf.writeBytes(customData);
    //$$     } else {
    //$$         encodeVanilla(buf);
    //$$     }
    //$$ }
    //$$
    //$$ private void encodeVanilla(ByteBuf buf) {
    //$$     FriendlyByteBuf pBuf = new FriendlyByteBuf(buf);
    //$$     try {
    //$$         packet.write(pBuf);
    //$$     } catch (Exception e) {
    //$$         LOGGER.error("Skipped: Failed to encode packet {}", type, e);
    //$$     }
    //$$ }
    //#endif
}
