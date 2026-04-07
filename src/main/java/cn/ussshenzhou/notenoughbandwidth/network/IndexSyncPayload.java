package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
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
public record IndexSyncPayload(List<Identifier> types, String serverId) implements CustomPayload {
    public static final Id<IndexSyncPayload> TYPE =
            new Id<>(Identifier.of(ModConstants.NETWORK_NAMESPACE, "index_sync"));

    public static final PacketCodec<PacketByteBuf, IndexSyncPayload> CODEC =
            PacketCodec.of(IndexSyncPayload::write, IndexSyncPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(types.size());
        for (Identifier id : types) {
            buf.writeIdentifier(id);
        }
        buf.writeString(serverId);
    }

    private static IndexSyncPayload read(PacketByteBuf buf) {
        int size = buf.readVarInt();
        var list = new ArrayList<Identifier>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readIdentifier());
        }
        String serverId = buf.isReadable() ? buf.readString() : "";
        return new IndexSyncPayload(list, serverId);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }
}
