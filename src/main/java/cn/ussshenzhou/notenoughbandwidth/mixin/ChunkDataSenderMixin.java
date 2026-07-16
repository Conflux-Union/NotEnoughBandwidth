package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkHashUtil;
import cn.ussshenzhou.notenoughbandwidth.network.ChunkHashPayload;
import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import net.minecraft.network.Connection;
//#if MC>=12002
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
//#else
//$$ import cn.ussshenzhou.notenoughbandwidth.chunkcache.PendingChunkQueue;
//$$ import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
//$$ import net.minecraft.network.FriendlyByteBuf;
//$$ import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
//$$ import net.minecraft.server.level.ChunkMap;
//$$ import net.minecraft.server.level.ServerPlayer;
//$$ import org.apache.commons.lang3.mutable.MutableObject;
//$$ import org.spongepowered.asm.mixin.Final;
//$$ import org.spongepowered.asm.mixin.Shadow;
//#endif
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.BitSet;

//#if MC>=12002
@Mixin(PlayerChunkSender.class)
//#else
//$$ @Mixin(ChunkMap.class)
//#endif
public class ChunkDataSenderMixin {

    //#if MC>=12002
    /**
     * Intercepts the moment a chunk data packet would be sent to a player.
     * When the client has uploaded a bloom filter and it reports a likely cache hit,
     * we send a tiny ChunkHashPayload (~20 bytes) instead of the full packet (~10-20 KB).
     * The client loads from its local DB, or falls back to requesting the full data.
     */
    @Inject(method = "sendChunk",
            at = @At("HEAD"),
            cancellable = true)
    private static void nebChunkCacheIntercept(ServerGamePacketListenerImpl handler,
                                               ServerLevel world,
                                               LevelChunk chunk,
                                               CallbackInfo ci) {
        var cfg = NotEnoughBandwidthConfig.get();
        if (!cfg.chunkCacheEnabled && !cfg.lightStripEnabled) return;

        Connection connection = handler.connection;
        if (!NebConnectionRegistry.isEnabled(connection)) return;
        BitSet lightMask = cfg.lightStripEnabled ? new BitSet() : null;

        // Build the packet once. On hit we skip the vanilla path entirely;
        // on miss we send this packet ourselves instead of letting vanilla
        // construct a second identical one.
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(
                chunk, world.getLightEngine(), lightMask, lightMask);

        if (!cfg.chunkCacheEnabled) {
            handler.send(packet);
            ci.cancel();
            return;
        }

        ChunkHashUtil.Result result = ChunkHashUtil.compute(packet.getChunkData(), world.registryAccess(),
                "SERVER", chunk.getPos().x(), chunk.getPos().z());

        if (ChunkCacheManager.serverMightHaveChunk(connection, result.hash())) {
            handler.send(new ClientboundCustomPayloadPacket(
                    new ChunkHashPayload(chunk.getPos().x(), chunk.getPos().z(), result.hash())));
            SimpleStatManager.chunkCacheHits.incrementAndGet();
            SimpleStatManager.chunkCacheSavedBytes.addAndGet(result.dataBytes());
            SimpleStatManager.outRaw((int) Math.min(result.dataBytes(), Integer.MAX_VALUE));
        } else {
            SimpleStatManager.chunkCacheMisses.incrementAndGet();
            handler.send(packet);
        }
        //#if MC>=12104 && MC<12106
        //$$ // Preserve vanilla TAIL behavior: debug-mode chunk-watching chart (removed in 1.21.5).
        //$$ net.minecraft.network.protocol.game.DebugPackets.sendPoiPacketsForChunk(world, chunk.getPos());
        //#endif
        ci.cancel();
    }
    //#else
    //$$ @Shadow
    //$$ @Final
    //$$ ServerLevel level;
    //$$
    //$$ /**
    //$$  * 1.20.1: chunk sending happens in ChunkMap.playerLoadedChunk (no
    //$$  * PlayerChunkSender yet). Same PCC/light-strip logic, plus the PENDING
    //$$  * queue: before NebAck the bloom filter hasn't arrived, so chunk sends
    //$$  * are parked and replayed with PCC awareness once the manifest is in.
    //$$  */
    //$$ @Inject(method = "playerLoadedChunk",
    //$$         at = @At("HEAD"),
    //$$         cancellable = true)
    //$$ private void nebChunkCacheIntercept(ServerPlayer player,
    //$$                                     MutableObject<ClientboundLevelChunkWithLightPacket> cachedDataPacket,
    //$$                                     LevelChunk chunk,
    //$$                                     CallbackInfo ci) {
    //$$     var cfg = NotEnoughBandwidthConfig.get();
    //$$     Connection connection = player.connection.connection;
    //$$     if (!NebConnectionRegistry.isActive(connection)) return;
    //$$     if (!cfg.chunkCacheEnabled && !cfg.lightStripEnabled) return;
    //$$
    //$$     BitSet lightMask = cfg.lightStripEnabled ? new BitSet() : null;
    //$$
    //$$     // During PENDING, the bloom filter hasn't arrived yet so we can't make
    //$$     // PCC decisions. Queue the chunk send and replay it later with bloom
    //$$     // filter awareness once the client's manifest has been received.
    //$$     if (cfg.chunkCacheEnabled && NebConnectionRegistry.isPending(connection)) {
    //$$         ClientboundLevelChunkWithLightPacket pkt = cachedDataPacket.getValue();
    //$$         if (pkt == null) {
    //$$             pkt = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), lightMask, lightMask);
    //$$             cachedDataPacket.setValue(pkt);
    //$$         }
    //$$         PendingChunkQueue.enqueue(connection, player, pkt);
    //$$         ci.cancel();
    //$$         return;
    //$$     }
    //$$
    //$$     ClientboundLevelChunkWithLightPacket packet = cachedDataPacket.getValue();
    //$$     if (packet == null || cfg.lightStripEnabled) {
    //$$         // Vanilla hasn't created the packet yet — create and cache it so
    //$$         // we can compute its content hash for the bloom filter check.
    //$$         packet = new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), lightMask, lightMask);
    //$$         cachedDataPacket.setValue(packet);
    //$$     }
    //$$
    //$$     if (!cfg.chunkCacheEnabled) return;
    //$$
    //$$     ChunkHashUtil.Result result = ChunkHashUtil.compute(packet.getChunkData(), level.registryAccess(),
    //$$             "SERVER", chunk.getPos().x, chunk.getPos().z);
    //$$
    //$$     if (ChunkCacheManager.serverMightHaveChunk(connection, result.hash())) {
    //$$         FriendlyByteBuf buf = PacketByteBufs.create();
    //$$         new ChunkHashPayload(chunk.getPos().x, chunk.getPos().z, result.hash()).write(buf);
    //$$         player.connection.send(new ClientboundCustomPayloadPacket(ChunkHashPayload.CHANNEL, buf));
    //$$         SimpleStatManager.chunkCacheHits.incrementAndGet();
    //$$         SimpleStatManager.chunkCacheSavedBytes.addAndGet(result.dataBytes());
    //$$         SimpleStatManager.outRaw((int) Math.min(result.dataBytes(), Integer.MAX_VALUE));
    //$$         ci.cancel();
    //$$     } else {
    //$$         SimpleStatManager.chunkCacheMisses.incrementAndGet();
    //$$     }
    //$$ }
    //#endif
}
