package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Sent from server to client during PLAY phase initialization.
 * Contains the sorted list of all registered custom payload Identifiers
 * so both sides build the same index table for packet header compression.
 * Also carries the server's persistent UUID for chunk cache namespacing
 * (critical behind proxies like Velocity where the client address is always the proxy).
 */
public record IndexSyncPayload(List<Identifier> types, String serverId) {
    public static final Identifier CHANNEL = new Identifier("neb", "index_sync");

    public void write(PacketByteBuf buf) {
        buf.writeVarInt(types.size());
        for (Identifier id : types) {
            buf.writeIdentifier(id);
        }
        buf.writeString(serverId);
    }

    public static IndexSyncPayload read(PacketByteBuf buf) {
        int size = buf.readVarInt();
        var list = new ArrayList<Identifier>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readIdentifier());
        }
        String serverId = buf.isReadable() ? buf.readString() : "";
        return new IndexSyncPayload(list, serverId);
    }
}
