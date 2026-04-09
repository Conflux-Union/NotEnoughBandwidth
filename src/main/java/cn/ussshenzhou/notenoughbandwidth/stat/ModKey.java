package cn.ussshenzhou.notenoughbandwidth.stat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public class ModKey {
    private static final KeyBinding.Category NEB_CATEGORY = KeyBinding.Category.create(Identifier.of("neb", "main"));
    private static KeyBinding statKey;

    public static void register() {
        statKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.neb.stat",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                NEB_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (statKey.wasPressed()) {
                MinecraftClient.getInstance().setScreen(new StatScreen());
            }
        });
    }
}
