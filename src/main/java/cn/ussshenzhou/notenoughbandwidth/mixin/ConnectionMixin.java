package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.aggregation.AggregationManager;
import cn.ussshenzhou.notenoughbandwidth.aggregation.PacketAggregationPacket;
import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.util.PacketUtil;
import io.netty.channel.local.LocalAddress;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.BundlePacket;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
    public abstract void send(Packet<?> packet, @Nullable PacketCallbacks callbacks);

    @Shadow
    public abstract SocketAddress getAddress();

    /**
     * Check if the listener is in PLAY phase without loading client-only classes on the server.
     * ServerPlayNetworkHandler is safe to reference on both sides; the client handler
     * is checked by class name to avoid a server-side class-loading crash.
     */
    @Unique
    private static boolean isPlayPhase(PacketListener listener) {
        if (listener instanceof ServerPlayNetworkHandler) return true;
        return listener.getClass().getName().equals("net.minecraft.client.network.ClientPlayNetworkHandler");
    }

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/PacketCallbacks;)V",
            at = @At("HEAD"), cancellable = true)
    private void nebPacketAggregate(Packet<?> packet, @Nullable PacketCallbacks callbacks,
                                    CallbackInfo ci) {
        var listener = this.packetListener;
        if (this.getAddress() instanceof LocalAddress
                || listener == null
                || !isPlayPhase(listener)
                || !NamespaceIndexManager.ready()) {
            return;
        }
        if (!NebConnectionRegistry.isEnabled((ClientConnection) (Object) this)) {
            return;
        }
        if (PacketAggregationPacket.CHANNEL.equals(PacketUtil.getTrueType(packet))) {
            return;
        }
        if (callbacks != null || NotEnoughBandwidthConfig.skipType(PacketUtil.getTrueType(packet).toString())) {
            AggregationManager.flushConnectionSync((ClientConnection) (Object) this);
            return;
        }
        if (packet instanceof BundlePacket<?> bundlePacket) {
            var subPackets = new java.util.ArrayList<Packet<?>>();
            bundlePacket.getPackets().forEach(subPackets::add);
            for (int i = 0; i < subPackets.size(); i++) {
                this.send(subPackets.get(i), i == subPackets.size() - 1 ? callbacks : null);
            }
            ci.cancel();
            return;
        }
        AggregationManager.takeOver(packet, (ClientConnection) (Object) this);
        ci.cancel();
    }
}
