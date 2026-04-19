package cn.ussshenzhou.notenoughbandwidth.util;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultChannelPipeline;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.PacketDecoder;

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
    public static PacketEncoder<?> getPacketEncoder(DefaultChannelPipeline pipeline) {
        try {
            Object head = HEAD.get(pipeline);
            Object tail = TAIL.get(pipeline);
            var ctx = (ChannelHandlerContext) NEXT.get(head);
            if (ctx == null) return null;
            while (ctx != null && ctx != tail) {
                if (ctx.handler() instanceof PacketEncoder<?> encoder) {
                    return encoder;
                }
                ctx = (ChannelHandlerContext) NEXT.get(ctx);
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    @Nullable
    public static PacketDecoder<?> getPacketDecoder(DefaultChannelPipeline pipeline) {
        try {
            Object head = HEAD.get(pipeline);
            Object tail = TAIL.get(pipeline);
            var ctx = (ChannelHandlerContext) NEXT.get(head);
            while (ctx != null && ctx != tail) {
                if (ctx.handler() instanceof PacketDecoder<?> decoder) {
                    return decoder;
                }
                ctx = (ChannelHandlerContext) NEXT.get(ctx);
            }
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
