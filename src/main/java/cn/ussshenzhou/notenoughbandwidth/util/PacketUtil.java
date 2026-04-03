package cn.ussshenzhou.notenoughbandwidth.util;

import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.util.Identifier;

public class PacketUtil {
    public static Identifier getTrueType(Packet<?> packet) {
        if (packet instanceof CustomPayloadC2SPacket p) {
            return p.payload().getId().id();
        } else if (packet instanceof CustomPayloadS2CPacket p) {
            return p.payload().getId().id();
        } else {
            return packet.getPacketType().id();
        }
    }

    public static Object getTruePacket(Packet<?> packet) {
        if (packet instanceof CustomPayloadC2SPacket p) {
            return p.payload();
        } else if (packet instanceof CustomPayloadS2CPacket p) {
            return p.payload();
        } else {
            return packet;
        }
    }
}
