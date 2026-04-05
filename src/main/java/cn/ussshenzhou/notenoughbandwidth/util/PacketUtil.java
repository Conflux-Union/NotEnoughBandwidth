package cn.ussshenzhou.notenoughbandwidth.util;

import cn.ussshenzhou.notenoughbandwidth.indextype.NamespaceIndexManager;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.util.Identifier;

public class PacketUtil {
    public static Identifier getTrueType(Packet<?> packet) {
        if (packet instanceof CustomPayloadC2SPacket p) {
            return p.getChannel();
        } else if (packet instanceof CustomPayloadS2CPacket p) {
            return p.getChannel();
        } else {
            return NamespaceIndexManager.getVanillaIdentifier(packet.getClass());
        }
    }

    public static Object getTruePacket(Packet<?> packet) {
        if (packet instanceof CustomPayloadC2SPacket p) {
            return p;
        } else if (packet instanceof CustomPayloadS2CPacket p) {
            return p;
        } else {
            return packet;
        }
    }
}
