package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.Varint21FrameDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Raises the vanilla frame-length VarInt from 3 bytes (2,097,151 max) to 4
 * bytes (268,435,455 max) and enforces our own {@code maxPacketSize} cap on
 * top of that, so an oversized aggregate throws a clean EncoderException
 * instead of exceeding the frame limit and getting the connection killed.
 * <p>
 * Not applied on 1.20.1 (see {@link NebMixinPlugin}) — that version's
 * Varint21FrameDecoder has a different shape (no {@code copyVarint}, no
 * standalone {@code VarInt} class) and its aggregation path already keeps
 * blobs under the vanilla limit via sendBatched, so no gate is needed there.
 * <p>
 * Ported from upstream commit 39b076c, but rewritten against plain
 * SpongePowered Mixin: our Fabric loaders bundle MixinExtras 0.4.x, and
 * upstream's version needs 0.5.3+ for {@code @Expression}/{@code @Local}.
 * The varint read/size algorithms below are reimplemented locally (rather
 * than calling {@code net.minecraft.network.VarInt}) so this file still
 * compiles on 1.20.1, where that class does not exist.
 */
@Mixin(Varint21FrameDecoder.class)
public class Varint21FrameDecoderMixin {

    @ModifyConstant(method = "<init>", constant = @Constant(intValue = 3))
    private int nebAllowBiggerPacket0(int constant) {
        return 4;
    }

    @ModifyConstant(method = "copyVarint", constant = @Constant(intValue = 3))
    private static int nebAllowBiggerPacket1(int constant) {
        return 4;
    }

    @Redirect(method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/network/VarInt;read(Lio/netty/buffer/ByteBuf;)I"))
    private int nebCheckPacketSize(ByteBuf buf) {
        int length = nebReadVarInt(buf);
        int maxSize = NotEnoughBandwidthConfig.get().getMaxPacketSize();
        if (length > maxSize) {
            throw new EncoderException("NEB: Packet too large: size " + length + " is over " + maxSize);
        }
        return length;
    }

    /** Mirrors net.minecraft.network.VarInt#read without depending on that class. */
    private static int nebReadVarInt(ByteBuf buf) {
        int value = 0;
        int size = 0;
        byte b;
        do {
            b = buf.readByte();
            value |= (b & 0x7F) << (size++ * 7);
            if (size > 5) {
                throw new RuntimeException("VarInt too big");
            }
        } while ((b & 0x80) == 0x80);
        return value;
    }
}
