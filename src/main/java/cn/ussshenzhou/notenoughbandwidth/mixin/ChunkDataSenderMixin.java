package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkHashUtil;
import cn.ussshenzhou.notenoughbandwidth.network.ChunkHashPayload;
import cn.ussshenzhou.notenoughbandwidth.network.NebConnectionRegistry;
import cn.ussshenzhou.notenoughbandwidth.stat.SimpleStatManager;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.CustomPayloadS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * In 1.20.1, chunk data sending is done via ThreadedAnvilChunkStorage.sendChunkDataPackets().
 * We intercept it to implement PCC bloom-filter optimization.
 */
@Mixin(ThreadedAnvilChunkStorage.class)
public class ChunkDataSenderMixin {

    @Shadow @Final private ServerWorld world;

    @Inject(method = "sendChunkDataPackets",
            at = @At("HEAD"),
            cancellable = true)
    private void nebChunkCacheIntercept(ServerPlayerEntity player,
                                        MutableObject<ChunkDataS2CPacket> cachedDataPacket,
                                        WorldChunk chunk,
                                        CallbackInfo ci) {
        var cfg = NotEnoughBandwidthConfig.get();
        if (!cfg.chunkCacheEnabled) return;

        ClientConnection connection = player.networkHandler.connection;
        if (!NebConnectionRegistry.isActive(connection)) return;

        ChunkDataS2CPacket packet = cachedDataPacket.getValue();
        if (packet == null) {
            // Vanilla hasn't created the packet yet — create and cache it so
            // we can compute its content hash for the bloom filter check.
            packet = new ChunkDataS2CPacket(chunk, world.getLightingProvider(), null, null);
            cachedDataPacket.setValue(packet);
        }

        ChunkHashUtil.Result result = ChunkHashUtil.compute(packet.getChunkData(),
                "SERVER", chunk.getPos().x, chunk.getPos().z);

        if (ChunkCacheManager.serverMightHaveChunk(connection, result.hash())) {
            PacketByteBuf buf = PacketByteBufs.create();
            new ChunkHashPayload(chunk.getPos().x, chunk.getPos().z, result.hash()).write(buf);
            player.networkHandler.sendPacket(new CustomPayloadS2CPacket(ChunkHashPayload.CHANNEL, buf));
            SimpleStatManager.chunkCacheHits.incrementAndGet();
            SimpleStatManager.chunkCacheSavedBytes.addAndGet(result.dataBytes());
            SimpleStatManager.outRaw((int) Math.min(result.dataBytes(), Integer.MAX_VALUE));
            ci.cancel();
        } else {
            SimpleStatManager.chunkCacheMisses.incrementAndGet();
        }
    }
}
