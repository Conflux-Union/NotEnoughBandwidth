package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.indextype.CustomPacketPrefixHelper;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.util.DefaultChannelPipelineHelper;
import cn.ussshenzhou.notenoughbandwidth.zstd.DictionaryManager;
import cn.ussshenzhou.notenoughbandwidth.zstd.ZstdHelper;
import io.netty.buffer.ByteBufAllocator;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.OffThreadException;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * Aggregation packet that bundles multiple sub-packets into one compressed blob.
 * In 1.20.1 this is not a CustomPayload — it's serialized manually and wrapped
 * in a CustomPayloadS2CPacket/C2SPacket by the caller.
 */
public class PacketAggregationPacket {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Aggregation");

    public static final Identifier CHANNEL = new Identifier(ModConstants.MOD_ID, "packet_aggregation_packet");

    /**
     * Passes baked size from write() to the encoder mixin on the same Netty I/O thread.
     * The encoder reads and resets this after encoding the wrapping CustomPayloadS2C/C2SPacket.
     */
    public static final ThreadLocal<Integer> LAST_BAKED_SIZE = ThreadLocal.withInitial(() -> -1);

    private int bakedSize;

    // ---- encode side ----
    private final ArrayList<AggregatedEncodePacket> packetsToEncode;
    private final NetworkSide side;
    private ClientConnection connection;

    public PacketAggregationPacket(ArrayList<AggregatedEncodePacket> packetsToEncode,
                                   NetworkSide side,
                                   ClientConnection connection) {
        this.packetsToEncode = packetsToEncode;
        this.side = side;
        this.connection = connection;
    }

    public void write(PacketByteBuf buffer) {
        var rawBuf = new PacketByteBuf(ByteBufAllocator.DEFAULT.buffer());
        try {
            packetsToEncode.forEach(p -> encodeSubPacket(rawBuf, p));

            int rawSize = rawBuf.readableBytes();
            if (DictionaryManager.isSampling()) {
                byte[] sample = new byte[rawSize];
                rawBuf.getBytes(rawBuf.readerIndex(), sample);
                DictionaryManager.collectSample(sample);
            }
            boolean compress = rawSize >= 32;
            buffer.writeBoolean(compress);
            if (compress) {
                buffer.writeVarInt(rawSize);
                var compressedBuf = new PacketByteBuf(ZstdHelper.compress(connection, rawBuf));
                try {
                    if (ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class).debugLog) {
                        LOGGER.debug("Aggregated and compressed: {} -> {} bytes ({} %)",
                                rawSize, compressedBuf.readableBytes(),
                                String.format("%.2f", 100f * compressedBuf.readableBytes() / rawSize));
                    }
                    buffer.writeBytes(compressedBuf);
                    this.bakedSize = compressedBuf.readableBytes();
                } finally {
                    compressedBuf.release();
                }
            } else {
                buffer.writeBytes(rawBuf);
                this.bakedSize = rawSize;
            }
            LAST_BAKED_SIZE.set(this.bakedSize);
            SimpleStatManager.outRaw(rawSize);
        } finally {
            rawBuf.release();
        }
    }

    private void encodeSubPacket(PacketByteBuf raw, AggregatedEncodePacket packet) {
        CustomPacketPrefixHelper.write(packet.type, raw);
        var d = new PacketByteBuf(ByteBufAllocator.DEFAULT.buffer());
        try {
            packet.encode(d, side);
            raw.writeVarInt(d.readableBytes());
            raw.writeBytes(d);
        } finally {
            d.release();
        }
    }

    // ---- decode side ----
    private PacketByteBuf data;

    private PacketAggregationPacket(PacketByteBuf buffer) {
        this.side = null;
        this.packetsToEncode = null;
        // Take direct ownership of the buffer — caller is responsible for
        // passing us a buf we can own (e.g. buf.copy()).
        this.data = buffer;
    }

    public static PacketAggregationPacket read(PacketByteBuf buffer) {
        return new PacketAggregationPacket(buffer);
    }

    // ---- handle side ----
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void handle(ClientConnection conn) {
        this.connection = conn;

        boolean compressed = data.readBoolean();
        PacketByteBuf raw;
        if (compressed) {
            int size = data.readVarInt();
            raw = new PacketByteBuf(ZstdHelper.decompress(conn, data.retainedDuplicate(), size));
        } else {
            raw = new PacketByteBuf(data.retain());
        }
        SimpleStatManager.inRaw(raw.readableBytes());

        var decoder = DefaultChannelPipelineHelper.getPacketDecoder(
                conn.channel.pipeline());
        if (decoder == null) {
            LOGGER.error("Failed to get DecoderHandler for inbound protocol");
            data.release();
            raw.release();
            return;
        }
        // In 1.20.1, DecoderHandler has a 'side' field (access-widened)
        NetworkSide decoderSide = decoder.side;
        var packetsToHandle = new ArrayList<AggregatedDecodePacket>();
        try {
            while (raw.readableBytes() > 0) {
                var type = CustomPacketPrefixHelper.read(raw);
                var size = raw.readVarInt();
                var subData = new PacketByteBuf(raw.readRetainedSlice(size));
                if (type == null) {
                    LOGGER.error("Unknown packet type index in aggregated blob — skipping {} bytes", size);
                    subData.release();
                    continue;
                }
                packetsToHandle.add(new AggregatedDecodePacket(type, subData));
            }
        } finally {
            data.release();
            raw.release();
        }

        for (var sub : packetsToHandle) {
            try {
                Packet<?> decoded = sub.decode(decoderSide);
                if (decoded != null) {
                    var listener = conn.getPacketListener();
                    if (listener != null) {
                        ((Packet) decoded).apply(listener);
                    }
                }
            } catch (OffThreadException e) {
                // Expected: packet has been scheduled on the main thread by forceMainThread()
            } catch (Exception e) {
                LOGGER.error("Failed to handle decoded packet {}", sub.getType(), e);
            } finally {
                sub.getData().release();
            }
        }
    }

    public int getBakedSize() {
        return bakedSize;
    }

    public void setBakedSize(int bakedSize) {
        this.bakedSize = bakedSize;
    }
}
