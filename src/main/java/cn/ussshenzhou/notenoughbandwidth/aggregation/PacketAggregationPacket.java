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
import net.minecraft.network.FriendlyByteBuf;
//#if MC>=12005
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#else
//$$ import io.netty.util.AttributeKey;
//$$ import net.minecraft.network.protocol.PacketFlow;
//$$ import net.minecraft.server.RunningOnDifferentThreadException;
//#endif
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

//#if MC>=12005
public class PacketAggregationPacket implements CustomPacketPayload {
//#else
//$$ /**
//$$  * On 1.20.1 this is not a CustomPacketPayload — it's serialized manually and
//$$  * wrapped in a Clientbound/ServerboundCustomPayloadPacket by the caller.
//$$  */
//$$ public class PacketAggregationPacket {
//#endif
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-Aggregation");

    //#if MC>=12005
    public static final Type<PacketAggregationPacket> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "packet_aggregation_packet"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PacketAggregationPacket> CODEC =
            StreamCodec.ofMember(PacketAggregationPacket::write, PacketAggregationPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
    //#else
    //$$ public static final ResourceLocation CHANNEL =
    //$$         new ResourceLocation(ModConstants.NETWORK_NAMESPACE, "packet_aggregation_packet");
    //$$
    //$$ /**
    //$$  * Passes baked (compressed) size from write() to the encoder mixin via a
    //$$  * Netty channel attribute — safe across threads (flush thread vs event loop).
    //$$  */
    //$$ public static final AttributeKey<Integer> BAKED_SIZE_KEY =
    //$$         AttributeKey.valueOf("neb_last_baked_size");
    //#endif

    private int bakedSize;
    private int innerBlobSize;

    // ---- encode side ----
    private final ArrayList<AggregatedEncodePacket> packetsToEncode;
    //#if MC>=12005
    private final ProtocolInfo<?> protocolInfo;
    //#else
    //$$ private final PacketFlow flow;
    //#endif
    private Connection connection;

    //#if MC>=12005
    public PacketAggregationPacket(ArrayList<AggregatedEncodePacket> packetsToEncode,
                                   ProtocolInfo<?> protocolInfo,
                                   Connection connection) {
        this.packetsToEncode = packetsToEncode;
        this.protocolInfo = protocolInfo;
        this.connection = connection;
    }
    //#else
    //$$ public PacketAggregationPacket(ArrayList<AggregatedEncodePacket> packetsToEncode,
    //$$                                PacketFlow flow,
    //$$                                Connection connection) {
    //$$     this.packetsToEncode = packetsToEncode;
    //$$     this.flow = flow;
    //$$     this.connection = connection;
    //$$ }
    //#endif

    //#if MC>=12005
    public void write(RegistryFriendlyByteBuf buffer) {
        int blobStartIdx = buffer.writerIndex();
        var rawBuf = new RegistryFriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer(), buffer.registryAccess());
    //#else
    //$$ public void write(FriendlyByteBuf buffer) {
    //$$     int blobStartIdx = buffer.writerIndex();
    //$$     var rawBuf = new FriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer());
    //#endif
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
            //#if MC<12005
            //$$ connection.channel.attr(BAKED_SIZE_KEY).set(this.bakedSize);
            //#endif
            SimpleStatManager.outRaw(rawSize);
            this.innerBlobSize = buffer.writerIndex() - blobStartIdx;
            recordPerTypeOut(subRawSizes, rawSize, this.innerBlobSize);
        } finally {
            rawBuf.release();
        }
    }

    private void recordPerTypeOut(int[] subRawSizes, int rawSize, int bakedSize) {
        if (rawSize <= 0 || subRawSizes.length == 0) return;
        //#if MC>=12005
        var direction = protocolInfo.flow();
        //#else
        //$$ var direction = this.flow;
        //#endif
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
            PacketTypeStatManager.record(direction, packetsToEncode.get(i).type, subRawSizes[i], subBaked);
        }
    }

    //#if MC>=12005
    private void encodeSubPacket(RegistryFriendlyByteBuf raw, AggregatedEncodePacket packet) {
        CustomPacketPrefixHelper.write(packet.type, raw);
        var d = new RegistryFriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer(), raw.registryAccess());
        try {
            packet.encode(d, protocolInfo, protocolInfo.flow());
    //#else
    //$$ private void encodeSubPacket(FriendlyByteBuf raw, AggregatedEncodePacket packet) {
    //$$     CustomPacketPrefixHelper.write(packet.type, raw);
    //$$     var d = new FriendlyByteBuf(ByteBufAllocator.DEFAULT.buffer());
    //$$     try {
    //$$         packet.encode(d, flow);
    //#endif
            raw.writeVarInt(d.readableBytes());
            raw.writeBytes(d);
        } finally {
            d.release();
        }
    }

    // ---- decode side ----
    //#if MC>=12005
    private RegistryFriendlyByteBuf data;

    public PacketAggregationPacket(RegistryFriendlyByteBuf buffer) {
        this.protocolInfo = null;
        this.packetsToEncode = null;
        this.innerBlobSize = buffer.readableBytes();
        this.data = new RegistryFriendlyByteBuf(buffer.retainedDuplicate(), buffer.registryAccess());
        buffer.readerIndex(buffer.writerIndex());
    }
    //#else
    //$$ private FriendlyByteBuf data;
    //$$
    //$$ private PacketAggregationPacket(FriendlyByteBuf buffer) {
    //$$     this.flow = null;
    //$$     this.packetsToEncode = null;
    //$$     // Take direct ownership of the buffer — caller is responsible for
    //$$     // passing us a buf we can own (e.g. buf.copy()).
    //$$     this.innerBlobSize = buffer.readableBytes();
    //$$     this.data = buffer;
    //$$ }
    //$$
    //$$ public static PacketAggregationPacket read(FriendlyByteBuf buffer) {
    //$$     return new PacketAggregationPacket(buffer);
    //$$ }
    //#endif

    // ---- handle side ----
    //#if MC>=12005
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void handle(Connection conn) {
        this.connection = conn;
        // Released exactly once in the finally below, on every path: normal
        // completion, the oversize rejection, the missing-decoder bailout, or
        // any exception thrown while reading the header / decompressing / parsing.
        RegistryFriendlyByteBuf raw = null;
        try {
            boolean compressed = data.readBoolean();
            if (compressed) {
                int size = data.readVarInt();
                // Count the wrapper's own header bytes (compress flag + size varint)
                // as inbound raw traffic, matching upstream's bakedSize accounting.
                SimpleStatManager.inRaw(innerBlobSize - data.readableBytes());
                if (size <= 0 || size > NotEnoughBandwidthConfig.get().getMaxPacketSize()) {
                    // A malicious client can otherwise force Context.decompress to
                    // ByteBuffer.allocateDirect(size) with an attacker-chosen size.
                    LOGGER.error("Rejected aggregate: decompressed size {} from {} is invalid or exceeds maxPacketSize",
                            size, conn.getRemoteAddress());
                    return;
                }
                raw = new RegistryFriendlyByteBuf(ZstdHelper.decompress(conn, data.retainedDuplicate(), size), data.registryAccess());
            } else {
                SimpleStatManager.inRaw(innerBlobSize - data.readableBytes());
                raw = new RegistryFriendlyByteBuf(data.retain(), data.registryAccess());
            }
            SimpleStatManager.inRaw(raw.readableBytes());

            var decoder = DefaultChannelPipelineHelper.getPacketDecoder(
                    (DefaultChannelPipeline) conn.channel.pipeline());
            if (decoder == null) {
                LOGGER.error("Failed to get PacketDecoder for inbound protocol");
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
            } catch (Exception e) {
                // A malformed blob (readVarInt overrun, readRetainedSlice past readable)
                // can throw mid-loop. Release every slice already retained above before
                // rethrowing, so the exception still kills the connection but nothing leaks.
                for (var collected : packetsToHandle) {
                    collected.getData().release();
                }
                throw e;
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
        } finally {
            data.release();
            if (raw != null) {
                raw.release();
            }
        }
    }
    //#else
    //$$ @SuppressWarnings({"rawtypes", "unchecked"})
    //$$ public void handle(Connection conn) {
    //$$     this.connection = conn;
    //$$     // Released exactly once in the finally below, on every path: normal
    //$$     // completion, the oversize rejection, the missing-decoder bailout, or
    //$$     // any exception thrown while reading the header / decompressing / parsing.
    //$$     FriendlyByteBuf raw = null;
    //$$     try {
    //$$         boolean compressed = data.readBoolean();
    //$$         if (compressed) {
    //$$             int size = data.readVarInt();
    //$$             // Count the wrapper's own header bytes (compress flag + size varint)
    //$$             // as inbound raw traffic, matching upstream's bakedSize accounting.
    //$$             SimpleStatManager.inRaw(innerBlobSize - data.readableBytes());
    //$$             if (size <= 0 || size > NotEnoughBandwidthConfig.get().getMaxPacketSize()) {
    //$$                 // A malicious client can otherwise force Context.decompress to
    //$$                 // ByteBuffer.allocateDirect(size) with an attacker-chosen size.
    //$$                 LOGGER.error("Rejected aggregate: decompressed size {} from {} is invalid or exceeds maxPacketSize",
    //$$                         size, conn.getRemoteAddress());
    //$$                 return;
    //$$             }
    //$$             raw = new FriendlyByteBuf(ZstdHelper.decompress(conn, data.retainedDuplicate(), size));
    //$$         } else {
    //$$             SimpleStatManager.inRaw(innerBlobSize - data.readableBytes());
    //$$             raw = new FriendlyByteBuf(data.retain());
    //$$         }
    //$$         SimpleStatManager.inRaw(raw.readableBytes());
    //$$
    //$$         var decoder = DefaultChannelPipelineHelper.getPacketDecoder(
    //$$                 (DefaultChannelPipeline) conn.channel.pipeline());
    //$$         if (decoder == null) {
    //$$             LOGGER.error("Failed to get PacketDecoder for inbound protocol");
    //$$             return;
    //$$         }
    //$$         // Access-widened PacketDecoder.flow gives the inbound direction.
    //$$         PacketFlow decoderFlow = decoder.flow;
    //$$         var packetsToHandle = new ArrayList<AggregatedDecodePacket>();
    //$$         var subRawSizes = new ArrayList<Integer>();
    //$$         int totalSubRaw = 0;
    //$$         try {
    //$$             int prevReaderIdx = raw.readerIndex();
    //$$             while (raw.readableBytes() > 0) {
    //$$                 var type = CustomPacketPrefixHelper.read(raw);
    //$$                 var size = raw.readVarInt();
    //$$                 var subData = new FriendlyByteBuf(raw.readRetainedSlice(size));
    //$$                 int newIdx = raw.readerIndex();
    //$$                 int subRaw = newIdx - prevReaderIdx;
    //$$                 prevReaderIdx = newIdx;
    //$$                 if (type == null) {
    //$$                     LOGGER.error("Unknown packet type index in aggregated blob — skipping {} bytes", size);
    //$$                     subData.release();
    //$$                     continue;
    //$$                 }
    //$$                 packetsToHandle.add(new AggregatedDecodePacket(type, subData));
    //$$                 subRawSizes.add(subRaw);
    //$$                 totalSubRaw += subRaw;
    //$$             }
    //$$         } catch (Exception e) {
    //$$             // A malformed blob (readVarInt overrun, readRetainedSlice past readable)
    //$$             // can throw mid-loop. Release every slice already retained above before
    //$$             // rethrowing, so the exception still kills the connection but nothing leaks.
    //$$             for (var collected : packetsToHandle) {
    //$$                 collected.getData().release();
    //$$             }
    //$$             throw e;
    //$$         }
    //$$
    //$$         recordPerTypeIn(decoderFlow, packetsToHandle, subRawSizes, totalSubRaw, this.innerBlobSize);
    //$$
    //$$         for (var sub : packetsToHandle) {
    //$$             try {
    //$$                 Packet<?> decoded = sub.decode(decoderFlow);
    //$$                 if (decoded != null) {
    //$$                     var listener = conn.getPacketListener();
    //$$                     if (listener != null) {
    //$$                         ((Packet) decoded).handle(listener);
    //$$                     }
    //$$                 }
    //$$             } catch (RunningOnDifferentThreadException e) {
    //$$                 // Expected: the packet re-scheduled itself onto the main thread.
    //$$             } catch (Exception e) {
    //$$                 LOGGER.error("Failed to handle decoded packet {}", sub.getType(), e);
    //$$             } finally {
    //$$                 sub.getData().release();
    //$$             }
    //$$         }
    //$$     } finally {
    //$$         data.release();
    //$$         if (raw != null) {
    //$$             raw.release();
    //$$         }
    //$$     }
    //$$ }
    //#endif

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
