package cn.ussshenzhou.notenoughbandwidth.network;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.minecraft.network.FriendlyByteBuf;
//#if MC>=12005
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//#endif
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Sent from server to client during PLAY phase initialization, before IndexSyncPayload.
 * Carries the server's compile-time vanilla path list (NamespaceIndexManager.vanillaPaths())
 * so the client builds its vanilla-packet index table from the server's list instead of its
 * own, keeping the positional path table consistent even when server and client builds
 * disagree on that hardcoded list.
 * <p>
 * MC>=12005 only: on 1.20.1 the vanilla table is enumerated from
 * {@code ConnectionProtocol.PLAY} at runtime instead of from a hardcoded list, so this
 * channel is never registered or sent there — the {@code //$$} stub below only exists to
 * keep the file compiling on that branch (and to give COMMON_BLOCK_LIST a CHANNEL constant
 * to reference, matching its neighbors).
 * <p>
 * Kept as its own payload (rather than a field on IndexSyncPayload) because an old client's
 * generated codec for IndexSyncPayload reads exactly its known fields and then expects zero
 * readable bytes left; vanilla's PacketDecoder disconnects the connection if any are left
 * over. A separate, unknown channel is silently discarded by an old client instead (see
 * IndexSyncHandler for the send/receive wiring and why that discard is safe).
 */
//#if MC>=12005
public record VanillaPathsPayload(List<String> paths) implements CustomPacketPayload {
    public static final Type<VanillaPathsPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModConstants.NETWORK_NAMESPACE, "vanilla_paths"));

    public static final StreamCodec<FriendlyByteBuf, VanillaPathsPayload> CODEC =
            StreamCodec.ofMember(VanillaPathsPayload::write, VanillaPathsPayload::read);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
//#else
//$$ public record VanillaPathsPayload(List<String> paths) {
//$$     public static final ResourceLocation CHANNEL = new ResourceLocation(ModConstants.NETWORK_NAMESPACE, "vanilla_paths");
//#endif

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(paths.size());
        for (String path : paths) {
            buf.writeUtf(path);
        }
    }

    public static VanillaPathsPayload read(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        var list = new ArrayList<String>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readUtf());
        }
        return new VanillaPathsPayload(list);
    }
}
