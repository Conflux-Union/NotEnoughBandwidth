package cn.ussshenzhou.notenoughbandwidth.aggregation;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import cn.ussshenzhou.notenoughbandwidth.indextype.CustomPacketPrefixHelper;
import cn.ussshenzhou.notenoughbandwidth.stat.PacketTypeStatManager;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.util.DefaultChannelPipelineHelper;
import cn.ussshenzhou.notenoughbandwidth.zstd.DictionaryManager;
import cn.ussshenzhou.notenoughbandwidth.zstd.ZstdHelper;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.DefaultChannelPipeline;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.state.NetworkState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

public class PacketAggregationPacket implements CustomPayload {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Aggregation");

    public static final Id<PacketAggregationPacket> TYPE =
            new Id<>(Identifier.of(ModConstants.NETWORK_NAMESPACE, "packet_aggregation_packet"));

    public static final PacketCodec<RegistryByteBuf, PacketAggregationPacket> CODEC =
            PacketCodec.of(PacketAggregationPacket::write, PacketAggregationPacket::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }

    private int bakedSize;
    /**
     * Wire bytes of this aggregation packet's inner CustomPayload blob (boolean +
     * optional varint header + compressed-or-raw payload), measured symmetrically
     * on encode and decode so per-PacketType accounting attributes the same total
     * on both sides. Distinct from {@link #bakedSize} which the SimpleStatManager
     * pipeline uses with different semantics on each side.
     */
    private int innerBlobSize;

    // ---- encode side ----
    private final ArrayList<AggregatedEncodePacket> packetsToEncode;
    private final NetworkState<?> protocolInfo;
    private ClientConnection connection;

    public PacketAggregationPacket(ArrayList<AggregatedEncodePacket> packetsToEncode,
                                   NetworkState<?> protocolInfo,
                                   ClientConnection connection) {
        this.packetsToEncode = packetsToEncode;
        this.protocolInfo = protocolInfo;
        this.connection = connection;
    }

    public void write(RegistryByteBuf buffer) {
        int blobStartIdx = buffer.writerIndex();
        var rawBuf = new RegistryByteBuf(ByteBufAllocator.DEFAULT.buffer(), buffer.getRegistryManager());
        try {
            int[] subRawSizes = new int[packetsToEncode.size()];
            int prevWriterIdx = rawBuf.writerIndex();
            for (int i = 0; i < packetsToEncode.size(); i++) {
                encodeSubPacket(rawBuf, packetsToEncode.get(i));
                int newIdx = rawBuf.writerIndex();
                subRawSizes[i] = newIdx - prevWriterIdx;
                prevWriterIdx = newIdx;
            }

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
            SimpleStatManager.outRaw(rawSize);
            this.innerBlobSize = buffer.writerIndex() - blobStartIdx;
            recordPerTypeOut(subRawSizes, rawSize, this.innerBlobSize);
        } finally {
            rawBuf.release();
        }
    }

    private void recordPerTypeOut(int[] subRawSizes, int rawSize, int bakedSize) {
        if (rawSize <= 0 || subRawSizes.length == 0) return;
        var side = protocolInfo.side();
        long allocatedBaked = 0;
        for (int i = 0; i < subRawSizes.length; i++) {
            long subBaked;
            if (i == subRawSizes.length - 1) {
                subBaked = bakedSize - allocatedBaked;
                if (subBaked < 0) subBaked = 0;
            } else {
                subBaked = (long) Math.floor((double) subRawSizes[i] * bakedSize / rawSize);
                allocatedBaked += subBaked;
            }
            PacketTypeStatManager.record(side, packetsToEncode.get(i).type, subRawSizes[i], subBaked);
        }
    }

    private void encodeSubPacket(RegistryByteBuf raw, AggregatedEncodePacket packet) {
        CustomPacketPrefixHelper.write(packet.type, raw);
        var d = new RegistryByteBuf(ByteBufAllocator.DEFAULT.buffer(), raw.getRegistryManager());
        try {
            packet.encode(d, protocolInfo, protocolInfo.side());
            raw.writeVarInt(d.readableBytes());
            raw.writeBytes(d);
        } finally {
            d.release();
        }
    }

