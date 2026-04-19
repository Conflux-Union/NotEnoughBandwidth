package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.local.LocalAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.SocketAddress;

@Mixin(value = Connection.class, priority = 1)
public abstract class ConnectionMixin {

    @Shadow
    @Nullable
    private volatile PacketListener packetListener;

    @Shadow
    public abstract void send(Packet<?> packet, @Nullable ChannelFutureListener callbacks, boolean flush);

    @Shadow
    public abstract SocketAddress getRemoteAddress();

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void nebPacketAggregate(Packet<?> packet, @Nullable ChannelFutureListener callbacks,
                                    boolean flush, CallbackInfo ci) {
        // Capture volatile field once to avoid TOCTOU null-pointer race.
        var listener = this.packetListener;
        if (this.getRemoteAddress() instanceof LocalAddress
                || listener == null
                || listener.protocol() != ConnectionProtocol.PLAY
                || !NamespaceIndexManager.ready()) {
            return;
        }
        // Vanilla client: no NEB ack was received, fall through to vanilla send path.
        if (!NebConnectionRegistry.isEnabled((Connection) (Object) this)) {
            return;
        }
        // Never aggregate the aggregation wrapper itself — would cause recursive nesting.
        if (PacketAggregationPacket.TYPE.id().equals(PacketUtil.getTrueType(packet))) {
            return;
        }
        // Packets with callbacks (disconnect, resource pack ack, etc.) must go through
        // the vanilla path so callbacks fire correctly. Flush first to preserve ordering.
        if (callbacks != null || NotEnoughBandwidthConfig.skipType(PacketUtil.getTrueType(packet).toString())) {
            AggregationManager.flushConnectionSync((Connection) (Object) this);
            return;
        }
        if (packet instanceof BundlePacket<?> bundlePacket) {
            var subPackets = new java.util.ArrayList<Packet<?>>();
            bundlePacket.subPackets().forEach(subPackets::add);
            for (int i = 0; i < subPackets.size(); i++) {
                // Only attach the original callbacks to the last sub-packet so they fire once.
                this.send(subPackets.get(i), i == subPackets.size() - 1 ? callbacks : null, flush);
            }
            ci.cancel();
            return;
        }
        AggregationManager.takeOver(packet, (Connection) (Object) this);
        ci.cancel();
    }
}
