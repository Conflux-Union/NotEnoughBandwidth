package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import io.netty.channel.local.LocalAddress;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkPhase;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.BundlePacket;
import net.minecraft.network.packet.Packet;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.SocketAddress;

@Mixin(value = ClientConnection.class, priority = 1)
public abstract class ConnectionMixin {

    @Shadow
    @Nullable
    private volatile PacketListener packetListener;

    @Shadow
    public abstract void send(Packet<?> packet, @Nullable PacketCallbacks callbacks, boolean flush);

    @Shadow
    public abstract SocketAddress getAddress();

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;Z)V",
            at = @At("HEAD"), cancellable = true)
    private void nebPacketAggregate(Packet<?> packet, @Nullable PacketCallbacks callbacks,
                                    boolean flush, CallbackInfo ci) {
        // Capture volatile field once to avoid TOCTOU null-pointer race.
        var listener = this.packetListener;
        if (this.getAddress() instanceof LocalAddress
                || listener == null
                || listener.getPhase() != NetworkPhase.PLAY
                || !NamespaceIndexManager.ready()) {
            return;
        }
        // Vanilla client: no NEB ack was received, fall through to vanilla send path.
        if (!NebConnectionRegistry.isEnabled((ClientConnection) (Object) this)) {
            return;
        }
        // Never aggregate the aggregation wrapper itself — would cause recursive nesting.
        var trueType = PacketUtil.getTrueType(packet);
        if (trueType == null) {
            return;
        }
        if (PacketAggregationPacket.TYPE.id().equals(trueType)) {
            return;
        }
        // Packets with callbacks (disconnect, resource pack ack, etc.) must go through
        // the vanilla path so callbacks fire correctly. Flush first to preserve ordering.
        if (callbacks != null || NotEnoughBandwidthConfig.skipType(trueType.toString())) {
            AggregationManager.flushConnectionSync((ClientConnection) (Object) this);
            return;
        }
        if (packet instanceof BundlePacket<?> bundlePacket) {
            var subPackets = new java.util.ArrayList<Packet<?>>();
            bundlePacket.getPackets().forEach(subPackets::add);
            for (int i = 0; i < subPackets.size(); i++) {
                // Only attach the original callbacks to the last sub-packet so they fire once.
                this.send(subPackets.get(i), i == subPackets.size() - 1 ? callbacks : null, flush);
            }
            ci.cancel();
            return;
        }
        AggregationManager.takeOver(packet, (ClientConnection) (Object) this);
        ci.cancel();
    }
}
