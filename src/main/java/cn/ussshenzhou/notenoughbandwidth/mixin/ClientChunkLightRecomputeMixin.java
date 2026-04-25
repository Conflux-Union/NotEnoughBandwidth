package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * When the server strips light data from a chunk packet (empty sky/block
 * BitSets), the client's vanilla {@code readLightData} call becomes a no-op
 * and the chunk loads pitch-dark. This mixin runs immediately after and
 * drives a full-chunk light recompute on the client-side {@link LightingProvider}
 * — identical in shape to what the server does during chunk generation:
 * enable the column, mark section statuses, poke light emitters, propagate.
 * <p>
 * Works for both the vanilla {@code onChunkData} path and the PCC cache-hit
 * path in {@code ModNetworking}, since both funnel through {@code readLightData}.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientChunkLightRecomputeMixin {

    @Inject(method = "readLightData", at = @At("TAIL"))
    private void nebRecomputeStrippedLight(int x, int z, LightData lightData, boolean bl, CallbackInfo ci) {
        if (!NotEnoughBandwidthConfig.get().lightStripEnabled) return;

        // Detect server-stripped light: no section indices set in any of the
        // four masks means zero nibble layers were shipped. Vanilla chunk
        // packets always set at least some bits, so this is a clean signal.
        if (!lightData.getInitedSky().isEmpty() || !lightData.getInitedBlock().isEmpty()
                || !lightData.getUninitedSky().isEmpty() || !lightData.getUninitedBlock().isEmpty()) {
            return;
        }

        ClientPlayNetworkHandler handler = (ClientPlayNetworkHandler) (Object) this;
        ClientWorld world = handler.getWorld();
        if (world == null) return;

        WorldChunk chunk = world.getChunkManager().getWorldChunk(x, z, false);
        if (chunk == null) return;

        ChunkPos chunkPos = new ChunkPos(x, z);
        LightingProvider lp = world.getLightingProvider();

        lp.setColumnEnabled(chunkPos, true);

        ChunkSection[] sections = chunk.getSectionArray();
        int bottomSection = chunk.getBottomSectionCoord();
        for (int i = 0; i < sections.length; i++) {
            ChunkSectionPos sectionPos = ChunkSectionPos.from(chunkPos.x, bottomSection + i, chunkPos.z);
            lp.setSectionStatus(sectionPos, sections[i].isEmpty());
        }

        chunk.forEachLightSource((pos, state) -> lp.checkBlock(pos));
        lp.propagateLight(chunkPos);
        chunk.setLightOn(true);
    }
}
