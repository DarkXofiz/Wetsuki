package com.wetsuki.core;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class Wetsuki implements ClientModInitializer {

    private static final List<WetsukiModule> modules = new ArrayList<>();
    private static final List<KeyBinding> keyBindings = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        // Sayi tusu satiri hotbar ile, F1/F3/F5 vanilla ile cakistigi icin numpad kullanildi.
        registerModule("NCP", GLFW.GLFW_KEY_KP_1);
        registerModule("Matrix", GLFW.GLFW_KEY_KP_2);
        registerModule("Vulcan", GLFW.GLFW_KEY_KP_3);
        registerModule("Spartan", GLFW.GLFW_KEY_KP_4);
        registerModule("AAC", GLFW.GLFW_KEY_KP_5);
        registerModule("Grim", GLFW.GLFW_KEY_KP_6);
        registerModule("Verus", GLFW.GLFW_KEY_KP_7);
        registerModule("Karhu", GLFW.GLFW_KEY_KP_8);
        registerModule("Intave", GLFW.GLFW_KEY_KP_9);
        registerModule("Themis", GLFW.GLFW_KEY_KP_0);
        // Geri kalanlar varsayilan olarak bos, Controls menusunden atanir.
        registerModule("Negativity", GLFW.GLFW_KEY_UNKNOWN);
        registerModule("Horizon", GLFW.GLFW_KEY_UNKNOWN);
        registerModule("Polar", GLFW.GLFW_KEY_UNKNOWN);
        registerModule("Hawk", GLFW.GLFW_KEY_UNKNOWN);
        registerModule("LightAC", GLFW.GLFW_KEY_UNKNOWN);
        registerModule("Wraith", GLFW.GLFW_KEY_UNKNOWN);
        registerModule("Kauri", GLFW.GLFW_KEY_UNKNOWN);
        registerModule("Godseye", GLFW.GLFW_KEY_UNKNOWN);
        registerModule("Medusa", GLFW.GLFW_KEY_UNKNOWN);
        registerModule("Lynx", GLFW.GLFW_KEY_UNKNOWN);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            for (int i = 0; i < keyBindings.size(); i++) {
                while (keyBindings.get(i).wasPressed()) {
                    WetsukiModule module = modules.get(i);
                    module.setEnabled(!module.isEnabled());
                }
            }
            if (client.player == null || client.world == null) return;
            for (WetsukiModule module : modules) {
                if (module.isEnabled()) {
                    module.onTick(client);
                }
            }
        });
    }

    private static void registerModule(String name, int defaultKey) {
        KeyBinding kb = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.wetsuki." + name.toLowerCase(),
                InputUtil.Type.KEYSYM,
                defaultKey,
                "category.wetsuki"
        ));
        keyBindings.add(kb);
        modules.add(new WetsukiModule(name));
    }

    public static class WetsukiModule {
        private final String name;
        private boolean enabled = false;
        protected int tickCounter = 0;

        public WetsukiModule(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean state) {
            this.enabled = state;
            if (!state) tickCounter = 0;
        }

        public void onTick(MinecraftClient client) {
            tickCounter++;
        }
    }
}
