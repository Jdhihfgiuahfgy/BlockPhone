package dev.blockphone.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.camera.CameraMode;
import dev.blockphone.fabric.client.dev.DevShots;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.music.MusicHud;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.spotify.Spotify;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Клиентская точка входа: клавиши, сеть, плеер и виджет музыки. */
public final class BlockPhoneClient implements ClientModInitializer {
    public static final KeyMapping.Category KEY_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "main"));

    /** Открыть/закрыть телефон. По умолчанию — стрелка вверх. */
    public static KeyMapping openPhoneKey;
    /** Сразу открыть/закрыть камеру. По умолчанию не назначена. */
    public static KeyMapping cameraKey;
    /** Музыка: пауза/продолжить, следующий, предыдущий трек (по умолчанию не назначены). */
    public static KeyMapping musicPlayKey;
    public static KeyMapping musicNextKey;
    public static KeyMapping musicPrevKey;

    @Override
    public void onInitializeClient() {
        openPhoneKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, KEY_CATEGORY));

        cameraKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.camera", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KEY_CATEGORY));

        musicPlayKey = register("key.blockphone.music_play");
        musicNextKey = register("key.blockphone.music_next");
        musicPrevKey = register("key.blockphone.music_prev");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MusicPlayer.tick();
            Spotify.tick();
            while (musicPlayKey.consumeClick()) {
                if (MusicPlayer.current() != null || Spotify.status() != Spotify.Status.CONNECTED) {
                    MusicPlayer.toggle();
                } else {
                    Spotify.togglePlay();
                }
            }
            while (musicNextKey.consumeClick()) {
                if (MusicPlayer.current() != null || Spotify.status() != Spotify.Status.CONNECTED) {
                    MusicPlayer.next();
                } else {
                    Spotify.next();
                }
            }
            while (musicPrevKey.consumeClick()) {
                if (MusicPlayer.current() != null || Spotify.status() != Spotify.Status.CONNECTED) {
                    MusicPlayer.previous();
                } else {
                    Spotify.previous();
                }
            }
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
        MusicHud.init();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MusicPlayer.stop());

        ClientNetwork.init();
        DevShots.initIfRequested();
    }

    private static KeyMapping register(String name) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), KEY_CATEGORY));
    }
}
