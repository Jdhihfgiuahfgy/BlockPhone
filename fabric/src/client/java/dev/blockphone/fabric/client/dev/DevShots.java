package dev.blockphone.fabric.client.dev;

import com.mojang.blaze3d.platform.NativeImage;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.apps.gallery.GalleryApp;
import dev.blockphone.fabric.client.apps.notes.NotesStore;
import dev.blockphone.fabric.client.camera.CameraMode;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.phone.AppRegistry;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.client.photo.CameraFilter;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import dev.blockphone.common.protocol.data.MessengerData;
import dev.blockphone.common.server.BlockPhoneServer;
import dev.blockphone.fabric.client.apps.messenger.MessengerApp;
import dev.blockphone.fabric.client.apps.social.SocialApp;
import dev.blockphone.fabric.client.apps.music.MusicApp;
import dev.blockphone.fabric.client.music.LocalLibrary;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.common.protocol.data.SocialData;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.server.FabricServerHandler;

/**
 * Только для разработки. Если задана переменная окружения {@code BLOCKPHONE_DEV_SHOTS=<папка>}:
 * <ol>
 *     <li>в главном меню снимает все приложения (светлая тема — EN, тёмная — RU);</li>
 *     <li>создаёт/загружает мир {@code blockphone_dev} и снимает телефон, погоду, камеру с фильтром,
 *     делает фото, открывает его в галерее и редакторе, показывает заметки;</li>
 *     <li>закрывает игру.</li>
 * </ol>
 * Паузы большие: окно в фоне рисует мало кадров, иначе снимок отстаёт на шаг.
 */
public final class DevShots {
    private static final String WORLD = "blockphone_dev";

    /** Шаг: подождать delayTicks, дождаться условия ready и выполнить action. */
    private record Step(int delayTicks, Predicate<Minecraft> ready, Consumer<Minecraft> action) {
    }

    /**
     * Сценарий: "all" (по умолчанию), "design", "music", "telegram", "messenger", "social", "voice"
     * (три последних — с BLOCKPHONE_DEV_MESSENGER=1).
     */
    private final String scenario = System.getenv().getOrDefault("BLOCKPHONE_DEV_SCENARIO", "all");
    private final AtomicLong directChat = new AtomicLong(-1);
    private final AtomicLong groupChat = new AtomicLong(-1);
    private final AtomicLong publicId = new AtomicLong(-1);
    private final Path dir;
    private final List<Step> steps = new ArrayList<>();
    private int index = -1;
    private int wait = 60;

    private DevShots(Path dir) {
        this.dir = dir;
    }

    public static void initIfRequested() {
        String value = System.getenv("BLOCKPHONE_DEV_SHOTS");
        if (value == null || value.isBlank()) {
            return;
        }
        DevShots shots = new DevShots(Path.of(value));
        shots.plan();
        ClientTickEvents.END_CLIENT_TICK.register(shots::tick);
        BlockPhoneFabric.LOGGER.info("[DevShots] screenshots will be written to {}", value);
    }

    private void step(int delay, Consumer<Minecraft> action) {
        steps.add(new Step(delay, mc -> true, action));
    }

    private void waitFor(Predicate<Minecraft> ready) {
        steps.add(new Step(1, ready, mc -> {
        }));
    }

    private void shot(int delay, String name) {
        step(delay, mc -> shot(mc, name));
    }

    private static void phone(Minecraft mc, Consumer<PhoneScreen> action) {
        if (mc.screen instanceof PhoneScreen phone) {
            action.accept(phone);
        }
    }

