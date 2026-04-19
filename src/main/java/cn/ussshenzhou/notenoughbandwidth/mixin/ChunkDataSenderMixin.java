package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkHashUtil;
import cn.ussshenzhou.notenoughbandwidth.network.ChunkHashPayload;
import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerChunkSender.class)
public class ChunkDataSenderMixin {

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
        if (!cfg.chunkCacheEnabled) return;

        Connection connection = handler.connection;
        if (!NebConnectionRegistry.isEnabled(connection)) return;

        // Build the packet once. On hit we skip the vanilla path entirely;
        // on miss we send this packet ourselves instead of letting vanilla
        // construct a second identical one.
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk, world.getLightEngine(), null, null);
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
        ci.cancel();
    }
}
