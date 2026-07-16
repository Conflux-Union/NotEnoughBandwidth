package cn.ussshenzhou.notenoughbandwidth.stat;

import cn.ussshenzhou.notenoughbandwidth.ModConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//#if MC>=12101
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//#else
//$$ import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//#endif
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class ModKey {
    private static KeyMapping statKey;

    //#if MC>=12110
    private static final KeyMapping.Category NEB_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ModConstants.MOD_ID, "main"));
    //#endif

    public static void register() {
        var mapping = new KeyMapping(
                "key.neb.stat",
                GLFW.GLFW_KEY_N,
                //#if MC>=12110
                NEB_CATEGORY
                //#else
                //$$ "key.categories.neb"
                //#endif
        );
        //#if MC>=12101
        statKey = KeyMappingHelper.registerKeyMapping(mapping);
        //#else
        //$$ statKey = KeyBindingHelper.registerKeyBinding(mapping);
        //#endif

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (statKey.consumeClick()) {
                Minecraft.getInstance().setScreen(new StatScreen());
            }
        });
    }
}
