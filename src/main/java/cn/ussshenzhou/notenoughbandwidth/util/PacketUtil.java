package cn.ussshenzhou.notenoughbandwidth.util;

import net.minecraft.network.handler.PacketCodecDispatcher;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.PacketType;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public class PacketUtil {

    @SuppressWarnings("unchecked")
    private static volatile Function<Packet<?>, PacketType<?>> vanillaPacketIdGetter;

    /**
     * Extract and cache the vanilla packet → PacketType mapping function
     * from the PLAY-phase codec. Safe to call multiple times (idempotent).
     */
    @SuppressWarnings("unchecked")
    public static void initPacketIdGetter(PacketCodecDispatcher codec) {
        if (vanillaPacketIdGetter == null) {
            vanillaPacketIdGetter = (Function<Packet<?>, PacketType<?>>) (Function<?, ?>) codec.packetIdGetter;
        }
    }

    @Nullable
    public static Identifier getTrueType(Packet<?> packet) {
        if (packet instanceof CustomPayloadC2SPacket p) {
            return p.payload().getId().id();
        } else if (packet instanceof CustomPayloadS2CPacket p) {
            return p.payload().getId().id();
        } else {
            var getter = vanillaPacketIdGetter;
            if (getter != null) {
                var type = getter.apply(packet);
                if (type != null) {
                    return type.id();
                }
            }
            return null;
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
