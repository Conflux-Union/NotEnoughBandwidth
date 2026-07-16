package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.stat.ChunkPacketBreakdown;
//#if MC>=12005
import net.minecraft.network.RegistryFriendlyByteBuf;
//#else
//$$ import net.minecraft.network.FriendlyByteBuf;
//#endif
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientboundLevelChunkPacketData.class)
public abstract class ChunkDataWriteMixin {

    @Unique
    private static final ThreadLocal<Integer> NEB_WRITE_START_IDX = new ThreadLocal<>();

    @Inject(method = "write", at = @At("HEAD"))
    //#if MC>=12005
    private void nebCaptureStart(RegistryFriendlyByteBuf buf, CallbackInfo ci) {
    //#else
    //$$ private void nebCaptureStart(FriendlyByteBuf buf, CallbackInfo ci) {
    //#endif
        NEB_WRITE_START_IDX.set(buf.writerIndex());
    }

    @Inject(method = "write", at = @At("TAIL"))
    //#if MC>=12005
    private void nebRecordSize(RegistryFriendlyByteBuf buf, CallbackInfo ci) {
    //#else
    //$$ private void nebRecordSize(FriendlyByteBuf buf, CallbackInfo ci) {
    //#endif
        Integer start = NEB_WRITE_START_IDX.get();
        if (start != null) {
            ChunkPacketBreakdown.recordChunkData(buf.writerIndex() - start);
        }
    }
}
