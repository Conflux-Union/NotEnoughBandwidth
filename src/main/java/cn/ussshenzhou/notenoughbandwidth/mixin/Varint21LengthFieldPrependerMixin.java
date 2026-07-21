package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.Varint21LengthFieldPrepender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Encode-side counterpart to {@link Varint21FrameDecoderMixin}: raises the
 * vanilla 3-byte VarInt frame-length cap to 4 bytes and enforces our own
 * {@code maxPacketSize} on top of it, so an outbound aggregate that is too
 * large fails fast with an EncoderException instead of corrupting the frame.
 * <p>
 * Not applied on 1.20.1 — see {@link NebMixinPlugin}.
 * <p>
 * Ported from upstream commit 39b076c, rewritten against plain SpongePowered
 * Mixin (no MixinExtras — see Varint21FrameDecoderMixin for why). The varint
 * byte-size formula is reimplemented locally rather than calling
 * {@code net.minecraft.network.VarInt} so this file still compiles on
 * 1.20.1, where that class does not exist.
 */
@Mixin(Varint21LengthFieldPrepender.class)
public class Varint21LengthFieldPrependerMixin {

    @ModifyConstant(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V",
            constant = @Constant(intValue = 3))
    private int nebAllowBiggerPacket(int constant) {
        return 4;
    }

    @Redirect(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/VarInt;getByteSize(I)I"))
    private int nebCheckPacketSize(int bodyLength) {
        int maxSize = NotEnoughBandwidthConfig.get().getMaxPacketSize();
        if (bodyLength > maxSize) {
            throw new EncoderException("NEB: Packet too large: size " + bodyLength + " is over " + maxSize);
        }
        return nebGetVarIntSize(bodyLength);
    }

    /** Mirrors net.minecraft.network.VarInt#getByteSize without depending on that class. */
    private static int nebGetVarIntSize(int value) {
        for (int i = 1; i < 5; i++) {
            if ((value & (-1 << (i * 7))) == 0) {
                return i;
            }
        }
        return 5;
    }
}
