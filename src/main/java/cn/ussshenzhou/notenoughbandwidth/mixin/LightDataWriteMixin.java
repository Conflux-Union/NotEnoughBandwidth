package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.stat.ChunkPacketBreakdown;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.s2c.play.LightData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightData.class)
public abstract class LightDataWriteMixin {

    @Unique
    private static final ThreadLocal<Integer> NEB_WRITE_START_IDX = new ThreadLocal<>();

    @Inject(method = "write", at = @At("HEAD"))
    private void nebCaptureStart(PacketByteBuf buf, CallbackInfo ci) {
        NEB_WRITE_START_IDX.set(buf.writerIndex());
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void nebRecordSize(PacketByteBuf buf, CallbackInfo ci) {
        Integer start = NEB_WRITE_START_IDX.get();
        if (start != null) {
            ChunkPacketBreakdown.recordLightData(buf.writerIndex() - start);
        }
    }
}
