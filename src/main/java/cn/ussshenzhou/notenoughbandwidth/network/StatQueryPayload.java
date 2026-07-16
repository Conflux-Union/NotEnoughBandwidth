package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.FriendlyByteBuf;
//#if MC>=12005
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#endif
import net.minecraft.resources.Identifier;

//#if MC>=12005
public record StatQueryPayload() implements CustomPacketPayload {
    public static final Type<StatQueryPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "stat_query"));

    public static final StreamCodec<ByteBuf, StatQueryPayload> CODEC =
            StreamCodec.unit(new StatQueryPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
//#else
//$$ public record StatQueryPayload() {
//$$     public static final ResourceLocation CHANNEL = new ResourceLocation(ModConstants.NETWORK_NAMESPACE, "stat_query");
//#endif

    public void write(FriendlyByteBuf buf) {
        // no-op: marker packet with no payload
    }

    public static StatQueryPayload read(FriendlyByteBuf buf) {
        return new StatQueryPayload();
    }
}
