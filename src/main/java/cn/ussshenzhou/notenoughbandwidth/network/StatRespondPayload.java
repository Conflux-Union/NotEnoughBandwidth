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
        double outboundSpeedRaw
) implements CustomPayload {
    public static final Id<StatRespondPayload> TYPE =
            new Id<>(Identifier.of(ModConstants.MOD_ID, "stat_resp"));

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
                    PacketCodecs.DOUBLE.decode(buf)
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
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }
}
