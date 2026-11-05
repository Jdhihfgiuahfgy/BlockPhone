package dev.blockphone.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.camera.CameraMode;
import dev.blockphone.fabric.client.dev.DevShots;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.music.MusicHud;
import dev.blockphone.fabric.client.music.MusicPlayer;
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
    /** «На ходу»: пока зажата — курсор для телефона (по умолчанию левый Alt). */
    public static KeyMapping cursorKey;
    public static KeyMapping musicPlayKey;
    public static KeyMapping musicNextKey;
    public static KeyMapping musicPrevKey;
    /** Пульт музыки: открыт, пока клавиша зажата. */
    public static KeyMapping musicRemoteKey;
    /** Повернуть телефон «на ходу» набок (по умолчанию K). */
    public static KeyMapping rotateKey;
    /** Включить/выключить телефон без рамки. */
    public static KeyMapping framelessKey;
    /** Режим джойстика: управлять игрой на ближайшем экране (телефон в руках набок). */
    public static KeyMapping gameKey;
    private static boolean wasInWorld;

    @Override
    public void onInitializeClient() {
        openPhoneKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, KEY_CATEGORY));

        cameraKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.camera", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KEY_CATEGORY));

        rotateKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.rotate", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KEY_CATEGORY));
        framelessKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.frameless", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, KEY_CATEGORY));
        compactKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.compact", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, KEY_CATEGORY));
        cursorKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.cursor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_ALT, KEY_CATEGORY));
        musicPlayKey = register("key.blockphone.music_play");
        musicNextKey = register("key.blockphone.music_next");
        musicPrevKey = register("key.blockphone.music_prev");
        musicRemoteKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.music_remote", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, KEY_CATEGORY));
        gameKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.blockphone.gamepad", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, KEY_CATEGORY));

        dev.blockphone.fabric.client.music.together.TogetherInvites.init();
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Вышли из мира (на сервер или из одиночной игры) — музыка и голосовые замолкают. Событие отключения
            // с некоторыми модами (FastQuit и т. п.) не приходит, поэтому проверяем и здесь.
            boolean inWorld = client.level != null;
            if (wasInWorld && !inWorld) {
                dev.blockphone.fabric.client.network.ClientNetwork.forgetServer();
            }
            wasInWorld = inWorld;
            MusicPlayer.tick();
            dev.blockphone.fabric.client.clock.ClockService.tick();
            dev.blockphone.fabric.client.music.together.ListenTogether.tick();
            dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.tick();
            dev.blockphone.fabric.client.music.yandex.YandexMusic.tick();
            dev.blockphone.fabric.client.music.media.WindowsMedia.tick();
            dev.blockphone.fabric.client.telegram.TelegramProxy.tick();
            dev.blockphone.fabric.client.music.lyrics.LyricsService.tick();
            passMovementThrough(client);
            dev.blockphone.fabric.client.phone.PhoneHolders.tick(client);
            while (framelessKey.consumeClick()) {
                if (dev.blockphone.fabric.client.phone.PhonePin.active()) {
                    PhoneScreen.toggleFrameless();
                }
            }
            while (rotateKey.consumeClick()) {
                if (dev.blockphone.fabric.client.phone.PhonePin.active()) {
                    PhoneScreen.toggleCompactLandscape();
                }
            }
            while (compactKey.consumeClick()) {
                if (client.screen == null || client.screen instanceof PhoneScreen) {
                    dev.blockphone.fabric.client.phone.PhonePin.toggle(client);
                }
            }
            dev.blockphone.fabric.client.phone.PhonePin.tick(client);
            // Свёрнутое приложение, которое работает в фоне (хост сетевой игры), продолжает свой такт.
            if (!(client.screen instanceof PhoneScreen) && !dev.blockphone.fabric.client.phone.PhonePin.active()) {
                dev.blockphone.fabric.client.phone.PhoneApp bg = dev.blockphone.fabric.client.phone.PhoneState.current();
                if (bg != null && bg.runsInBackground()) {
                    bg.backgroundTick();
                }
            }
            dev.blockphone.fabric.client.apps.custom.CustomApps.tickBackground(client);
            dev.blockphone.fabric.client.apps.custom.CloudRelay.tick();
            dev.blockphone.fabric.client.screens.Screens.tick();
            while (gameKey.consumeClick()) {
                dev.blockphone.fabric.client.screens.AppDisplays.toggleControl(client);
            }
            dev.blockphone.fabric.client.music.MusicRemote.tick(client);
            dev.blockphone.fabric.client.voice.VoiceMessages.tick();
            dev.blockphone.fabric.client.voice.CallClient.tick();
            while (musicPlayKey.consumeClick()) {
                if (MusicPlayer.current() == null && dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.playing() != null) {
                    dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.toggle(); // играет SoundCloud во вкладке «Музыки»
                } else if (MusicPlayer.current() != null || !dev.blockphone.fabric.client.music.media.WindowsMedia.supported()) {
                    MusicPlayer.toggle();
                } else {
                    dev.blockphone.fabric.client.music.media.WindowsMedia.togglePlay();
                }
            }
            while (musicNextKey.consumeClick()) {
                if (MusicPlayer.current() == null && dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.playing() != null) {
                    dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.next(); // играет SoundCloud во вкладке «Музыки»
                } else if (MusicPlayer.current() != null || !dev.blockphone.fabric.client.music.media.WindowsMedia.supported()) {
                    MusicPlayer.next();
                } else {
                    dev.blockphone.fabric.client.music.media.WindowsMedia.next();
                }
            }
            while (musicPrevKey.consumeClick()) {
                if (MusicPlayer.current() == null && dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.playing() != null) {
                    dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.previous(); // играет SoundCloud во вкладке «Музыки»
                } else if (MusicPlayer.current() != null || !dev.blockphone.fabric.client.music.media.WindowsMedia.supported()) {
                    MusicPlayer.previous();
                } else {
                    dev.blockphone.fabric.client.music.media.WindowsMedia.previous();
                }
            }
            dev.blockphone.fabric.client.telegram.VideoNoteRecorder.tick();
            while (openPhoneKey.consumeClick()) {
                if (dev.blockphone.fabric.client.telegram.VideoNoteRecorder.active()) {
                    dev.blockphone.fabric.client.telegram.VideoNoteRecorder.stop();
                } else if (client.screen == null && client.player != null) {
                    // Из режима «на ходу» — в полноэкранный телефон.
                    dev.blockphone.fabric.client.phone.PhonePin.unpin(client);
                    client.setScreen(new PhoneScreen());
                }
            }
            while (cameraKey.consumeClick()) {
                if (client.screen == null && client.player != null) {
                    CameraMode.toggle();
                }
            }
        });
        // Телефон и в меню игры (главное меню, пауза): клавиша телефона открывает его поверх, закрытие — назад в меню.
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof net.minecraft.client.gui.screens.TitleScreen
                    || screen instanceof net.minecraft.client.gui.screens.PauseScreen) {
                net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents.allowKeyPress(screen).register((s, key) -> {
                    if (openPhoneKey.matches(key) && client.screen == s) {
                        client.setScreen(new PhoneScreen().withParent(s));
                        return false;
                    }
                    return true;
                });
            }
        });
        CameraMode.init();
        dev.blockphone.fabric.client.phone.PhoneInHandLayer.init();
        MusicHud.init();
        dev.blockphone.fabric.client.phone.PhonePin.init();
        dev.blockphone.fabric.client.music.lyrics.LyricsWorld.init();
        dev.blockphone.fabric.client.screens.Screens.init();
        dev.blockphone.fabric.client.music.lyrics.LyricsHud.init();
        dev.blockphone.fabric.client.telegram.VideoNoteRecorder.init();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            MusicPlayer.stop();
            dev.blockphone.fabric.client.music.media.WindowsMedia.stop();
            dev.blockphone.fabric.client.telegram.Telegram.shutdown();
            dev.blockphone.fabric.client.browser.WebEngine.shutdown();
            dev.blockphone.fabric.client.apps.custom.CustomApps.saveAll();
            dev.blockphone.fabric.client.music.together.ListenTogether.shutdown();
            dev.blockphone.fabric.client.apps.custom.CloudRelay.shutdown();
        });

        ClientNetwork.init();
        DevShots.initIfRequested();
    }

    /**
     * Режим «на ходу»: пока открыт маленький телефон и не печатаешь, клавиши ходьбы, прыжка, приседа и бега
     * передаются игроку (обычно экран их перехватывает).
     */
    private static void passMovementThrough(net.minecraft.client.Minecraft client) {
        boolean remote = client.screen instanceof dev.blockphone.fabric.client.music.MusicRemote;
        if (!remote && (!(client.screen instanceof PhoneScreen phone) || !phone.compact() || phone.capturesKeyboard())) {
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
