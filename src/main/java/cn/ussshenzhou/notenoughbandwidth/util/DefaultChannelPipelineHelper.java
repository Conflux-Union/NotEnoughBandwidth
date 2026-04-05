package cn.ussshenzhou.notenoughbandwidth.util;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.DecoderHandler;
import net.minecraft.network.PacketEncoder;

import org.jetbrains.annotations.Nullable;
import java.util.Map;

/**
 * Locates PacketEncoder / DecoderHandler in the Netty pipeline.
 * Uses the public ChannelPipeline iterator instead of fragile reflection
 * into DefaultChannelPipeline internals.
 */
public class DefaultChannelPipelineHelper {

    @Nullable
    public static PacketEncoder getPacketEncoder(ChannelPipeline pipeline) {
        for (Map.Entry<String, ChannelHandler> entry : pipeline) {
            if (entry.getValue() instanceof PacketEncoder encoder) {
                return encoder;
            }
        }
        return null;
    }

    @Nullable
    public static DecoderHandler getPacketDecoder(ChannelPipeline pipeline) {
        for (Map.Entry<String, ChannelHandler> entry : pipeline) {
            if (entry.getValue() instanceof DecoderHandler decoder) {
                return decoder;
            }
        }
        return null;
    }
}
