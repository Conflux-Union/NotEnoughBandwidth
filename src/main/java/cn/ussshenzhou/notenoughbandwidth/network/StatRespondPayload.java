package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

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
) implements CustomPayload {
    public static final Id<StatRespondPayload> TYPE =
            new Id<>(Identifier.of(ModConstants.NETWORK_NAMESPACE, "stat_resp"));

    public static final PacketCodec<ByteBuf, StatRespondPayload> CODEC = new PacketCodec<>() {
        @Override
        public StatRespondPayload decode(ByteBuf buf) {
            return new StatRespondPayload(
                    PacketCodecs.VAR_LONG.decode(buf),
                    PacketCodecs.VAR_LONG.decode(buf),
                    PacketCodecs.VAR_LONG.decode(buf),
                    PacketCodecs.VAR_LONG.decode(buf),
                    PacketCodecs.DOUBLE.decode(buf),
                    PacketCodecs.DOUBLE.decode(buf),
                    PacketCodecs.DOUBLE.decode(buf),
                    PacketCodecs.DOUBLE.decode(buf),
                    PacketCodecs.VAR_INT.decode(buf),
                    PacketCodecs.VAR_INT.decode(buf),
                    PacketCodecs.VAR_INT.decode(buf),
                    PacketCodecs.VAR_LONG.decode(buf),
                    PacketCodecs.VAR_LONG.decode(buf),
                    PacketCodecs.VAR_LONG.decode(buf),
                    PacketCodecs.VAR_LONG.decode(buf),
                    PacketCodecs.VAR_LONG.decode(buf)
            );
        }

        @Override
        public void encode(ByteBuf buf, StatRespondPayload value) {
            PacketCodecs.VAR_LONG.encode(buf, value.inboundBytesBaked);
            PacketCodecs.VAR_LONG.encode(buf, value.inboundBytesRaw);
            PacketCodecs.VAR_LONG.encode(buf, value.outboundBytesBaked);
            PacketCodecs.VAR_LONG.encode(buf, value.outboundBytesRaw);
            PacketCodecs.DOUBLE.encode(buf, value.inboundSpeedBaked);
            PacketCodecs.DOUBLE.encode(buf, value.inboundSpeedRaw);
            PacketCodecs.DOUBLE.encode(buf, value.outboundSpeedBaked);
            PacketCodecs.DOUBLE.encode(buf, value.outboundSpeedRaw);
            PacketCodecs.VAR_INT.encode(buf, value.dictSize);
            PacketCodecs.VAR_INT.encode(buf, value.dictSampleCount);
            PacketCodecs.VAR_INT.encode(buf, value.dictSampleThreshold);
            PacketCodecs.VAR_LONG.encode(buf, value.chunkCacheHits);
            PacketCodecs.VAR_LONG.encode(buf, value.chunkCacheMisses);
            PacketCodecs.VAR_LONG.encode(buf, value.chunkCacheSavedBytes);
            PacketCodecs.VAR_LONG.encode(buf, value.nicInboundSpeed);
            PacketCodecs.VAR_LONG.encode(buf, value.nicOutboundSpeed);
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }
}
