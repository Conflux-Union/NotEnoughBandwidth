package cn.ussshenzhou.notenoughbandwidth.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes private ClientPacketListener methods needed for applying
 * cached chunk data without going through the packet pipeline.
 */
@Mixin(ClientPacketListener.class)
public interface ClientPlayNetworkHandlerInvoker {

    @Invoker("updateLevelChunk")
    void nebLoadChunk(int x, int z, ClientboundLevelChunkPacketData chunkData);

    //#if MC>=12102
    @Invoker("applyLightData")
    void nebReadLightData(int x, int z, ClientboundLightUpdatePacketData lightData, boolean bl);
    //#else
    //$$ @Invoker("applyLightData")
    //$$ void nebReadLightData(int x, int z, ClientboundLightUpdatePacketData lightData);
    //#endif

    @Invoker("enableChunkLight")
    void nebScheduleRenderChunk(LevelChunk chunk, int x, int z);
}
