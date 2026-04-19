package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PacketDecoder.class)
public class PacketDecoderMixin {

    @Unique
    private int neb$capturedSize;

    @Inject(method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V",
            at = @At("HEAD"))
    private void nebCaptureSize(ChannelHandlerContext ctx, ByteBuf input, List<Object> out, CallbackInfo ci) {
        neb$capturedSize = input.readableBytes();
    }

    @Inject(method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V",
            at = @At("TAIL"))
    private void nebRecordIn(ChannelHandlerContext ctx, ByteBuf input, List<Object> out, CallbackInfo ci) {
        if (out.isEmpty()) return;
        var last = out.getLast();
        if (last instanceof Packet<?> packet) {
            int consumed = neb$capturedSize - input.readableBytes();
            SimpleStatManager.inBaked(consumed);
            if (PacketUtil.getTruePacket(packet) instanceof PacketAggregationPacket aggregationPacket) {
                aggregationPacket.setBakedSize(consumed);
            } else {
                SimpleStatManager.inRaw(consumed);
            }
        }
    }
}
