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
    /** Включить/выключить режим «на ходу» (по умолчанию не назначена). */
    public static KeyMapping compactKey;
    public static KeyMapping musicPlayKey;
    public static KeyMapping musicNextKey;
    public static KeyMapping musicPrevKey;

    @Override
    public void onInitializeClient() {
        openPhoneKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, KEY_CATEGORY));

        cameraKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.camera", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KEY_CATEGORY));

        compactKey = register("key.blockphone.compact");
        musicPlayKey = register("key.blockphone.music_play");
        musicNextKey = register("key.blockphone.music_next");
        musicPrevKey = register("key.blockphone.music_prev");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MusicPlayer.tick();
            Spotify.tick();
            passMovementThrough(client);
            dev.blockphone.fabric.client.phone.PhoneHolders.tick(client);
            while (compactKey.consumeClick()) {
                if (client.screen == null && client.player != null) {
                    dev.blockphone.fabric.client.config.PhoneSettings.get().compactPhone = true;
                    client.setScreen(new PhoneScreen());
                }
            }
            dev.blockphone.fabric.client.voice.VoiceMessages.tick();
            dev.blockphone.fabric.client.voice.CallClient.tick();
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
            dev.blockphone.fabric.client.telegram.VideoNoteRecorder.tick();
            while (openPhoneKey.consumeClick()) {
                if (dev.blockphone.fabric.client.telegram.VideoNoteRecorder.active()) {
                    dev.blockphone.fabric.client.telegram.VideoNoteRecorder.stop();
                } else if (client.screen == null && client.player != null) {
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
        dev.blockphone.fabric.client.phone.PhoneInHandLayer.init();
        MusicHud.init();
        dev.blockphone.fabric.client.telegram.VideoNoteRecorder.init();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            MusicPlayer.stop();
            dev.blockphone.fabric.client.telegram.Telegram.shutdown();
        });

        ClientNetwork.init();
        DevShots.initIfRequested();
    }

    /**
     * Режим «на ходу»: пока открыт маленький телефон и не печатаешь, клавиши ходьбы, прыжка, приседа и бега
     * передаются игроку (обычно экран их перехватывает).
     */
    private static void passMovementThrough(net.minecraft.client.Minecraft client) {
        if (!(client.screen instanceof PhoneScreen phone) || !phone.compact() || phone.capturesKeyboard()) {
            return;
        }
        var options = client.options;
        for (KeyMapping key : new KeyMapping[]{options.keyUp, options.keyDown, options.keyLeft, options.keyRight, options.keyJump,
                options.keyShift, options.keySprint}) {
            InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(key);
            if (bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() != InputConstants.UNKNOWN.getValue()) {
                key.setDown(InputConstants.isKeyDown(client.getWindow(), bound.getValue()));
            }
        }
    }

    private static KeyMapping register(String name) {
        return KeyBindingHelper.registerKeyBinding(new KeyMapping(name, InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), KEY_CATEGORY));
    }
}
