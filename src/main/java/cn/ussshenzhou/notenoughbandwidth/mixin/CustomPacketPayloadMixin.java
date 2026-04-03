package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.indextype.CustomPacketPrefixHelper;
import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
import net.minecraft.network.NetworkState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Targets the anonymous codec class inside CustomPayload that handles
 * writing/reading the payload type Identifier.
 * <p>
 * The anonymous class number ($1) must be verified against 1.21.4 decompiled source.
 * If it changes, update the target accordingly.
 */
@Mixin(targets = "net.minecraft.network.packet.CustomPayload$1")
public class CustomPacketPayloadMixin {

    // TODO: Verify this field name in 1.21.4 yarn mappings.
    // In NeoForge 26.1 this was val$protocol; in Fabric it may be field_XXXX or similar.
    // This needs to be checked against the decompiled anonymous class.
    // For now, we'll use a simpler approach: check the protocol at runtime.

    @Redirect(method = "writeCap",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/PacketByteBuf;writeIdentifier(Lnet/minecraft/util/Identifier;)Lnet/minecraft/network/PacketByteBuf;"),
            require = 0)
    private PacketByteBuf nebIndexedHeaderEncode(PacketByteBuf buf, Identifier identifier) {
        // Only index in PLAY phase
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

    @Redirect(method = "decode",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/network/PacketByteBuf;readIdentifier()Lnet/minecraft/util/Identifier;"),
            require = 0)
    private Identifier nebIndexedHeaderDecode(PacketByteBuf buf) {
        if (!NamespaceIndexManager.ready()) {
            return buf.readIdentifier();
        }
        return CustomPacketPrefixHelper.read(buf);
    }
}
