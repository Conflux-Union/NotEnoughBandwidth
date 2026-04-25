package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkCacheManager;
import cn.ussshenzhou.notenoughbandwidth.chunkcache.ChunkHashUtil;
import cn.ussshenzhou.notenoughbandwidth.network.ChunkCacheManifestPayload;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Mixin(ClientPlayNetworkHandler.class)
public class ClientChunkCacheMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("NEB-ChunkCacheWriter");

    // Off-thread executor so DB writes don't stall the client netty thread.
    private static final ExecutorService CACHE_WRITER = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setNameFormat("NEB-ChunkCacheWriter").setDaemon(true).build());

    /**
     * After vanilla finishes applying a chunk packet, serialize and cache the chunk data.
     * Runs the actual DB write on a background thread to avoid blocking packet processing.
     */
    @Inject(method = "onChunkData",
            at = @At("TAIL"))
    private void nebCacheChunk(ChunkDataS2CPacket packet, CallbackInfo ci) {
        var cfg = NotEnoughBandwidthConfig.get();
        if (!cfg.chunkCacheEnabled) return;
        if (!ChunkCacheManager.isClientEnabled()) return;

        // Capture packet data now (we're on the netty/main thread).
        // We need the RegistryManager to serialize block entity types correctly.
        var handler = (ClientPlayNetworkHandler) (Object) this;
        var registryManager = handler.getRegistryManager();

        var chunkData = packet.getChunkData();
        var lightData = packet.getLightData();

        // Serialize on a background thread so DB write doesn't block.
        CACHE_WRITER.execute(() -> {
            long hash = ChunkHashUtil.compute(chunkData, registryManager,
                    "CLIENT", packet.getChunkX(), packet.getChunkZ()).hash();
            // Only write if not already cached (avoid pointless re-writes for re-entered areas).
            if (ChunkCacheManager.getClientCachedChunk(hash) != null) return;

            var inner = Unpooled.buffer(8192);
            var buf = new RegistryByteBuf(inner, registryManager);
            try {
                chunkData.write(buf);
                lightData.write(buf);
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                ChunkCacheManager.cacheChunk(hash, bytes);
            } finally {
                inner.release();
            }

            // When enough new chunks have been cached, push the updated bloom filter to the server
            // so it can start skipping them in the current session (not just after reconnect).
            // evictAndRebuildIfNeeded runs here (off-thread, outside class monitor) so that
            // Files.walk + LevelDB compaction don't block the main synchronized operations.
            if (ChunkCacheManager.drainAndShouldResend()) {
                ChunkCacheManager.evictAndRebuildIfNeeded();
                MinecraftClient.getInstance().execute(() -> {
                    byte[] bloomBytes = ChunkCacheManager.getClientBloomFilterBytes();
                    if (bloomBytes != null) {
                        try {
                            ClientPlayNetworking.send(new ChunkCacheManifestPayload(bloomBytes));
                        } catch (Exception e) {
                            LOGGER.warn("Failed to resend chunk cache manifest", e);
                        }
                    }
                });
            }
        });
    }
}
