package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.indextype.CustomPacketPrefixHelper;
import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Targets the anonymous codec class inside CustomPayload.
 * <p>
 * After type erasure, generic parameter B becomes PacketByteBuf.
 * Actual descriptors:
 * - encode: (Lnet/minecraft/network/PacketByteBuf;Lnet/minecraft/network/packet/CustomPayload$Id;Lnet/minecraft/network/packet/CustomPayload;)V
 * - decode: (Lnet/minecraft/network/PacketByteBuf;)Lnet/minecraft/network/packet/CustomPayload;
 */
@Mixin(targets = "net.minecraft.network.packet.CustomPayload$1")
public class CustomPacketPayloadMixin {

    @Redirect(method = "encode(Lnet/minecraft/network/PacketByteBuf;Lnet/minecraft/network/packet/CustomPayload$Id;Lnet/minecraft/network/packet/CustomPayload;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/PacketByteBuf;writeIdentifier(Lnet/minecraft/util/Identifier;)Lnet/minecraft/network/PacketByteBuf;"))
    private PacketByteBuf nebIndexedHeaderEncode(PacketByteBuf buf, Identifier identifier) {
        if (!NamespaceIndexManager.ready()) {
            buf.writeIdentifier(identifier);
            return buf;
        }
        if (NotEnoughBandwidthConfig.skipType(identifier.toString())) {
            buf.writeByte(0);
            buf.writeIdentifier(identifier);
            return buf;
        }
        CustomPacketPrefixHelper.write(identifier, buf);
        return buf;
    }

    @Redirect(method = "decode(Lnet/minecraft/network/PacketByteBuf;)Lnet/minecraft/network/packet/CustomPayload;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/PacketByteBuf;readIdentifier()Lnet/minecraft/util/Identifier;"))
    private Identifier nebIndexedHeaderDecode(PacketByteBuf buf) {
        if (!NamespaceIndexManager.ready()) {
            return buf.readIdentifier();
        }
        return CustomPacketPrefixHelper.read(buf);
    }
}
