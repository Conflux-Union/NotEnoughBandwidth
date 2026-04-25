package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public class ClientChunkLightRecomputeMixin {

    @Inject(method = "applyLightData", at = @At("TAIL"))
    private void nebRecomputeStrippedLight(int x, int z, ClientboundLightUpdatePacketData lightData, boolean trustEdges, CallbackInfo ci) {
        if (!NotEnoughBandwidthConfig.get().lightStripEnabled) return;
        if (!lightData.getSkyYMask().isEmpty() || !lightData.getBlockYMask().isEmpty()
                || !lightData.getEmptySkyYMask().isEmpty() || !lightData.getEmptyBlockYMask().isEmpty()) {
            return;
        }

        ClientPacketListener handler = (ClientPacketListener) (Object) this;
        ClientLevel world = handler.getLevel();
        if (world == null) return;

        LevelChunk chunk = world.getChunkSource().getChunkNow(x, z);
        if (chunk == null) return;

        ChunkPos chunkPos = new ChunkPos(x, z);
        LevelLightEngine lightEngine = world.getLightEngine();
        lightEngine.setLightEnabled(chunkPos, true);

        var sections = chunk.getSections();
        int minSection = lightEngine.getMinLightSection();
        for (int i = 0; i < sections.length; i++) {
            lightEngine.updateSectionStatus(SectionPos.of(chunkPos, minSection + i), sections[i].hasOnlyAir());
        }

        chunk.findBlockLightSources((pos, state) -> lightEngine.checkBlock(pos));
        lightEngine.propagateLightSources(chunkPos);
        lightEngine.runLightUpdates();
        chunk.setLightCorrect(true);
        world.onChunkLoaded(chunkPos);
    }
}
