package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

public record StatQueryPayload() {
    public static final Identifier CHANNEL = new Identifier("neb", "stat_query");

    public void write(PacketByteBuf buf) {
        // no-op: marker packet with no payload
    }

    public static StatQueryPayload read(PacketByteBuf buf) {
        return new StatQueryPayload();
    }
}
