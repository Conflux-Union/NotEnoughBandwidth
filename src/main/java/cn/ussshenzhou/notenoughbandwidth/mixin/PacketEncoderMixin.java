package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.stat.PacketTypeStatManager;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketEncoder.class)
public class PacketEncoderMixin {

    //#if MC>=12005
    @Shadow @Final private ProtocolInfo<?> protocolInfo;

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("TAIL"))
    private void nebRecordOut(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
        int size = output.readableBytes();
        SimpleStatManager.outBaked(size);
        if (PacketUtil.getTruePacket(packet) instanceof PacketAggregationPacket aggregationPacket) {
            SimpleStatManager.outRaw(size - aggregationPacket.getBakedSize());
        } else {
            SimpleStatManager.outRaw(size);
            Identifier type = PacketUtil.getTrueType(packet);
            PacketTypeStatManager.record(protocolInfo.flow(), type, size, size);
        }
    }
    //#else
    //$$ @Shadow @Final private PacketFlow flow;
    //$$
    //$$ @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V",
    //$$         at = @At("TAIL"))
    //$$ private void nebRecordOut(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
    //$$     int size = output.readableBytes();
    //$$     SimpleStatManager.outBaked(size);
    //$$     ResourceLocation channel = PacketUtil.getTrueType(packet);
    //$$     if (PacketAggregationPacket.CHANNEL.equals(channel)) {
    //$$         // Read baked size from the channel attribute (set by write() on the flush thread).
    //$$         Integer bakedSize = ctx.channel().attr(PacketAggregationPacket.BAKED_SIZE_KEY).getAndSet(null);
    //$$         if (bakedSize != null && bakedSize >= 0) {
    //$$             SimpleStatManager.outRaw(size - bakedSize);
    //$$         }
    //$$         // outRaw for the raw sub-packets is already recorded inside write().
    //$$     } else {
    //$$         SimpleStatManager.outRaw(size);
    //$$         PacketTypeStatManager.record(flow, channel, size, size);
    //$$     }
    //$$ }
    //#endif
}
