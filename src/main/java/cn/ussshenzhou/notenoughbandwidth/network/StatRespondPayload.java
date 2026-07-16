package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
//#if MC>=12005
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#else
//$$ import net.minecraft.network.FriendlyByteBuf;
//#endif
import net.minecraft.resources.Identifier;

public record StatRespondPayload(
        long inboundBytesBaked,
        long inboundBytesRaw,
        long outboundBytesBaked,
        long outboundBytesRaw,
        double inboundSpeedBaked,
        double inboundSpeedRaw,
        double outboundSpeedBaked,
        double outboundSpeedRaw,
        int dictSize,
        int dictSampleCount,
        int dictSampleThreshold,
        long chunkCacheHits,
        long chunkCacheMisses,
        long chunkCacheSavedBytes,
        long nicInboundSpeed,
        long nicOutboundSpeed
//#if MC>=12005
) implements CustomPacketPayload {
    public static final Type<StatRespondPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "stat_resp"));

    public static final StreamCodec<ByteBuf, StatRespondPayload> CODEC = new StreamCodec<>() {
        @Override
        public StatRespondPayload decode(ByteBuf buf) {
            return new StatRespondPayload(
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.DOUBLE.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf)
            );
        }

        @Override
        public void encode(ByteBuf buf, StatRespondPayload value) {
            ByteBufCodecs.VAR_LONG.encode(buf, value.inboundBytesBaked);
            ByteBufCodecs.VAR_LONG.encode(buf, value.inboundBytesRaw);
            ByteBufCodecs.VAR_LONG.encode(buf, value.outboundBytesBaked);
            ByteBufCodecs.VAR_LONG.encode(buf, value.outboundBytesRaw);
            ByteBufCodecs.DOUBLE.encode(buf, value.inboundSpeedBaked);
            ByteBufCodecs.DOUBLE.encode(buf, value.inboundSpeedRaw);
            ByteBufCodecs.DOUBLE.encode(buf, value.outboundSpeedBaked);
            ByteBufCodecs.DOUBLE.encode(buf, value.outboundSpeedRaw);
            ByteBufCodecs.VAR_INT.encode(buf, value.dictSize);
            ByteBufCodecs.VAR_INT.encode(buf, value.dictSampleCount);
            ByteBufCodecs.VAR_INT.encode(buf, value.dictSampleThreshold);
            ByteBufCodecs.VAR_LONG.encode(buf, value.chunkCacheHits);
            ByteBufCodecs.VAR_LONG.encode(buf, value.chunkCacheMisses);
            ByteBufCodecs.VAR_LONG.encode(buf, value.chunkCacheSavedBytes);
            ByteBufCodecs.VAR_LONG.encode(buf, value.nicInboundSpeed);
            ByteBufCodecs.VAR_LONG.encode(buf, value.nicOutboundSpeed);
        }
    };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//#else
//$$ ) {
//$$     public static final ResourceLocation CHANNEL = new ResourceLocation(ModConstants.NETWORK_NAMESPACE, "stat_resp");
//$$
//$$     public void write(FriendlyByteBuf buf) {
//$$         buf.writeLong(inboundBytesBaked);
//$$         buf.writeLong(inboundBytesRaw);
//$$         buf.writeLong(outboundBytesBaked);
//$$         buf.writeLong(outboundBytesRaw);
//$$         buf.writeDouble(inboundSpeedBaked);
//$$         buf.writeDouble(inboundSpeedRaw);
//$$         buf.writeDouble(outboundSpeedBaked);
//$$         buf.writeDouble(outboundSpeedRaw);
//$$         buf.writeVarInt(dictSize);
//$$         buf.writeVarInt(dictSampleCount);
//$$         buf.writeVarInt(dictSampleThreshold);
//$$         buf.writeLong(chunkCacheHits);
//$$         buf.writeLong(chunkCacheMisses);
//$$         buf.writeLong(chunkCacheSavedBytes);
//$$         buf.writeLong(nicInboundSpeed);
//$$         buf.writeLong(nicOutboundSpeed);
//$$     }
//$$
//$$     public static StatRespondPayload read(FriendlyByteBuf buf) {
//$$         return new StatRespondPayload(
//$$                 buf.readLong(),
//$$                 buf.readLong(),
//$$                 buf.readLong(),
//$$                 buf.readLong(),
//$$                 buf.readDouble(),
//$$                 buf.readDouble(),
//$$                 buf.readDouble(),
//$$                 buf.readDouble(),
//$$                 buf.readVarInt(),
//$$                 buf.readVarInt(),
//$$                 buf.readVarInt(),
//$$                 buf.readLong(),
//$$                 buf.readLong(),
//$$                 buf.readLong(),
//$$                 buf.readLong(),
//$$                 buf.readLong()
//$$         );
//$$     }
//$$ }
//#endif
