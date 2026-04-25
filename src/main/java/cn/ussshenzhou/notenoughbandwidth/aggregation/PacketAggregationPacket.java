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
import net.minecraft.network.Connection;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

public class PacketAggregationPacket implements CustomPacketPayload {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Aggregation");

    public static final Type<PacketAggregationPacket> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "packet_aggregation_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketAggregationPacket> CODEC =
            StreamCodec.ofMember(PacketAggregationPacket::write, PacketAggregationPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private int bakedSize;
    private int innerBlobSize;

    // ---- encode side ----
    private final ArrayList<AggregatedEncodePacket> packetsToEncode;
    private final ProtocolInfo<?> protocolInfo;
    private Connection connection;

    public PacketAggregationPacket(ArrayList<AggregatedEncodePacket> packetsToEncode,
                                   ProtocolInfo<?> protocolInfo,
                                   Connection connection) {
        this.packetsToEncode = packetsToEncode;
        this.protocolInfo = protocolInfo;
        this.connection = connection;
    }

    public void write(RegistryFriendlyByteBuf buffer) {
        int blobStartIdx = buffer.writerIndex();
        var rawBuf = new RegistryFriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer(), buffer.registryAccess());
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
                var compressedBuf = new FriendlyByteBuf(ZstdHelper.compress(connection, rawBuf));
                try {
                    this.bakedSize = compressedBuf.readableBytes();
                    if (ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class).debugLog) {
                        LOGGER.debug("Aggregated and compressed: {} -> {} bytes ({} %)",
                                rawSize, this.bakedSize,
                                String.format("%.2f", 100f * this.bakedSize / rawSize));
                    }
                    buffer.writeBytes(compressedBuf);
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
        var flow = protocolInfo.flow();
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
            PacketTypeStatManager.record(flow, packetsToEncode.get(i).type, subRawSizes[i], subBaked);
        }
    }

    private void encodeSubPacket(RegistryFriendlyByteBuf raw, AggregatedEncodePacket packet) {
        CustomPacketPrefixHelper.write(packet.type, raw);
        var d = new RegistryFriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer(), raw.registryAccess());
        try {
            packet.encode(d, protocolInfo, protocolInfo.flow());
            raw.writeVarInt(d.readableBytes());
            raw.writeBytes(d);
        } finally {
            d.release();
        }
    }

    // ---- decode side ----
    private RegistryFriendlyByteBuf data;

    public PacketAggregationPacket(RegistryFriendlyByteBuf buffer) {
        this.protocolInfo = null;
        this.packetsToEncode = null;
        this.innerBlobSize = buffer.readableBytes();
        this.data = new RegistryFriendlyByteBuf(buffer.retainedDuplicate(), buffer.registryAccess());
        buffer.readerIndex(buffer.writerIndex());
    }

    // ---- handle side ----
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void handle(Connection conn) {
        this.connection = conn;

        boolean compressed = data.readBoolean();
        RegistryFriendlyByteBuf raw;
        if (compressed) {
            int size = data.readVarInt();
            raw = new RegistryFriendlyByteBuf(ZstdHelper.decompress(conn, data.retainedDuplicate(), size), data.registryAccess());
        } else {
            raw = new RegistryFriendlyByteBuf(data.retain(), data.registryAccess());
        }
        SimpleStatManager.inRaw(raw.readableBytes());

        var decoder = DefaultChannelPipelineHelper.getPacketDecoder(
                (DefaultChannelPipeline) conn.channel.pipeline());
        if (decoder == null) {
            LOGGER.error("Failed to get PacketDecoder for inbound protocol");
            data.release();
            raw.release();
            return;
        }
        var inboundProtocol = decoder.protocolInfo;
        var packetsToHandle = new ArrayList<AggregatedDecodePacket>();
        var subRawSizes = new ArrayList<Integer>();
        int totalSubRaw = 0;
        try {
            int prevReaderIdx = raw.readerIndex();
            while (raw.readableBytes() > 0) {
                var type = CustomPacketPrefixHelper.read(raw);
                var size = raw.readVarInt();
                var subData = new RegistryFriendlyByteBuf(raw.readRetainedSlice(size), data.registryAccess());
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

        recordPerTypeIn(inboundProtocol.flow(), packetsToHandle, subRawSizes, totalSubRaw, this.innerBlobSize);

        for (var sub : packetsToHandle) {
            try {
                Packet<?> decoded = sub.decode(inboundProtocol);
                if (decoded != null) {
                    var listener = conn.getPacketListener();
                    if (listener != null) {
                        ((Packet) decoded).handle(listener);
                    }
                }
            } catch (Exception e) {
                LOGGER.error("Failed to handle decoded packet {}", sub.getType(), e);
            } finally {
                sub.getData().release();
            }
        }
    }

    private static void recordPerTypeIn(net.minecraft.network.protocol.PacketFlow flow,
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
            PacketTypeStatManager.record(flow, sub.get(i).getType(), subRaw, subBaked);
        }
    }

    public int getBakedSize() {
        return bakedSize;
    }

    public void setBakedSize(int bakedSize) {
        this.bakedSize = bakedSize;
    }
}
