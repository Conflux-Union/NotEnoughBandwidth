package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkHashUtil;
import cn.ussshenzhou.notenoughbandwidth.network.IndexSyncHandler;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientChunkCacheMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-ChunkCacheWriter");

    private static final ExecutorService CACHE_WRITER = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("NEB-ChunkCacheWriter").setDaemon(true).build());

    @Inject(method = "onChunkData",
            at = @At("TAIL"))
    private void nebCacheChunk(ChunkDataS2CPacket packet, CallbackInfo ci) {
        var cfg = NotEnoughBandwidthConfig.get();
        if (!cfg.chunkCacheEnabled) return;
        if (!ChunkCacheManager.isClientEnabled()) return;

        var chunkData = packet.getChunkData();
        var lightData = packet.getLightData();

        CACHE_WRITER.execute(() -> {
            long hash = ChunkHashUtil.compute(chunkData,
                    "CLIENT", packet.getX(), packet.getZ()).hash();
            if (ChunkCacheManager.getClientCachedChunk(hash) != null) return;

            var inner = Unpooled.buffer(8192);
            var buf = new PacketByteBuf(inner);
            try {
                chunkData.write(buf);
                lightData.write(buf);
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                ChunkCacheManager.cacheChunk(hash, bytes);
            } finally {
                inner.release();
            }

            if (ChunkCacheManager.drainAndShouldResend()) {
                ChunkCacheManager.evictAndRebuildIfNeeded();
                MinecraftClient.getInstance().execute(() -> {
                    try {
                        byte[] bloomBytes = ChunkCacheManager.getClientBloomFilterBytes();
                        if (bloomBytes != null) {
                            IndexSyncHandler.sendChunkedManifest(bloomBytes);
                        }
                    } catch (Exception e) {
                        LOGGER.warn("Failed to resend chunk cache manifest", e);
                    }
                });
            }
        });
    }
}
