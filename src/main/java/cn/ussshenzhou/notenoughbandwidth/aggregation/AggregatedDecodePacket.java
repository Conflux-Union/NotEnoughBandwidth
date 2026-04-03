package cn.ussshenzhou.notenoughbandwidth.aggregation;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2IntArrayMap;
import net.minecraft.network.NetworkState;
import net.minecraft.network.handler.PacketCodecDispatcher;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.PacketType;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a single sub-packet extracted from an aggregated blob for decoding.
 * <p>
 * All packets (vanilla and modded custom payloads) are decoded through
 * the vanilla dispatch codec, since custom payloads are wrapped in
 * CustomPayloadS2C/C2SPacket.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class AggregatedDecodePacket {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Decode");

    private final Identifier type;
    private final ByteBuf data;
    private static final Object2IntArrayMap<Identifier> VANILLA_TO_ID = new Object2IntArrayMap<>();

    static {
        VANILLA_TO_ID.defaultReturnValue(-1);
    }

    public AggregatedDecodePacket(Identifier type, ByteBuf data) {
        this.type = type;
        this.data = data;
    }

    public Packet<?> decode(NetworkState<?> protocolInfo) {
        PacketCodecDispatcher vanillaCodec = (PacketCodecDispatcher) protocolInfo.codec();
        updateVanillaIdMap(vanillaCodec);

        int id = VANILLA_TO_ID.getInt(type);
        if (id == -1) {
            LOGGER.error("Skipped: Unknown packet type {} during decode", type);
            return null;
        }
        var entry = (PacketCodecDispatcher.PacketType) vanillaCodec.packetTypes.get(id);
        var codec = (PacketCodec<ByteBuf, Packet<?>>) entry.codec();
        try {
            return codec.decode(data);
        } catch (Exception e) {
            LOGGER.error("Skipped: Failed to decode packet {}", type, e);
            return null;
        }
    }

    private void updateVanillaIdMap(PacketCodecDispatcher vanillaCodec) {
        if (vanillaCodec.typeToIndex.size() == VANILLA_TO_ID.size()) {
            return;
        }
        VANILLA_TO_ID.clear();
        vanillaCodec.typeToIndex.forEach((t, i) -> {
            if (t instanceof PacketType<?> pt) {
                VANILLA_TO_ID.put(pt.id(), (int) i);
            }
        });
    }

    public Identifier getType() {
        return type;
    }

    public ByteBuf getData() {
        return data;
    }
}
