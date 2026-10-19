package dev.blockphone.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.camera.CameraMode;
import dev.blockphone.fabric.client.dev.DevShots;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Клиентская точка входа: клавиша телефона и сеть. */
public final class BlockPhoneClient implements ClientModInitializer {
    public static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "main"));

    /** Открыть/закрыть телефон. По умолчанию — стрелка вверх. */
    public static KeyMapping openPhoneKey;
    /** Сразу открыть/закрыть камеру. По умолчанию не назначена. */
    public static KeyMapping cameraKey;

    @Override
    public void onInitializeClient() {
        openPhoneKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, KEY_CATEGORY));

        cameraKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.camera", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KEY_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openPhoneKey.consumeClick()) {
                if (client.screen == null && client.player != null) {
                    client.setScreen(new PhoneScreen());
                }
            }
            while (cameraKey.consumeClick()) {
                if (client.screen == null && client.player != null) {
                    CameraMode.toggle();
                }
            }
        });
        CameraMode.init();

        ClientNetwork.init();
        DevShots.initIfRequested();
    }
}
