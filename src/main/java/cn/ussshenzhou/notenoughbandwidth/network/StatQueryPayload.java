package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record StatQueryPayload() implements CustomPayload {
    public static final Id<StatQueryPayload> TYPE =
            new Id<>(Identifier.of(ModConstants.MOD_ID, "stat_query"));

    public static final PacketCodec<ByteBuf, StatQueryPayload> CODEC =
            PacketCodec.unit(new StatQueryPayload());

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }
}
