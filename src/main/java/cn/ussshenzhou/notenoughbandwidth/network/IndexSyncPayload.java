package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Sent from server to client during PLAY phase initialization.
 * Contains the sorted list of all registered custom payload Identifiers
 * so both sides build the same index table for packet header compression.
 * Also carries the server's persistent UUID for chunk cache namespacing
 * (critical behind proxies like Velocity where the client address is always the proxy).
 */
public record IndexSyncPayload(List<Identifier> types, String serverId) implements CustomPacketPayload {
    public static final Type<IndexSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "index_sync"));

    public static final StreamCodec<FriendlyByteBuf, IndexSyncPayload> CODEC =
            StreamCodec.ofMember(IndexSyncPayload::write, IndexSyncPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(types.size());
        for (Identifier id : types) {
            buf.writeIdentifier(id);
        }
        buf.writeUtf(serverId);
    }

    private static IndexSyncPayload read(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        var list = new ArrayList<Identifier>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readIdentifier());
        }
        String serverId = buf.isReadable() ? buf.readUtf() : "";
        return new IndexSyncPayload(list, serverId);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
