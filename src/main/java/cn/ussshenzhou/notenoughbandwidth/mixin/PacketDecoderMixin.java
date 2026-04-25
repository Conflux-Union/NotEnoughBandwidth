package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.stat.PacketTypeStatManager;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.DecoderHandler;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(DecoderHandler.class)
public abstract class PacketDecoderMixin {

    @Shadow @Final private NetworkSide side;

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
        var last = out.get(out.size() - 1);
        if (last instanceof Packet<?> packet) {
            int consumed = neb$capturedSize - input.readableBytes();
            SimpleStatManager.inBaked(consumed);
            Identifier channel = PacketUtil.getTrueType(packet);
            if (!PacketAggregationPacket.CHANNEL.equals(channel)) {
                // inRaw for aggregation packets is recorded inside handle()
                // after decompression; for everything else, raw == baked.
                SimpleStatManager.inRaw(consumed);
                PacketTypeStatManager.record(side, channel, consumed, consumed);
            }
        }
    }
}
