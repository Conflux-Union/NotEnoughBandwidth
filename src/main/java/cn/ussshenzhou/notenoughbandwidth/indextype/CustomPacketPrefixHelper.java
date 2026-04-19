package cn.ussshenzhou.notenoughbandwidth.indextype;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.Identifier;

import org.jetbrains.annotations.Nullable;

public class CustomPacketPrefixHelper {

    public static void write(Identifier type, FriendlyByteBuf buf) {
        if (NamespaceIndexManager.contains(type)) {
            var index = NamespaceIndexManager.getCheckedIndex(type);
            buf.writeVarInt(index.getA());
            buf.writeVarInt(index.getB());
        } else {
            buf.writeByte(0);
            buf.writeIdentifier(type);
        }
    }

    @Nullable
    public static Identifier read(FriendlyByteBuf buf) {
        byte firstByte = buf.getByte(buf.readerIndex());
        if (firstByte == 0) {
            buf.readVarInt();
            return buf.readIdentifier();
        } else {
            return NamespaceIndexManager.getIdentifier(buf.readVarInt(), buf.readVarInt());
        }
    }
}