    // ---- decode side ----
    private RegistryByteBuf data;

    public PacketAggregationPacket(RegistryByteBuf buffer) {
        this.protocolInfo = null;
        this.packetsToEncode = null;
        this.innerBlobSize = buffer.readableBytes();
        this.data = new RegistryByteBuf(buffer.retainedDuplicate(), buffer.getRegistryManager());
        buffer.readerIndex(buffer.writerIndex());
    }

    // ---- handle side ----
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void handle(ClientConnection conn) {
        this.connection = conn;

        boolean compressed = data.readBoolean();
        RegistryByteBuf raw;
        if (compressed) {
            int size = data.readVarInt();
            raw = new RegistryByteBuf(ZstdHelper.decompress(conn, data.retainedDuplicate(), size), data.getRegistryManager());
        } else {
            raw = new RegistryByteBuf(data.retain(), data.getRegistryManager());
        }
        SimpleStatManager.inRaw(raw.readableBytes());

        var decoder = DefaultChannelPipelineHelper.getPacketDecoder(
                (DefaultChannelPipeline) conn.channel.pipeline());
        if (decoder == null) {
            LOGGER.error("Failed to get DecoderHandler for inbound protocol");
            data.release();
            raw.release();
            return;
        }
        var inboundProtocol = decoder.state;
        var packetsToHandle = new ArrayList<AggregatedDecodePacket>();
        var subRawSizes = new ArrayList<Integer>();
        int totalSubRaw = 0;
        try {
            int prevReaderIdx = raw.readerIndex();
            while (raw.readableBytes() > 0) {
                var type = CustomPacketPrefixHelper.read(raw);
                var size = raw.readVarInt();
                var subData = new RegistryByteBuf(raw.readRetainedSlice(size), data.getRegistryManager());
                int newIdx = raw.readerIndex();
                int subRaw = newIdx - prevReaderIdx;
                prevReaderIdx = newIdx;
                if (type == null) {
                    LOGGER.error("Unknown packet type index in aggregated blob — skipping {} bytes", size);
                    subData.release();
                    continue;
                }
                packetsToHandle.add(new AggregatedDecodePacket(type, subData));
                subRawSizes.add(subRaw);
                totalSubRaw += subRaw;
            }
        } finally {
            data.release();
            raw.release();
        }

        recordPerTypeIn(inboundProtocol.side(), packetsToHandle, subRawSizes, totalSubRaw, this.innerBlobSize);

        for (var sub : packetsToHandle) {
            try {
                Packet<?> decoded = sub.decode(inboundProtocol);
                if (decoded != null) {
                    var listener = conn.getPacketListener();
                    if (listener != null) {
                        ((Packet) decoded).apply(listener);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to handle decoded packet {}", sub.getType(), e);
            } finally {
                sub.getData().release();
            }
        }
    }

    private static void recordPerTypeIn(net.minecraft.network.NetworkSide side,
                                        ArrayList<AggregatedDecodePacket> sub,
                                        ArrayList<Integer> subRawSizes,
                                        int totalSubRaw,
                                        int bundleBaked) {
        int n = sub.size();
        if (n == 0 || totalSubRaw <= 0) return;
        long allocatedBaked = 0;
        for (int i = 0; i < n; i++) {
            int subRaw = subRawSizes.get(i);
            long subBaked;
            if (i == n - 1) {
                subBaked = bundleBaked - allocatedBaked;
                if (subBaked < 0) subBaked = 0;
            } else {
                subBaked = (long) Math.floor((double) subRaw * bundleBaked / totalSubRaw);
                allocatedBaked += subBaked;
            }
            PacketTypeStatManager.record(side, sub.get(i).getType(), subRaw, subBaked);
        }
    }

    public int getBakedSize() {
        return bakedSize;
    }

    public void setBakedSize(int bakedSize) {
        this.bakedSize = bakedSize;
    }
}
