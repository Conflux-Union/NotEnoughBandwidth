package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketEncoder.class)
public class PacketEncoderMixin {

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("TAIL"))
    private void nebRecordOut(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
        int size = output.readableBytes();
        SimpleStatManager.outBaked(size);
        Identifier channel = PacketUtil.getTrueType(packet);
        if (PacketAggregationPacket.CHANNEL.equals(channel)) {
            int bakedSize = PacketAggregationPacket.LAST_BAKED_SIZE.get();
            PacketAggregationPacket.LAST_BAKED_SIZE.set(-1);
            if (bakedSize >= 0) {
                SimpleStatManager.outRaw(size - bakedSize);
            }
            // outRaw for the raw sub-packets is already recorded inside write()
        } else {
            SimpleStatManager.outRaw(size);
        }
    }
}
