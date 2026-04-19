package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record StatQueryPayload() implements CustomPacketPayload {
    public static final Type<StatQueryPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "stat_query"));

    public static final StreamCodec<ByteBuf, StatQueryPayload> CODEC =
            StreamCodec.unit(new StatQueryPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
