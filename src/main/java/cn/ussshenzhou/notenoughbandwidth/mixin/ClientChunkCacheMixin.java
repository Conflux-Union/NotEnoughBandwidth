package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkHashUtil;
import cn.ussshenzhou.notenoughbandwidth.network.ChunkCacheManifestPayload;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
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
                byte[] bloomBytes = ChunkCacheManager.getClientBloomFilterBytes();
                if (bloomBytes != null) {
                    MinecraftClient.getInstance().execute(() -> {
                        try {
                            PacketByteBuf sendBuf = PacketByteBufs.create();
                            new ChunkCacheManifestPayload(bloomBytes).write(sendBuf);
                            ClientPlayNetworking.send(ChunkCacheManifestPayload.CHANNEL, sendBuf);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        });
    }
}
