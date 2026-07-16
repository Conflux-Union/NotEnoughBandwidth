package cn.ussshenzhou.notenoughbandwidth.util;

//#if MC>=12005
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
//#else
//$$ import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
//$$ import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
//$$ import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
//#endif
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.Identifier;

public class PacketUtil {
    public static Identifier getTrueType(Packet<?> packet) {
        //#if MC>=12005
        if (packet instanceof ServerboundCustomPayloadPacket p) {
            return p.payload().type().id();
        } else if (packet instanceof ClientboundCustomPayloadPacket p) {
            return p.payload().type().id();
        } else {
            return packet.type().id();
        }
        //#else
        //$$ if (packet instanceof ServerboundCustomPayloadPacket p) {
        //$$     return p.getIdentifier();
        //$$ } else if (packet instanceof ClientboundCustomPayloadPacket p) {
        //$$     return p.getIdentifier();
        //$$ } else {
        //$$     return NamespaceIndexManager.getVanillaIdentifier(packet.getClass());
        //$$ }
        //#endif
    }

    public static Object getTruePacket(Packet<?> packet) {
        //#if MC>=12005
        if (packet instanceof ServerboundCustomPayloadPacket p) {
            return p.payload();
        } else if (packet instanceof ClientboundCustomPayloadPacket p) {
            return p.payload();
        } else {
            return packet;
        }
        //#else
        //$$ return packet;
        //#endif
    }
}
