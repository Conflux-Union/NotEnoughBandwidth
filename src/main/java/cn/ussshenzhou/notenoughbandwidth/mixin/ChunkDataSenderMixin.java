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

import java.util.BitSet;

@Mixin(ChunkDataSender.class)
public class ChunkDataSenderMixin {

    /**
     * Intercepts the moment a chunk data packet would be sent to a player.
     * Two orthogonal NEB transforms are applied here so we only build the
     * packet once:
     * <ul>
     *     <li>{@code lightStripEnabled}: light nibble layers are omitted by
     *     passing empty BitSets for the sky/block section masks. The client
     *     recomputes full-chunk light after load.</li>
     *     <li>{@code chunkCacheEnabled} (PCC): if the client's bloom filter
     *     reports a likely hit, ship a tiny {@link ChunkHashPayload} instead
     *     of the full packet.</li>
     * </ul>
     * If neither transform applies we bail out and let the vanilla path run.
     */
    @Inject(method = "sendChunkData",
            at = @At("HEAD"),
            cancellable = true)
    private static void nebChunkCacheIntercept(ServerPlayNetworkHandler handler,
                                               ServerWorld world,
                                               WorldChunk chunk,
                                               CallbackInfo ci) {
        var cfg = NotEnoughBandwidthConfig.get();
        ClientConnection connection = handler.connection;
        if (!NebConnectionRegistry.isEnabled(connection)) return;
        if (!cfg.chunkCacheEnabled && !cfg.lightStripEnabled) return;

        // Empty BitSet -> LightData ctor writes zero nibble layers.
        // null -> vanilla "send all sections" behavior.
        BitSet lightMask = cfg.lightStripEnabled ? new BitSet() : null;
        ChunkDataS2CPacket packet = new ChunkDataS2CPacket(
                chunk, world.getLightingProvider(), lightMask, lightMask);

        if (cfg.chunkCacheEnabled) {
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
        } else {
            handler.sendPacket(packet);
        }
        ci.cancel();
    }
}
