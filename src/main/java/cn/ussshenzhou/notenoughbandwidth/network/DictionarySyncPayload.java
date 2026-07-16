package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.FriendlyByteBuf;
//#if MC>=12005
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#endif
import net.minecraft.resources.Identifier;

/**
 * Sent from server to client during handshake, before IndexSyncPayload.
 * Carries the trained Zstd dictionary bytes so both sides compress with the same dictionary.
 * Empty dictionary (length 0) means no dictionary is available.
 */
//#if MC>=12005
public record DictionarySyncPayload(byte[] dictionary) implements CustomPacketPayload {
    public static final Type<DictionarySyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "dictionary_sync"));

    public static final StreamCodec<FriendlyByteBuf, DictionarySyncPayload> CODEC =
            StreamCodec.ofMember(DictionarySyncPayload::write, DictionarySyncPayload::read);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
//#else
//$$ public record DictionarySyncPayload(byte[] dictionary) {
//$$     public static final ResourceLocation CHANNEL = new ResourceLocation(ModConstants.NETWORK_NAMESPACE, "dictionary_sync");
//#endif

    private static final int MAX_DICT_SIZE = 256 * 1024;

    public void write(FriendlyByteBuf buf) {
        if (dictionary != null && dictionary.length > 0) {
            buf.writeVarInt(dictionary.length);
            buf.writeBytes(dictionary);
        } else {
            buf.writeVarInt(0);
        }
    }

    public static DictionarySyncPayload read(FriendlyByteBuf buf) {
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
