package cn.ussshenzhou.notenoughbandwidth.mixin;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Targets the anonymous codec class inside CustomPayload.
 * <p>
 * On Fabric, index sync happens during PLAY phase (not CONFIGURATION like NeoForge),
 * so we cannot safely use indexed encoding at the outer CustomPayload level —
 * the server may encode with indexed headers before the client has received the sync.
 * <p>
 * Indexed encoding is still used inside aggregation blobs via CustomPacketPrefixHelper
 * (called directly by PacketAggregationPacket), which is only active after both sides
 * have completed the index sync handshake.
 * <p>
 * These redirects are kept as pass-throughs so the mixin target stays valid
 * and can be re-enabled if index sync moves to CONFIGURATION phase in the future.
 */
@Mixin(targets = "net.minecraft.network.packet.CustomPayload$1")
public class CustomPacketPayloadMixin {

    @Redirect(method = "encode(Lnet/minecraft/network/PacketByteBuf;Lnet/minecraft/network/packet/CustomPayload$Id;Lnet/minecraft/network/packet/CustomPayload;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/PacketByteBuf;writeIdentifier(Lnet/minecraft/util/Identifier;)Lnet/minecraft/network/PacketByteBuf;"))
    private PacketByteBuf nebIndexedHeaderEncode(PacketByteBuf buf, Identifier identifier) {
        buf.writeIdentifier(identifier);
        return buf;
    }

    @Redirect(method = "decode(Lnet/minecraft/network/PacketByteBuf;)Lnet/minecraft/network/packet/CustomPayload;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/PacketByteBuf;readIdentifier()Lnet/minecraft/util/Identifier;"))
    private Identifier nebIndexedHeaderDecode(PacketByteBuf buf) {
        return buf.readIdentifier();
    }
}
