package cn.ussshenzhou.notenoughbandwidth.mixin;

import cn.ussshenzhou.notenoughbandwidth.NotEnoughBandwidthConfig;
import cn.ussshenzhou.notenoughbandwidth.config.ConfigHelper;
import net.minecraft.server.PlayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Extends the server-side chunk watch distance by dccDistance so that
 * vanilla's updatePosition/sendWatchPackets covers the DCC range.
 * Only affects applyViewDistance — the client-facing view distance is unchanged.
 */
@Mixin(PlayerManager.class)
public class PlayerListMixin {

    @Redirect(
            method = "setViewDistance",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/world/ServerChunkManager;applyViewDistance(I)V")
    )
    private void nebExtendViewDistance(net.minecraft.server.world.ServerChunkManager chunkManager, int viewDistance) {
        int extra;
        try {
            extra = ConfigHelper.getConfigRead(NotEnoughBandwidthConfig.class).dccDistance;
        } catch (IllegalStateException ignored) {
            extra = 0;
        }
        chunkManager.applyViewDistance(viewDistance + extra);
    }
}
