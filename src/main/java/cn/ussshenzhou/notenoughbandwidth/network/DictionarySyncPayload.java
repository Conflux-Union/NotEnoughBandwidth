package cn.ussshenzhou.notenoughbandwidth.network;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/**
 * Sent from server to client during handshake, before IndexSyncPayload.
 * Carries the trained Zstd dictionary bytes so both sides compress with the same dictionary.
 * Empty dictionary (length 0) means no dictionary is available.
 */
public record DictionarySyncPayload(byte[] dictionary) {
    public static final Identifier CHANNEL = new Identifier("neb", "dictionary_sync");

    private static final int MAX_DICT_SIZE = 256 * 1024;

    public void write(PacketByteBuf buf) {
        if (dictionary != null && dictionary.length > 0) {
            buf.writeVarInt(dictionary.length);
            buf.writeBytes(dictionary);
        } else {
            buf.writeVarInt(0);
        }
    }

    public static DictionarySyncPayload read(PacketByteBuf buf) {
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
}
