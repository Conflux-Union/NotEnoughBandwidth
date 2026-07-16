package cn.ussshenzhou.notenoughbandwidth.aggregation;

import io.netty.buffer.ByteBuf;
//#if MC>=12005
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.PacketFlow;
//#else
//$$ import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
//$$ import net.minecraft.network.ConnectionProtocol;
//$$ import net.minecraft.network.FriendlyByteBuf;
//$$ import net.minecraft.network.protocol.PacketFlow;
//$$ import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
//$$ import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
//#endif
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single sub-packet slice extracted from an aggregated blob for decoding.
 * <p>
 * On 1.20.5+: if {@code type} matches a Fabric-registered custom payload, decode
 * the payload bytes via its registered codec and wrap in the appropriate
 * {@code *CustomPayloadPacket}; otherwise treat the slice as a vanilla packet
 * body prefixed with its VarInt id and let {@code ProtocolInfo.codec()} decode it.
 * <p>
 * On 1.20.1: vanilla packets are reconstructed via
 * {@code ConnectionProtocol.PLAY.createPacket()} using the int id resolved by
 * {@code NamespaceIndexManager}; custom payloads are re-wrapped raw.
 */
public class AggregatedDecodePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Decode");

    private final Identifier type;
    private final ByteBuf data;

    public AggregatedDecodePacket(Identifier type, ByteBuf data) {
        this.type = type;
        this.data = data;
    }

    //#if MC>=12005
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
    //#else
    //$$ public Packet<?> decode(PacketFlow side) {
    //$$     Integer vanillaId = NamespaceIndexManager.getVanillaPacketId(type, side);
    //$$     if (vanillaId != null) {
    //$$         return decodeVanilla(side, vanillaId);
    //$$     }
    //$$     return decodeCustom(side);
    //$$ }
    //$$
    //$$ private Packet<?> decodeVanilla(PacketFlow side, int id) {
    //$$     FriendlyByteBuf pBuf = new FriendlyByteBuf(data);
    //$$     try {
    //$$         return ConnectionProtocol.PLAY.createPacket(side, id, pBuf);
    //$$     } catch (Exception e) {
    //$$         LOGGER.error("Skipped: Failed to decode packet {} (id={})", type, id, e);
    //$$         return null;
    //$$     }
    //$$ }
    //$$
    //$$ private Packet<?> decodeCustom(PacketFlow side) {
    //$$     if (side == PacketFlow.CLIENTBOUND) {
    //$$         // S2C: copy() gives the packet an independent heap buf.
    //$$         // The caller's finally { sub.getData().release() } frees the
    //$$         // original slice, and the copy is GC'd with the packet.
    //$$         // We can't just retain() here because ClientboundCustomPayloadPacket
    //$$         // never releases its data — getData() returns data.copy(),
    //$$         // not the original, so the extra refCnt would leak.
    //$$         return new ClientboundCustomPayloadPacket(type, new FriendlyByteBuf(data.copy()));
    //$$     }
    //$$     // C2S: retain once so the caller's finally block can safely release.
    //$$     // ServerboundCustomPayloadPacket.handle() releases data after
    //$$     // onCustomPayload() returns, bringing refCnt back to 0.
    //$$     data.retain();
    //$$     return new ServerboundCustomPayloadPacket(type, new FriendlyByteBuf(data));
    //$$ }
    //#endif

    public Identifier getType() {
        return type;
    }

    public ByteBuf getData() {
        return data;
    }
}
