package cn.ussshenzhou.notenoughbandwidth.util;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultChannelPipeline;
import net.minecraft.network.handler.EncoderHandler;
import net.minecraft.network.handler.DecoderHandler;

import org.jetbrains.annotations.Nullable;
import java.lang.reflect.Field;

public class DefaultChannelPipelineHelper {

    private static final Field HEAD;
    private static final Field TAIL;
    private static final Field NEXT;

    static {
        try {
            HEAD = DefaultChannelPipeline.class.getDeclaredField("head");
            HEAD.setAccessible(true);
            TAIL = DefaultChannelPipeline.class.getDeclaredField("tail");
            TAIL.setAccessible(true);
            NEXT = ((Class<?>) DefaultChannelPipeline.class.getDeclaredField("head").getType().getAnnotatedSuperclass().getType()).getDeclaredField("next");
            NEXT.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    public static EncoderHandler<?> getPacketEncoder(DefaultChannelPipeline pipeline) {
        try {
            Object head = HEAD.get(pipeline);
            Object tail = TAIL.get(pipeline);
            var ctx = (ChannelHandlerContext) NEXT.get(head);
            if (ctx == null) return null;
            do {
                if (ctx.handler() instanceof EncoderHandler<?> encoder) {
                    return encoder;
                }
                ctx = (ChannelHandlerContext) NEXT.get(ctx);
            } while (ctx != tail);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Nullable
    public static DecoderHandler<?> getPacketDecoder(DefaultChannelPipeline pipeline) {
        try {
            Object head = HEAD.get(pipeline);
            Object tail = TAIL.get(pipeline);
            var ctx = (ChannelHandlerContext) NEXT.get(head);
            while (true) {
                if (ctx.handler() instanceof DecoderHandler<?> decoder) {
                    return decoder;
                }
                ctx = (ChannelHandlerContext) NEXT.get(ctx);
                if (ctx == tail) break;
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
