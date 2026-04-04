package cn.ussshenzhou.notenoughbandwidth.mixin;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.ChunkData;
import net.minecraft.network.packet.s2c.play.LightData;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes private ClientPlayNetworkHandler methods needed for applying
 * cached chunk data without going through the packet pipeline.
 */
@Mixin(ClientPlayNetworkHandler.class)
public interface ClientPlayNetworkHandlerInvoker {

    @Invoker("loadChunk")
    void nebLoadChunk(int x, int z, ChunkData chunkData);

    @Invoker("readLightData")
    void nebReadLightData(int x, int z, LightData lightData, boolean bl);

    @Invoker("scheduleRenderChunk")
    void nebScheduleRenderChunk(WorldChunk chunk, int x, int z);
}
