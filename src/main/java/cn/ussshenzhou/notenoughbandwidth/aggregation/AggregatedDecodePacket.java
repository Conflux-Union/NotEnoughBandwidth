package cn.ussshenzhou.notenoughbandwidth.aggregation;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.state.NetworkState;
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

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps a single sub-packet extracted from an aggregated blob for decoding.
 * <p>
 * Vanilla game packets are decoded through the PacketCodecDispatcher codec.
 * Custom payloads are decoded through their Fabric-registered payload codec
 * and wrapped in the appropriate CustomPayload packet for handling.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class AggregatedDecodePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Decode");

    private final Identifier type;
    private final ByteBuf data;
    private static volatile Object2IntArrayMap<Identifier> VANILLA_TO_ID;
    private static final AtomicInteger LAST_KNOWN_SIZE = new AtomicInteger(-1);

    public AggregatedDecodePacket(Identifier type, ByteBuf data) {
        this.type = type;
        this.data = data;
    }

    public Packet<?> decode(NetworkState<?> protocolInfo) {
        PacketCodecDispatcher vanillaCodec = (PacketCodecDispatcher) protocolInfo.codec();
        var idMap = getOrUpdateVanillaIdMap(vanillaCodec);

        int id = idMap.getInt(type);
        if (id != -1) {
            return decodeVanilla(vanillaCodec, id);
        }
        return decodeCustom(protocolInfo);
    }

    private Packet<?> decodeVanilla(PacketCodecDispatcher vanillaCodec, int id) {
        var entry = (PacketCodecDispatcher.PacketType) vanillaCodec.packetTypes.get(id);
        var codec = (PacketCodec<ByteBuf, Packet<?>>) entry.codec();
        try {
            return codec.decode(data);
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to decode packet {}", type, e);
            return null;
        }
    }

    private Packet<?> decodeCustom(NetworkState<?> protocolInfo) {
        NetworkSide side = protocolInfo.side();
        var registry = side == NetworkSide.CLIENTBOUND
                ? PayloadTypeRegistryImpl.PLAY_S2C
                : PayloadTypeRegistryImpl.PLAY_C2S;
        var payloadType = registry.get(type);
        if (payloadType == null) {
            LOGGER.error("Skipped: Unknown custom payload type {} during decode", type);
            return null;
        }
        var codec = (PacketCodec) payloadType.codec();
        try {
            CustomPayload payload = (CustomPayload) codec.decode(data);
            if (side == NetworkSide.CLIENTBOUND) {
                return new CustomPayloadS2CPacket(payload);
            } else {
                return new CustomPayloadC2SPacket(payload);
            }
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to decode custom payload {}", type, e);
            return null;
        }
    }

    private static Object2IntArrayMap<Identifier> getOrUpdateVanillaIdMap(PacketCodecDispatcher vanillaCodec) {
        int currentSize = vanillaCodec.typeToIndex.size();
        if (currentSize == LAST_KNOWN_SIZE.get()) {
            var cached = VANILLA_TO_ID;
            if (cached != null) return cached;
        }
        // Build a fresh snapshot — no mutation of shared state.
        var fresh = new Object2IntArrayMap<Identifier>();
        fresh.defaultReturnValue(-1);
        Object2IntMap<PacketType<?>> typeToIndex = (Object2IntMap<PacketType<?>>) vanillaCodec.typeToIndex;
        for (var entry : typeToIndex.object2IntEntrySet()) {
            fresh.put(entry.getKey().id(), entry.getIntValue());
        }
        VANILLA_TO_ID = fresh;
        LAST_KNOWN_SIZE.set(currentSize);
        return fresh;
    }

    public Identifier getType() {
        return type;
    }

    public ByteBuf getData() {
        return data;
    }
}
