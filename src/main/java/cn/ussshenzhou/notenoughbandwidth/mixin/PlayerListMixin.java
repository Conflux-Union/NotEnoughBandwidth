package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import net.minecraft.server.players.PlayerList;
//#if MC>=12002
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//#else
//$$ import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
//$$ import net.minecraft.server.level.ServerChunkCache;
//$$ import org.spongepowered.asm.mixin.injection.Redirect;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerList.class)
public class PlayerListMixin {

    //#if MC>=12002
    @ModifyVariable(method = "setViewDistance", at = @At(
            value = "FIELD",
            target = "Lnet/minecraft/server/players/PlayerList;viewDistance:I",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER
    ), argsOnly = true)
    private int nebModifyViewDistance(int viewDistance) {
        try {
            return viewDistance + NotEnoughBandwidthConfig.get().dccDistance;
        } catch (IllegalStateException ignored) {
            return viewDistance;
        }
    }
    //#else
    //$$ /**
    //$$  * Extends the server-side chunk watch distance by dccDistance so that
    //$$  * vanilla's move()/sendWatchPackets covers the DCC range. Only the
    //$$  * ServerChunkCache call is redirected — the client-facing view distance
    //$$  * (ClientboundSetChunkCacheRadiusPacket) is unchanged.
    //$$  */
    //$$ @Redirect(
    //$$         method = "setViewDistance",
    //$$         at = @At(value = "INVOKE",
    //$$                 target = "Lnet/minecraft/server/level/ServerChunkCache;setViewDistance(I)V")
    //$$ )
    //$$ private void nebExtendViewDistance(ServerChunkCache chunkSource, int viewDistance) {
    //$$     int extra;
    //$$     try {
    //$$         extra = ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class).dccDistance;
    //$$     } catch (IllegalStateException ignored) {
    //$$         extra = 0;
    //$$     }
    //$$     chunkSource.setViewDistance(viewDistance + extra);
    //$$ }
    //#endif
}
