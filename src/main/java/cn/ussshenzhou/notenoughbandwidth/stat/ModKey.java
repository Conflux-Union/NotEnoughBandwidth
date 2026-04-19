package cn.ussshenzhou.notenoughbandwidth.stat;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ModKey {
    private static KeyMapping statKey;

    private static final KeyMapping.Category NEB_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ModConstants.MOD_ID, "main"));

    public static void register() {
        statKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.neb.stat",
                GLFW.GLFW_KEY_N,
                NEB_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (statKey.consumeClick()) {
                Minecraft.getInstance().setScreen(new StatScreen());
            }
        });
    }
}
