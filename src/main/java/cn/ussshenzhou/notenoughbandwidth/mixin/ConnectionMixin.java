package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
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
        if (this.getAddress() instanceof LocalAddress
                || this.packetListener == null
                || this.packetListener.getPhase() != NetworkPhase.PLAY
                || !NamespaceIndexManager.ready()) {
            return;
        }
        // Vanilla client: no NEB ack was received, fall through to vanilla send path.
        if (!NebConnectionRegistry.isEnabled((ClientConnection) (Object) this)) {
            return;
        }
        if (NotEnoughBandwidthConfig.skipType(PacketUtil.getTrueType(packet).toString())) {
            AggregationManager.flushConnection((ClientConnection) (Object) this);
            return;
        }
        if (packet instanceof BundlePacket<?> bundlePacket) {
            bundlePacket.getPackets().forEach(p -> this.send(p, callbacks, flush));
            ci.cancel();
            return;
        }
        AggregationManager.takeOver(packet, (ClientConnection) (Object) this);
        ci.cancel();
    }
}
