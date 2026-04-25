package cn.ussshenzhou.notenoughbandwidth.bench;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts bytes (and packet frames) flowing outbound through the Netty pipeline
 * at the position it is installed. Install at pipeline "first" (head) to measure
 * post-compression wire bytes — outbound traversal runs tail-to-head, so the
 * head handler is the last to see the data before it hits the socket.
 */
public final class WireByteCounter extends ChannelOutboundHandlerAdapter {
    public static final String HANDLER_NAME = "neb-bench-wire-counter";

    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong packets = new AtomicLong();

    public long bytes() {
        return bytes.get();
    }

    public long packets() {
        return packets.get();
    }

    public void reset() {
        bytes.set(0);
        packets.set(0);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf buf) {
            bytes.addAndGet(buf.readableBytes());
            packets.incrementAndGet();
        }
        super.write(ctx, msg, promise);
    }
}
