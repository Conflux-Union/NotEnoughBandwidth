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
 */
public record IndexSyncPayload(List<Identifier> types) implements CustomPayload {
    public static final Id<IndexSyncPayload> TYPE =
            new Id<>(Identifier.of(ModConstants.MOD_ID, "index_sync"));

    public static final PacketCodec<PacketByteBuf, IndexSyncPayload> CODEC =
            PacketCodec.of(IndexSyncPayload::write, IndexSyncPayload::read);

    private void write(PacketByteBuf buf) {
        buf.writeVarInt(types.size());
        for (Identifier id : types) {
            buf.writeIdentifier(id);
        }
    }

    private static IndexSyncPayload read(PacketByteBuf buf) {
        int size = buf.readVarInt();
        var list = new ArrayList<Identifier>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readIdentifier());
        }
        return new IndexSyncPayload(list);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }
}
