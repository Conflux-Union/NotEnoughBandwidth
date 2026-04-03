package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Sent from server to client during handshake, before IndexSyncPayload.
 * Carries the trained Zstd dictionary bytes so both sides compress with the same dictionary.
 * Empty dictionary (length 0) means no dictionary is available.
 */
public record DictionarySyncPayload(byte[] dictionary) implements CustomPayload {
    public static final Id<DictionarySyncPayload> TYPE =
            new Id<>(Identifier.of(ModConstants.MOD_ID, "dictionary_sync"));

    public static final PacketCodec<PacketByteBuf, DictionarySyncPayload> CODEC =
            PacketCodec.of(DictionarySyncPayload::write, DictionarySyncPayload::read);

    private void write(PacketByteBuf buf) {
        if (dictionary != null && dictionary.length > 0) {
            buf.writeVarInt(dictionary.length);
            buf.writeBytes(dictionary);
        } else {
            buf.writeVarInt(0);
        }
    }

    private static final int MAX_DICT_SIZE = 256 * 1024;

    private static DictionarySyncPayload read(PacketByteBuf buf) {
        int length = buf.readVarInt();
        if (length > MAX_DICT_SIZE) {
            throw new IllegalArgumentException("Dictionary too large: " + length + " bytes (max " + MAX_DICT_SIZE + ")");
        }
        if (length > 0) {
            byte[] dict = new byte[length];
            buf.readBytes(dict);
            return new DictionarySyncPayload(dict);
        }
        return new DictionarySyncPayload(new byte[0]);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return TYPE;
    }
}
