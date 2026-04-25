package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.stat.PacketTypeStatManager;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.handler.EncoderHandler;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.state.NetworkState;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EncoderHandler.class)
public abstract class PacketEncoderMixin {

    @Shadow @Final NetworkState<?> state;

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;Lio/netty/buffer/ByteBuf;)V",
            at = @At("TAIL"))
    private void nebRecordOut(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf output, CallbackInfo ci) {
        int size = output.readableBytes();
        SimpleStatManager.outBaked(size);
        if (PacketUtil.getTruePacket(packet) instanceof PacketAggregationPacket aggregationPacket) {
            SimpleStatManager.outRaw(size - aggregationPacket.getBakedSize());
            // Per-type accounting for sub-packets is handled in PacketAggregationPacket.write().
        } else {
            SimpleStatManager.outRaw(size);
            Identifier type = PacketUtil.getTrueType(packet);
            PacketTypeStatManager.record(state.side(), type, size, size);
        }
    }
}
