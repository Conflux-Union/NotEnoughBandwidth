package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.stat.PacketTypeStatManager;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketDecoder;
//#if MC>=12005
import net.minecraft.network.ProtocolInfo;
//#else
//$$ import net.minecraft.network.protocol.PacketFlow;
//#endif
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(PacketDecoder.class)
public class PacketDecoderMixin {

    //#if MC>=12005
    @Shadow @Final private ProtocolInfo<?> protocolInfo;
    //#else
    //$$ @Shadow @Final private PacketFlow flow;
    //#endif

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
        // get(size-1) instead of getLast(): 1.20.1 compiles with --release 17,
        // which predates SequencedCollection.
        var last = out.get(out.size() - 1);
        if (last instanceof Packet<?> packet) {
            int consumed = neb$capturedSize - input.readableBytes();
            SimpleStatManager.inBaked(consumed);
            //#if MC>=12005
            if (PacketUtil.getTruePacket(packet) instanceof PacketAggregationPacket aggregationPacket) {
                aggregationPacket.setBakedSize(consumed);
            } else {
                SimpleStatManager.inRaw(consumed);
                Identifier type = PacketUtil.getTrueType(packet);
                PacketTypeStatManager.record(protocolInfo.flow(), type, consumed, consumed);
            }
            //#else
            //$$ ResourceLocation channel = PacketUtil.getTrueType(packet);
            //$$ if (!PacketAggregationPacket.CHANNEL.equals(channel)) {
            //$$     // inRaw for aggregation packets is recorded inside handle()
            //$$     // after decompression; for everything else, raw == baked.
            //$$     SimpleStatManager.inRaw(consumed);
            //$$     PacketTypeStatManager.record(flow, channel, consumed, consumed);
            //$$ }
            //#endif
        }
    }
}
