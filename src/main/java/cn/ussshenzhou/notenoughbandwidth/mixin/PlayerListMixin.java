package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import net.minecraft.server.PlayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(PlayerManager.class)
public class PlayerListMixin {

    @ModifyVariable(method = "setViewDistance", at = @At("HEAD"), argsOnly = true)
    private int nebModifyViewDistance(int viewDistance) {
        try {
            return viewDistance + NotEnoughBandwidthConfig.get().dccDistance;
        } catch (IllegalStateException ignored) {
            return viewDistance;
        }
    }
}