    private void plan() {
        PhoneSettings settings = PhoneSettings.get();
        boolean originalDark = settings.darkTheme;
        int originalAccent = settings.accentColor;
        String originalLanguage = settings.language;
        boolean originalLock = settings.lockScreen;
        step(1, mc -> {
            mc.options.pauseOnLostFocus = false;
            settings.lockScreen = false;
        });

        // 1. Все приложения в главном меню.
        boolean server = scenario.equals("messenger") || scenario.equals("social") || scenario.equals("music")
                || scenario.equals("voice") || scenario.equals("telegram");
        for (boolean dark : server ? new boolean[0] : new boolean[]{true, false}) {
            String suffix = dark ? "_dark" : "";
            step(1, mc -> {
                settings.darkTheme = dark;
                settings.language = dark ? "ru_ru" : "en_us";
                settings.accentColor = 0xFF4C8DF6;
                PhoneState.reset();
                mc.setScreen(new PhoneScreen());
            });
            shot(30, "menu_home" + suffix);
            for (AppRegistry.Entry entry : AppRegistry.visible()) {
                step(1, mc -> phone(mc, p -> p.openApp(entry.id())));
                shot(20, "menu_" + entry.id() + suffix);
            }
        }

        // 1б. Другой акцент, страницы цвета и обоев.
        if (!server) {
            step(1, mc -> {
                settings.darkTheme = true;
                settings.language = "ru_ru";
                settings.accentColor = 0xFFF29544;
                PhoneState.reset();
                mc.setScreen(new PhoneScreen());
            });
            shot(30, "accent_home");
            step(1, mc -> phone(mc, p -> p.openApp("calculator")));
            shot(20, "accent_calculator");
            step(1, mc -> phone(mc, p -> p.openApp("settings")));
            shot(20, "accent_settings");
            step(1, mc -> settingsPage("accent"));
            shot(20, "accent_page");
            step(1, mc -> settingsPage("wallpaper"));
            shot(30, "wallpaper_page");
            // Экран блокировки с уведомлениями.
            step(1, mc -> {
                UUID steve = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
                dev.blockphone.fabric.client.phone.PhoneNotifications.add("call:1", "messenger", "Пропущенный звонок", "Строители",
                        steve, p -> {
                        });
                dev.blockphone.fabric.client.phone.PhoneNotifications.add("feed:1", "feed", "Steve оценил ваш пост",
                        "Закат над морем с нового маяка", steve, p -> {
                        });
                dev.blockphone.fabric.client.phone.PhoneNotifications.add("telegram:1", "telegram", "Мама", "Ужин в семь, не опаздывай",
                        null, p -> {
                        });
                dev.blockphone.fabric.client.phone.PhoneNotifications.add("messenger:1", "messenger", "Steve",
                        "Ты где? Я жду у портала в Незер", steve, p -> {
                        });
                settings.lockScreen = true;
                dev.blockphone.fabric.client.phone.PhoneState.markClosed();
                mc.setScreen(null);
            });
            step(1, mc -> {
                // Как будто телефон был закрыт давно.
                try {
                    var field = dev.blockphone.fabric.client.phone.PhoneState.class.getDeclaredField("closedAt");
                    field.setAccessible(true);
                    field.setLong(null, 1);
                } catch (ReflectiveOperationException ignored) {
                }
                mc.setScreen(new PhoneScreen());
            });
            shot(30, "lock_screen");
            step(1, mc -> {
                settings.lockScreen = false;
                dev.blockphone.fabric.client.phone.PhoneNotifications.clear();
            });
            step(1, mc -> settings.accentColor = originalAccent);
        }
        if (scenario.equals("design")) {
            step(5, mc -> {
                settings.lockScreen = originalLock;
                settings.darkTheme = originalDark;
                settings.language = originalLanguage;
                settings.accentColor = originalAccent;
                BlockPhoneFabric.LOGGER.info("[DevShots] done");
                mc.stop();
            });
            return;
        }

        // 2. Мир.
        step(5, mc -> {
            settings.darkTheme = false;
            settings.language = "ru_ru";
            PhoneState.reset();
            mc.setScreen(new TitleScreen());
            if (mc.getLevelSource().levelExists(WORLD)) {
                mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
            } else {
                LevelSettings level = new LevelSettings("BlockPhone Dev", GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                        new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel(WORLD, level, new WorldOptions(20261019L, true, false),
                        WorldPresets::createNormalWorldDimensions, new TitleScreen());
            }
        });
        waitFor(mc -> mc.level != null && mc.player != null && mc.screen == null);
        shot(200, "world_game");
        step(1, mc -> mc.setScreen(new PhoneScreen()));
        shot(30, "world_home");
        step(1, mc -> phone(mc, p -> p.openApp("weather")));
        shot(20, "world_weather");
        // Режим «на ходу»: маленький телефон в углу, мир виден.
        step(1, mc -> {
            settings.compactPhone = true;
            mc.setScreen(null);
            mc.setScreen(new PhoneScreen());
        });
        shot(30, "world_compact");
        // Телефон в руке: свой персонаж от третьего лица спереди.
        step(1, mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
        shot(30, "world_phone_in_hand");
        step(1, mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
        step(1, mc -> {
            settings.compactPhone = false;
            mc.setScreen(null);
            mc.setScreen(new PhoneScreen());
        });

        if (scenario.equals("messenger")) {
            planMessenger(settings);
        } else if (scenario.equals("social")) {
            planSocial(settings);
        } else if (scenario.equals("music")) {
            planMusic(settings);
        } else if (scenario.equals("voice")) {
            planVoice(settings);
        } else if (scenario.equals("telegram")) {
            planTelegram();
        } else if (scenario.equals("hand")) {
            // только общая часть (мир, телефон, «на ходу», телефон в руке)
        } else {
            planCameraGalleryNotes();
        }

        step(5, mc -> {
            settings.darkTheme = originalDark;
            settings.language = originalLanguage;
            settings.accentColor = originalAccent;
            CameraMode.SETTINGS.reset();
            settings.lockScreen = originalLock;
            BlockPhoneFabric.LOGGER.info("[DevShots] done");
            mc.stop();
        });
    }

    private void planCameraGalleryNotes() {
        // 3. Камера.
        step(1, mc -> phone(mc, p -> p.openApp("camera")));
        shot(40, "camera_viewfinder");
        step(1, mc -> {
            CameraMode.setFilter(CameraFilter.SEPIA);
            CameraMode.SETTINGS.zoom = 2f;
        });
        shot(40, "camera_sepia_zoom");
        step(1, mc -> CameraMode.shoot());
        shot(3, "camera_flash");
        // Длинная выдержка 1/4 с на ISO 100: копится несколько кадров, снимок светлее на ~3 ступени.
        step(80, mc -> {
            CameraMode.setFilter(CameraFilter.NONE);
            CameraMode.SETTINGS.zoom = 1f;
            CameraMode.SETTINGS.iso = 1;
            CameraMode.SETTINGS.shutter = 9;
        });
        step(20, mc -> CameraMode.shoot());
        shot(4, "camera_long_exposure");
        step(120, mc -> CameraMode.openGallery());
        shot(60, "gallery_viewer");
        step(1, mc -> {
            if (PhoneState.app("gallery") instanceof GalleryApp gallery) {
                gallery.openEditor();
            }
        });
        shot(60, "gallery_editor");
        step(1, mc -> phone(mc, PhoneScreen::back));
        step(1, mc -> phone(mc, PhoneScreen::back));
        shot(40, "gallery_grid");

        // 4. Заметки.
        step(1, mc -> {
            if (NotesStore.all().stream().noneMatch(n -> n.text.startsWith("Список покупок"))) {
                NotesStore.Note note = NotesStore.create();
                note.text = "Список покупок\nАлмазы — 3 шт.\nИзумруды для жителя\nНе забыть: починить кирку";
                NotesStore.save();
            }
            phone(mc, p -> p.openApp("notes"));
        });
        shot(20, "notes_list");
    }

    // --- Мессенджер (одиночная игра с BLOCKPHONE_DEV_MESSENGER=1) ---

    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
    private static final UUID ALEX = UUID.fromString("ec561538-f3fd-461d-aff5-086b22154bce");

    private void planMessenger(PhoneSettings settings) {
        waitFor(mc -> MessengerClient.loaded());
        step(5, this::seedMessenger);
        waitFor(mc -> directChat.get() > 0 && MessengerClient.chat(directChat.get()) != null);
        // Сообщение, пока телефон закрыт: уведомление + значок.
        step(20, mc -> FabricServerHandler.core().messenger().injectMessage(STEVE, "Steve", directChat.get(),
                "Ты где? Я жду у портала в Незер"));
        shot(15, "msg_toast");
        step(60, mc -> {
            PhoneState.setCurrent(PhoneState.HOME);
            mc.setScreen(new PhoneScreen());
        });
        shot(30, "msg_home_badge");
        step(1, mc -> phone(mc, p -> p.openApp("messenger")));
        shot(20, "msg_list");
        step(1, mc -> messenger(app -> app.openChat(directChat.get())));
        shot(30, "msg_chat");
        step(1, mc -> MessengerClient.sendMessage(directChat.get(), "Уже бегу! Захвати обсидиан на всякий случай"));
        shot(30, "msg_chat_sent");
        step(1, mc -> messenger(app -> app.openChat(groupChat.get())));
        shot(30, "msg_group");
        step(1, mc -> messenger(MessengerApp::onHeaderAction));
        shot(20, "msg_group_info");
        step(1, mc -> phone(mc, PhoneScreen::back));
        step(1, mc -> phone(mc, PhoneScreen::back));
        step(1, mc -> messenger(MessengerApp::onHeaderAction));
        shot(30, "msg_new_chat");
        step(1, mc -> {
            settings.darkTheme = true;
            messenger(app -> app.openChat(groupChat.get()));
        });
        shot(30, "msg_group_dark");
        step(1, mc -> phone(mc, PhoneScreen::back));
        shot(20, "msg_list_dark");
    }

    // --- Лента (одиночная игра с BLOCKPHONE_DEV_MESSENGER=1) ---

    private void planSocial(PhoneSettings settings) {
        waitFor(mc -> MessengerClient.loaded() && ClientSession.get() != null);
        step(5, this::seedMessenger);
        waitFor(mc -> directChat.get() > 0 && MessengerClient.chat(directChat.get()) != null);
        step(5, this::seedSocial);
        waitFor(mc -> publicId.get() > 0);
        // Снимок камерой: в сведениях окажется id сервера — подходит под политику «только с этого сервера».
        step(10, mc -> CameraMode.enter(false));
        step(40, mc -> CameraMode.shoot());
        step(100, mc -> CameraMode.toggle());
        step(20, mc -> {
            SocialClient.feed(SocialData.FeedKind.ALL, null, 0);
            PhoneState.setCurrent(PhoneState.HOME);
            mc.setScreen(new PhoneScreen());
        });
        shot(30, "social_home");
        step(1, mc -> phone(mc, p -> p.openApp("feed")));
        shot(40, "social_feed");
        step(1, mc -> social(SocialApp::debugOpenFirstPost));
        shot(40, "social_post");
        step(1, mc -> phone(mc, PhoneScreen::back));
        step(1, mc -> social(app -> app.debugCompose("Закат над морем с нового маяка Стива", newestPhoto())));
        shot(80, "social_compose");
        step(60, mc -> social(SocialApp::debugPublish));
        shot(60, "social_feed_after_post");
        step(1, mc -> social(app -> app.switchTab(SocialApp.Tab.PROFILE)));
        shot(40, "social_profile_self");
        step(1, mc -> social(app -> app.openProfile(STEVE)));
        shot(40, "social_profile_steve");
        step(1, mc -> social(app -> {
            app.switchTab(SocialApp.Tab.PROFILE);
            app.debugLook(mc.player.getUUID(), 1, 3, 5, 2);
        }));
        shot(40, "profile_look");
        step(1, mc -> social(SocialApp::debugSaveLook));
        shot(40, "profile_self_look");
        step(1, mc -> social(SocialApp::debugPrivacy));
        shot(40, "privacy");
        step(1, mc -> social(app -> app.switchTab(SocialApp.Tab.FEED)));
        step(1, mc -> social(app -> app.switchTab(SocialApp.Tab.PUBLICS)));
        shot(40, "social_publics");
        step(1, mc -> social(app -> app.openPublic(publicId.get())));
        shot(40, "social_public");
        step(1, mc -> social(app -> app.switchTab(SocialApp.Tab.MODERATION)));
        shot(40, "social_moderation");
        // Фото в мессенджере.
        step(1, mc -> phone(mc, p -> p.openApp("messenger")));
        step(1, mc -> messenger(app -> app.openChat(directChat.get())));
        step(20, mc -> messenger(app -> app.debugAttach(newestPhoto())));
        shot(80, "social_chat_attach");
        step(1, mc -> messenger(MessengerApp::debugSubmit));
        shot(80, "social_chat_photo");
        step(1, mc -> {
            settings.darkTheme = true;
            phone(mc, p -> p.openApp("feed"));
            social(app -> app.switchTab(SocialApp.Tab.FEED));
        });
        shot(40, "social_feed_dark");
    }

    // --- Музыка ---

    /** Музыка Minecraft (из ресурсов игры) копируется в blockphone/music; громкость почти нулевая, чтобы не шуметь. */
    /** Придуманный тестовый текст (.lrc) для скопированных треков: строки около 1:00, куда перематывает сценарий. */
    private static void writeTestLyrics() {
        String lrc = String.join("\n",
                "[00:01.00]BlockPhone играет в мире",
                "[00:58.50]Свет фонаря над тихой рекой",
                "[01:01.20]Камень за камнем <01:02.20>строим <01:02.80>наш <01:03.20>дом",
                "[01:04.50]Ночь опустилась на сонный лес",
                "[01:08.00]");
        try (var files = java.nio.file.Files.list(LocalLibrary.dir())) {
            for (Path track : files.filter(f -> f.toString().endsWith(".ogg")).toList()) {
                String name = track.getFileName().toString();
                java.nio.file.Files.writeString(track.resolveSibling(name.substring(0, name.length() - 4) + ".lrc"), lrc);
            }
        } catch (java.io.IOException e) {
            BlockPhoneFabric.LOGGER.warn("[DevShots] lyrics: {}", e.toString());
        }
    }

    private void planMusic(PhoneSettings settings) {
        float originalVolume = settings.musicVolume;
        step(1, mc -> {
            settings.musicVolume = 0.05f;
            copyMinecraftMusic(mc);
            writeTestLyrics();
            settings.lyricsOnline = false;
            LocalLibrary.rescan();
            PhoneState.setCurrent(PhoneState.HOME);
            mc.setScreen(new PhoneScreen());
        });
        waitFor(mc -> LocalLibrary.scanned() && !LocalLibrary.scanning());
        step(10, mc -> phone(mc, p -> p.openApp("music")));
        shot(40, "music_local");
        step(1, mc -> MusicPlayer.play(LocalLibrary.tracks(), 0, null, "Моя музыка"));
        shot(80, "music_local_playing");
        step(1, mc -> music(MusicApp::openPlayer));
        shot(60, "music_player");
        step(1, mc -> MusicPlayer.seek(60_000));
        step(60, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] music state {} at {} ms of {} ms", MusicPlayer.state(),
                MusicPlayer.positionMs(), MusicPlayer.durationMs()));
        shot(1, "music_player_seek");
        step(1, mc -> music(MusicApp::openLyrics));
        shot(30, "music_lyrics");
        step(1, mc -> phone(mc, PhoneScreen::back));
        step(1, mc -> phone(mc, PhoneScreen::back));
        step(1, mc -> music(app -> app.switchTab(MusicApp.Tab.YANDEX)));
        shot(40, "music_yandex_login");
        step(1, mc -> music(app -> app.switchTab(MusicApp.Tab.SPOTIFY)));
        shot(40, "music_spotify_connect");
        step(1, mc -> {
            settings.darkTheme = true;
            music(app -> app.switchTab(MusicApp.Tab.LOCAL));
        });
        shot(40, "music_local_dark");
        step(1, mc -> mc.setScreen(null));
        shot(60, "music_hud");
        step(1, mc -> MusicPlayer.seek(61_000));
        shot(50, "music_lyrics_world");
        step(1, mc -> {
            settings.lyricsMode = "view";
            settings.lyricsScatter = false;
            MusicPlayer.seek(64_600);
        });
        shot(40, "music_lyrics_view");
        // Оформления виджета музыки в разных углах экрана.
        String[][] looks = {{"compact", "top_center", "dark"}, {"large", "top_right", "dark"}, {"minimal", "bottom_left", "dark"},
                {"cover", "middle_right", "accent"}, {"card", "top_left", "light"}};
        for (String[] look : looks) {
            step(1, mc -> {
                settings.hudStyle = look[0];
                settings.hudAnchor = look[1];
                settings.hudColor = look[2];
                settings.hudShowTime = true;
                settings.hudShowLyrics = true;
                MusicPlayer.seek(61_000);
            });
            shot(30, "hud_" + look[0]);
        }
        step(1, mc -> {
            settings.hudStyle = "large";
            settings.hudAnchor = "top_right";
            settings.hudColor = "dark";
            PhoneState.setCurrent(PhoneState.HOME);
            mc.setScreen(new PhoneScreen());
            phone(mc, p -> p.openApp("settings"));
        });
        step(5, mc -> settingsPage("hud"));
        shot(30, "settings_hud");
        step(1, mc -> {
            mc.setScreen(null);
            PhoneSettings fresh = new PhoneSettings();
            settings.hudStyle = fresh.hudStyle;
            settings.hudAnchor = fresh.hudAnchor;
            settings.hudColor = fresh.hudColor;
            settings.hudShowTime = fresh.hudShowTime;
            settings.hudShowLyrics = fresh.hudShowLyrics;
        });
        step(1, mc -> {
            settings.lyricsMode = "world";
            settings.lyricsScatter = true;
            settings.lyricsOnline = true;
        });
        step(1, mc -> {
            BlockPhoneFabric.LOGGER.info("[DevShots] music state {} at {} ms", MusicPlayer.state(), MusicPlayer.positionMs());
            MusicPlayer.stop();
            settings.musicVolume = originalVolume;
        });
    }

    // --- Голосовые и звонки (одиночная игра с BLOCKPHONE_DEV_MESSENGER=1; Simple Voice Chat — из среды разработки) ---

    private void planVoice(PhoneSettings settings) {
        waitFor(mc -> MessengerClient.loaded() && ClientSession.get() != null);
        step(5, this::seedMessenger);
        waitFor(mc -> directChat.get() > 0 && MessengerClient.chat(directChat.get()) != null);
        step(40, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] voice: bridge={}, calls={}, maxVoice={}",
                dev.blockphone.common.voice.VoiceBridges.current() != null, ClientSession.callsAvailable(),
                ClientSession.maxVoiceSeconds()));
        step(1, mc -> {
            PhoneState.setCurrent(PhoneState.HOME);
            mc.setScreen(new PhoneScreen());
            phone(mc, p -> p.openApp("messenger"));
            messenger(app -> app.openChat(directChat.get()));
        });
        // Тихий тестовый сигнал вместо микрофона (2,5 с, «глиссандо» 300→600 Гц).
        step(20, mc -> {
            int n = dev.blockphone.common.protocol.data.VoiceData.SAMPLE_RATE * 5 / 2;
            short[] pcm = new short[n];
            double phase = 0;
            for (int i = 0; i < n; i++) {
                phase += 2 * Math.PI * (300 + 300.0 * i / n) / dev.blockphone.common.protocol.data.VoiceData.SAMPLE_RATE;
                pcm[i] = (short) (500 * Math.sin(phase));
            }
            dev.blockphone.fabric.client.voice.VoiceMessages.upload(pcm, result -> {
                BlockPhoneFabric.LOGGER.info("[DevShots] voice upload: {} {}", result.voice(), result.error());
                if (result.voice() != null) {
                    MessengerClient.sendMessage(directChat.get(), "", null, result.voice());
                }
            });
        });
        shot(80, "voice_chat_sent");
        step(1, mc -> {
            var list = MessengerClient.messages(directChat.get()).list;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i).voice() != null) {
                    dev.blockphone.fabric.client.voice.VoiceMessages.toggle(list.get(i).voice());
                    break;
                }
            }
        });
        shot(25, "voice_playing");
        // Интерфейс звонка на поддельных состояниях (сервер звонков проверен тестами).
        step(60, mc -> {
            var self = new dev.blockphone.common.protocol.data.MessengerData.UserInfo(mc.player.getUUID(), mc.player.getName().getString(), true);
            var steve = new dev.blockphone.common.protocol.data.MessengerData.UserInfo(STEVE, "Steve", true);
            dev.blockphone.fabric.client.voice.CallClient.handle(new dev.blockphone.common.protocol.packet.VoicePackets.CallState(
                    new dev.blockphone.common.protocol.data.VoiceData.CallInfo(directChat.get(), List.of(steve), List.of(self),
                            System.currentTimeMillis(), true)));
        });
        shot(30, "call_incoming");
        step(1, mc -> {
            var self = new dev.blockphone.common.protocol.data.MessengerData.UserInfo(mc.player.getUUID(), mc.player.getName().getString(), true);
            var steve = new dev.blockphone.common.protocol.data.MessengerData.UserInfo(STEVE, "Steve", true);
            dev.blockphone.fabric.client.voice.CallClient.handle(new dev.blockphone.common.protocol.packet.VoicePackets.CallState(
                    new dev.blockphone.common.protocol.data.VoiceData.CallInfo(directChat.get(), List.of(steve, self), List.of(),
                            System.currentTimeMillis() - 65_000, true)));
        });
        shot(30, "call_bar");
        step(1, mc -> mc.setScreen(null));
        shot(40, "call_hud");
        step(1, mc -> dev.blockphone.fabric.client.voice.CallClient.handle(new dev.blockphone.common.protocol.packet.VoicePackets.CallState(
                new dev.blockphone.common.protocol.data.VoiceData.CallInfo(directChat.get(), List.of(), List.of(), 0, false))));
        // Настоящий звонок через Simple Voice Chat (если он подключился): позвонить в группу и посмотреть ответ сервера.
        step(40, mc -> dev.blockphone.fabric.client.voice.CallClient.start(groupChat.get()));
        step(40, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] real call: myCall={}, error={}",
                dev.blockphone.fabric.client.voice.CallClient.myCall(), MessengerClient.recentError()));
        step(1, mc -> {
            PhoneState.setCurrent(PhoneState.HOME);
            mc.setScreen(new PhoneScreen());
            phone(mc, p -> p.openApp("messenger"));
            messenger(app -> app.openChat(groupChat.get()));
        });
        shot(40, "call_real_group");
        step(1, mc -> dev.blockphone.fabric.client.voice.CallClient.leave(groupChat.get()));
    }

    // --- Telegram (ключи — ненастоящие: проверяется запуск TDLib, экраны входа и запись «кружочка») ---

    private void planTelegram() {
        var accounts = dev.blockphone.fabric.client.music.Accounts.get();
        int originalId = accounts.telegramApiId;
        String originalHash = accounts.telegramApiHash;
        step(1, mc -> {
            accounts.telegramApiId = 0;
            accounts.telegramApiHash = "";
            PhoneState.setCurrent(PhoneState.HOME);
            mc.setScreen(new PhoneScreen());
            phone(mc, p -> p.openApp("telegram"));
        });
        shot(30, "telegram_keys");
        step(1, mc -> dev.blockphone.fabric.client.telegram.Telegram.configure(1, "0123456789abcdef0123456789abcdef"));
        waitFor(mc -> {
            var status = dev.blockphone.fabric.client.telegram.Telegram.status();
            return status == dev.blockphone.fabric.client.telegram.Telegram.Status.WAIT_PHONE
                    || status == dev.blockphone.fabric.client.telegram.Telegram.Status.ERROR;
        });
        step(10, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] telegram status {} {}", dev.blockphone.fabric.client.telegram.Telegram.status(),
                dev.blockphone.fabric.client.telegram.Telegram.fatal()));
        shot(20, "telegram_phone");
        // «Кружочек» из кадров мира: 3 секунды записи, затем кодирование в MP4.
        step(1, mc -> dev.blockphone.fabric.client.telegram.VideoNoteRecorder.start(0));
        shot(30, "telegram_video_note_recording");
        step(40, mc -> dev.blockphone.fabric.client.telegram.VideoNoteRecorder.stop());
        step(80, mc -> {
            Path dir = dev.blockphone.fabric.client.storage.ClientFiles.root().resolve("telegram").resolve("videonotes");
            try (var files = Files.list(dir)) {
                files.forEach(f -> {
                    try {
                        BlockPhoneFabric.LOGGER.info("[DevShots] video note {} ({} bytes)", f.getFileName(), Files.size(f));
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("[DevShots] no video notes", e);
            }
            dev.blockphone.fabric.client.telegram.Telegram.shutdown();
            accounts.telegramApiId = originalId;
            accounts.telegramApiHash = originalHash;
            dev.blockphone.fabric.client.music.Accounts.save();
        });
    }

    private static void copyMinecraftMusic(Minecraft mc) {
        String[] names = {"clark", "a_familiar_room", "below_and_above"};
        try {
            Files.createDirectories(LocalLibrary.dir());
            for (String name : names) {
                Path target = LocalLibrary.dir().resolve("Minecraft - " + name.replace('_', ' ') + ".ogg");
                if (Files.exists(target)) {
                    continue;
                }
                var resource = mc.getResourceManager().getResource(
                        net.minecraft.resources.Identifier.withDefaultNamespace("sounds/music/game/" + name + ".ogg"));
                if (resource.isPresent()) {
                    try (var in = resource.get().open()) {
                        Files.copy(in, target);
                    }
                }
            }
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("[DevShots] failed to copy music", e);
        }
    }

    private static void music(Consumer<MusicApp> action) {
        if (PhoneState.app("music") instanceof MusicApp app) {
            action.accept(app);
        }
    }

    private static PhotoStore.Photo newestPhoto() {
        List<PhotoStore.Photo> photos = PhotoStore.list();
        return photos.isEmpty() ? null : photos.get(0);
    }

    private static void social(Consumer<SocialApp> action) {
        if (PhoneState.app("feed") instanceof SocialApp app) {
            action.accept(app);
        }
    }

    /** Подписки, паблик, посты, лайки, комментарии и жалоба — прямо в базе встроенного сервера. */
    private void seedSocial(Minecraft mc) {
        BlockPhoneServer core = FabricServerHandler.core();
        if (core == null || core.social() == null || mc.player == null) {
            BlockPhoneFabric.LOGGER.warn("[DevShots] feed is not running; set BLOCKPHONE_DEV_MESSENGER=1");
            return;
        }
        UUID self = mc.player.getUUID();
        core.social().runInThread(repo -> {
            // Страница Стива: фигура, машет рукой, оранжевый фон, фиолетовый баннер.
            repo.setLook(STEVE, new dev.blockphone.common.server.SocialRepository.LookRow(1, 1, 2, 4, 0), System.currentTimeMillis());
            List<dev.blockphone.common.server.SocialRepository.PublicRow> existing = repo.searchPublics("Новости сервера", 1);
            if (!existing.isEmpty()) {
                publicId.set(existing.get(0).id());
                return;
            }
            long now = System.currentTimeMillis();
            long hour = 3600_000L;
            repo.setBio(STEVE, "Строю замки и маяки. Пишите, если нужна помощь со стройкой!", now);
            repo.setBio(self, "Снимаю закаты и стройки сервера", now);
            repo.follow(self, STEVE, now - 48 * hour);
            repo.follow(STEVE, self, now - 47 * hour);
            repo.follow(ALEX, self, now - 30 * hour);
            long pub = repo.createPublic("Новости сервера", "Официальные новости: обновления, ивенты и конкурсы построек.",
                    0xFF4C8DF6, STEVE, now - 72 * hour);
            repo.subscribe(pub, STEVE, now - 72 * hour);
            repo.subscribe(pub, self, now - 50 * hour);
            repo.subscribe(pub, ALEX, now - 40 * hour);
            long news = repo.insertPost(STEVE, pub, "Сервер обновлён до 1.21.11! В субботу в 18:00 — конкурс построек у спавна. "
                    + "Победитель получит редкий набор блоков.", 0, now - 5 * hour);
            long lighthouse = repo.insertPost(STEVE, 0, "Закончил маяк на побережье. Заходите посмотреть — координаты 120 / 64 / -340",
                    0, now - 2 * hour);
            long spam = repo.insertPost(ALEX, 0, "Продаю алмазы дёшево!!! Пишите в ЛС", 0, now - hour);
            repo.like(lighthouse, ALEX, now - 90 * 60_000L);
            repo.like(news, ALEX, now - 4 * hour);
            repo.like(news, self, now - 4 * hour);
            repo.insertComment(lighthouse, ALEX, "Очень красиво! Можно в гости?", now - 80 * 60_000L);
            repo.insertComment(lighthouse, STEVE, "Конечно, заходи вечером", now - 70 * 60_000L);
            repo.insertReport(STEVE, SocialData.ReportTarget.POST, spam, ALEX, "spam", now - 30 * 60_000L);
            publicId.set(pub);
        });
    }

    private static void settingsPage(String page) {
        if (PhoneState.app("settings") instanceof dev.blockphone.fabric.client.apps.settings.SettingsApp app) {
            app.openPage(page);
        }
    }

    private static void messenger(Consumer<MessengerApp> action) {
        if (PhoneState.app("messenger") instanceof MessengerApp app) {
            action.accept(app);
        }
    }

    /** Тестовые игроки, личный чат и группа с историей (прямо в базе встроенного сервера). */
    private void seedMessenger(Minecraft mc) {
        BlockPhoneServer core = FabricServerHandler.core();
        if (core == null || core.messenger() == null || mc.player == null) {
            BlockPhoneFabric.LOGGER.warn("[DevShots] messenger is not running; set BLOCKPHONE_DEV_MESSENGER=1");
            return;
        }
        UUID self = mc.player.getUUID();
        String selfName = mc.player.getName().getString();
        core.messenger().runInThread(repo -> {
            long now = System.currentTimeMillis();
            long yesterday = now - 26 * 3600_000L;
            repo.upsertUser(STEVE, "Steve", now);
            repo.upsertUser(ALEX, "Alex", now - 3600_000L);
            String key = self.toString().compareTo(STEVE.toString()) < 0 ? self + ":" + STEVE : STEVE + ":" + self;
            Long dm = repo.findDirectChat(key);
            if (dm == null) {
                dm = repo.createChat(MessengerData.ChatType.DIRECT, "", STEVE, key, yesterday);
                repo.addMember(dm, STEVE, yesterday);
                repo.addMember(dm, self, yesterday);
                repo.insertMessage(dm, STEVE, "Привет! Пойдёшь завтра в шахту?", yesterday);
                repo.insertMessage(dm, self, "Конечно, возьму кирки", yesterday + 60_000);
                repo.insertMessage(dm, STEVE, "Отлично. Встречаемся у дома в 18:00 по серверу", now - 600_000);
                long last = repo.insertMessage(dm, self, "Договорились :)", now - 540_000);
                repo.markRead(dm, self, last);
            }
            long group = -1;
            for (var row : repo.chatsOf(self)) {
                if (row.type() == MessengerData.ChatType.GROUP) {
                    group = row.id();
                }
            }
            if (group < 0) {
                group = repo.createChat(MessengerData.ChatType.GROUP, "Строители", self, null, yesterday);
                repo.addMember(group, self, yesterday);
                repo.addMember(group, STEVE, yesterday + 1);
                repo.addMember(group, ALEX, yesterday + 2);
                repo.insertMessage(group, self, "Всем привет! Строим замок у реки", yesterday + 120_000);
                repo.insertMessage(group, ALEX, "Я принесу камень и стекло", yesterday + 180_000);
                repo.insertMessage(group, STEVE, "А я займусь крышей. Нужно много тёмного дуба", now - 1_200_000);
                repo.insertMessage(group, ALEX, "Скину координаты позже, сейчас фармлю опыт", now - 900_000);
            }
            directChat.set(dm);
            groupChat.set(group);
        });
        // Перечитать список чатов, как при входе.
        core.backend().onJoin(self, selfName);
    }

    private void tick(Minecraft mc) {
        if (index < 0 && !(mc.screen instanceof TitleScreen)) {
            return;
        }
        if (--wait > 0) {
            return;
        }
        Step next = index + 1 < steps.size() ? steps.get(index + 1) : null;
        if (next == null) {
            return;
        }
        if (!next.ready().test(mc)) {
            wait = 1;
            return;
        }
        index++;
        next.action().accept(mc);
        wait = index + 1 < steps.size() ? steps.get(index + 1).delayTicks() : 1;
    }

    private void shot(Minecraft mc, String name) {
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("[DevShots] cannot create {}", dir, e);
        }
        Path file = dir.resolve(name + ".png");
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
            try (NativeImage img = image) {
                img.writeToFile(file);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("[DevShots] failed to write {}", file, e);
            }
        });
    }
}
