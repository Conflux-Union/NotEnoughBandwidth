package cn.ussshenzhou.notenoughbandwidth.indextype;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

import org.jetbrains.annotations.Nullable;

public class CustomPacketPrefixHelper {

    public static void write(Identifier type, PacketByteBuf buf) {
        if (NamespaceIndexManager.contains(type)) {
            var index = NamespaceIndexManager.getCheckedIndex(type);
            buf.writeVarInt(index.getLeft());
            buf.writeVarInt(index.getRight());
        } else {
            buf.writeByte(0);
            buf.writeIdentifier(type);
        }
    }

    @Nullable
    public static Identifier read(PacketByteBuf buf) {
        byte firstByte = buf.getByte(buf.readerIndex());
        if (firstByte == 0) {
            buf.readVarInt();
            return buf.readIdentifier();
        } else {
            return NamespaceIndexManager.getIdentifier(buf.readVarInt(), buf.readVarInt());
        }
    }
}
