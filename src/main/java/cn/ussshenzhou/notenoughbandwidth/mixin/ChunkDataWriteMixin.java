package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.stat.ChunkPacketBreakdown;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.packet.s2c.play.ChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkData.class)
public abstract class ChunkDataWriteMixin {

    @Unique
    private static final ThreadLocal<Integer> NEB_WRITE_START_IDX = new ThreadLocal<>();

    @Inject(method = "write", at = @At("HEAD"))
    private void nebCaptureStart(RegistryByteBuf buf, CallbackInfo ci) {
        NEB_WRITE_START_IDX.set(buf.writerIndex());
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void nebRecordSize(RegistryByteBuf buf, CallbackInfo ci) {
        Integer start = NEB_WRITE_START_IDX.get();
        if (start != null) {
            ChunkPacketBreakdown.recordChunkData(buf.writerIndex() - start);
        }
    }
}
