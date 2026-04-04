package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkHashUtil;
import cn.ussshenzhou.notenoughbandwidth.network.ChunkHashPayload;
import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.network.ChunkDataSender;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkDataSender.class)
public class ChunkDataSenderMixin {

    /**
     * Intercepts the moment a chunk data packet would be sent to a player.
     * When the client has uploaded a bloom filter and it reports a likely cache hit,
     * we send a tiny ChunkHashPayload (~20 bytes) instead of the full packet (~10-20 KB).
     * The client loads from its local DB, or falls back to requesting the full data.
     */
    @Inject(method = "sendChunkData",
            at = @At("HEAD"),
            cancellable = true)
    private static void nebChunkCacheIntercept(ServerPlayNetworkHandler handler,
                                               ServerWorld world,
                                               WorldChunk chunk,
                                               CallbackInfo ci) {
        var cfg = NotEnoughBandwidthConfig.get();
        if (!cfg.chunkCacheEnabled) return;

        ClientConnection connection = handler.connection;
        if (!NebConnectionRegistry.isEnabled(connection)) return;

        // Build the packet once. On hit we skip the vanilla path entirely;
        // on miss we send this packet ourselves instead of letting vanilla
        // construct a second identical one.
        ChunkDataS2CPacket packet = new ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null);
        ChunkHashUtil.Result result = ChunkHashUtil.compute(packet.getChunkData(), world.getRegistryManager(),
                "SERVER", chunk.getPos().x, chunk.getPos().z);

        if (ChunkCacheManager.serverMightHaveChunk(connection, result.hash())) {
            handler.sendPacket(new CustomPayloadS2CPacket(
                    new ChunkHashPayload(chunk.getPos().x, chunk.getPos().z, result.hash())));
            SimpleStatManager.chunkCacheHits.incrementAndGet();
            SimpleStatManager.chunkCacheSavedBytes.addAndGet(result.dataBytes());
            SimpleStatManager.outRaw((int) Math.min(result.dataBytes(), Integer.MAX_VALUE));
        } else {
            SimpleStatManager.chunkCacheMisses.incrementAndGet();
            handler.sendPacket(packet);
        }
        ci.cancel();
    }
}
