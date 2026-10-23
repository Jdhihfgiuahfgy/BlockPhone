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

    /**
     * «Block Strike» из docs/examples/scripts/blockstrike: заставка → «Игра с ботами» → «Начать», фаза покупки,
     * меню покупки, бой с ботами, пауза.
     */
    private void planStrike() {
        step(1, mc -> {
            try {
                Path example = Path.of("..", "..", "docs", "examples", "scripts", "blockstrike");
                Path target = dev.blockphone.fabric.client.apps.custom.CustomApps.folder().resolve("blockstrike");
                try (var files = Files.walk(example)) {
                    for (Path f : files.toList()) {
                        Path to = target.resolve(example.relativize(f).toString());
                        if (Files.isDirectory(f)) {
                            Files.createDirectories(to);
                        } else {
                            Files.copy(f, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
                // Только для снимков: клавиша «0» выдаёт по очереди оружие (3D-модели от первого лица).
                Files.writeString(target.resolve("dev.lua"), """
                        local list = {11, 12, 13, 6, 8, 10, 9, 7, 5, 3, 1, 14, 17}
                        local i = 0
                        local maps, m = {5, 6, 7, 8, 9}, 0
                        return {key = function(G, key, Sim, api)
                          if key == "9" then
                            m = m % #maps + 1
                            local def = require("maps").LIST[maps[m]]
                            api.start("offline", {map = maps[m], firstTo = 8, level = 2, bots = 5, team = 0,
                              wingman = def.wingman == true})
                            print("dev map " .. def.key)
                            return true
                          end
                          if key ~= "0" or not G.S then return false end
                          i = i % #list + 1
                          local wid = list[i]
                          local P = G.S.players[G.me.id]
                          local w = require("data").WEAPONS[wid]
                          if w.slot == 1 then P.pri = wid elseif w.slot == 2 then P.sec = wid
                          elseif w.slot == 4 then P.he = 1 elseif w.slot == 5 then P.bomb = true end
                          Sim.giveAmmo(P, wid)
                          G.me.wpn, G.me.switchT, G.me.ammo[wid] = wid, 0, P.ammo[wid] and {math.max(1, P.ammo[wid][1] - 20), P.ammo[wid][2]}
                          print("dev weapon " .. w.key)
                          return true
                        end}
                        """);
                dev.blockphone.fabric.client.apps.custom.CustomApps.scan(true);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("[DevShots] strike: {}", e.toString());
            }
            PhoneState.setCurrent(PhoneState.HOME);
            mc.setScreen(new PhoneScreen());
        });
        step(20, mc -> phone(mc, p -> p.openApp("app.blockstrike")));
        shot(60, "strike_title");
        step(1, mc -> customApp("blockstrike", a -> a.debugClick(a.contentWidth() / 2.0, 58)));
        shot(20, "strike_create");
        // «Режим» → Напарники (кнопка «>» в первой строке), снимок, обратно.
        Runnable modeClick = () -> customApp("blockstrike", a -> {
            double sw = a.contentWidth(), sh = a.contentHeight(), size = sh - 46, x = size + 20, w = sw - size - 28;
            a.debugClick(x + w - 7, 28);
        });
        step(1, mc -> modeClick.run());
        shot(10, "strike_create_wingman");
        step(1, mc -> modeClick.run());
        step(1, mc -> customApp("blockstrike", a -> {
            double sw = a.contentWidth(), sh = a.contentHeight(), size = sh - 46, x = size + 20, w = sw - size - 28;
            a.debugClick(x + w * 0.75, sh - 13);
        }));
        shot(100, "strike_freeze");
        step(1, mc -> customApp("blockstrike", a -> a.debugKey("b")));
        step(5, mc -> customApp("blockstrike", a -> a.debugKey("1")));
        shot(10, "strike_buy");
        step(1, mc -> customApp("blockstrike", a -> {
            a.debugKey("3");
            a.debugKey("b");
        }));
        shot(120, "strike_live");
        String[] guns = {"ak47", "m4a4", "awp", "nova", "p90", "famas", "galil", "mp9", "deagle", "usp", "knife", "he", "c4"};
        for (String gun : guns) {
            step(1, mc -> customApp("blockstrike", a -> a.debugKey("0")));
            shot(12, "strike_w_" + gun);
        }
        step(1, mc -> customApp("blockstrike", a -> {
            a.debugKey("0"); // снова AK — перезарядка
            a.debugKey("r");
        }));
        shot(25, "strike_reload");
        shot(300, "strike_fight");
        shot(300, "strike_later");
        step(1, mc -> phone(mc, PhoneScreen::back));
        shot(20, "strike_pause");
        // Новые карты (Ледник, Офис, Храм, Озеро и Переулок — «Напарники»): игра с ботами, снимок в начале раунда.
        for (String map : new String[]{"snow", "office", "temple", "lake", "alley"}) {
            step(1, mc -> customApp("blockstrike", a -> a.debugKey("9")));
            shot(160, "strike_map_" + map);
        }
    }

    /**
     * Свои приложения: «Кликер» из примера, «Банк» из docs/examples с серверной частью на Java (встроенный сервер,
     * нужен BLOCKPHONE_DEV_MESSENGER=1), подтверждение, отключённое сервером приложение, «Настройки → Свои приложения».
     */
    private void planApps() {
        step(1, mc -> {
            try {
                Path apps = dev.blockphone.fabric.client.apps.custom.CustomApps.folder();
                Files.createDirectories(apps.resolve("bank"));
                try (var in = DevShots.class.getResourceAsStream("/blockphone/apps/example-tapper.json")) {
                    Files.write(apps.resolve("tapper.json"), in.readAllBytes());
                }
                Path docs = Path.of("..", "..", "docs", "examples", "bank-client", "bank", "app.json");
                Files.copy(docs, apps.resolve("bank").resolve("app.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(apps.resolve("broken.json"), "{\"pages\": {\"main\": {\"elements\": [{\"type\": \"button\", "
                        + "\"action\": {\"command\": \"spawn\"}}]}}}");
                // Скриптовые примеры из docs/examples/scripts и приложение с бесконечным циклом (проверка лимита).
                Path scripts = Path.of("..", "..", "docs", "examples", "scripts");
                try (var list = Files.list(scripts)) {
                    for (Path example : list.toList()) {
                        Path target = apps.resolve(example.getFileName().toString());
                        try (var files = Files.walk(example)) {
                            for (Path f : files.toList()) {
                                Path to = target.resolve(example.relativize(f).toString());
                                if (Files.isDirectory(f)) {
                                    Files.createDirectories(to);
                                } else {
                                    Files.copy(f, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                }
                            }
                        }
                    }
                }
                Path loop = apps.resolve("loop");
                Files.createDirectories(loop);
                Files.writeString(loop.resolve("app.json"), "{\"id\": \"loop\", \"name\": \"Loop\", \"script\": \"main.lua\"}");
                Files.writeString(loop.resolve("main.lua"), "function onDraw() gfx.clear() gfx.text('click me', 8, 8) end\n"
                        + "function onClick() pcall(function() while true do end end) end\n");
                dev.blockphone.fabric.client.apps.custom.CustomApps.scan(true);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("[DevShots] apps: {}", e.toString());
            }
            // Серверная часть банка на Java (как в docs/examples/bank-plugin), деньги — в памяти.
            java.util.Map<UUID, java.math.BigDecimal> money = new java.util.concurrent.ConcurrentHashMap<>();
            dev.blockphone.common.apps.BlockPhoneApps.handler("bank", new dev.blockphone.common.apps.BlockPhoneApps.AppHandler() {
                @Override
                public java.util.Map<String, String> variables(UUID player, String page) {
                    return java.util.Map.of("balance", money.computeIfAbsent(player, k -> new java.math.BigDecimal("1250")).toPlainString() + " $",
                            "top1_name", "Notch", "top1_money", "99 000 $", "top2_name", "Alex", "top2_money", "4 200 $");
                }

                @Override
                public dev.blockphone.common.apps.AppResult call(UUID player, String function, java.util.Map<String, String> inputs) {
                    java.math.BigDecimal amount = new java.math.BigDecimal(inputs.get("amount").replace(',', '.'));
                    money.merge(player, amount.negate(), java.math.BigDecimal::add);
                    return dev.blockphone.common.apps.AppResult.ok("Отправлено " + amount.toPlainString() + " $ игроку " + inputs.get("to"))
                            .then("main");
                }
            });
            dev.blockphone.fabric.client.apps.custom.CustomApps.scan(true);
            PhoneState.setCurrent(PhoneState.HOME);
            mc.setScreen(new PhoneScreen());
        });
        shot(40, "apps_home");
        step(1, mc -> phone(mc, p -> p.openApp("app.tapper")));
        for (int i = 0; i < 7; i++) {
            step(2, mc -> customApp("tapper", a -> a.debugAct("main:0")));
        }
        shot(20, "apps_tapper");
        step(1, mc -> customApp("tapper", a -> a.debugAct("main:1")));
        shot(20, "apps_confirm");
        step(1, mc -> phone(mc, p -> p.goHome()));
        step(1, mc -> phone(mc, p -> p.openApp("app.bank")));
        waitFor(mc -> dev.blockphone.fabric.client.network.ClientNetwork.serverHello() != null);
        shot(60, "apps_bank");
        step(1, mc -> customApp("bank", a -> a.debugAct("main:0")));
        step(30, mc -> customApp("bank", a -> {
            a.debugInput("to", "Alex");
            a.debugInput("amount", "250");
        }));
        shot(20, "apps_bank_transfer");
        step(1, mc -> customApp("bank", a -> a.debugAct("transfer:0")));
        shot(20, "apps_bank_confirm");
        step(1, mc -> phone(mc, p -> {
            p.closeDialog();
        }));
        step(1, mc -> {
            // Как будто сервер запретил «Кликер».
            dev.blockphone.fabric.client.apps.custom.CustomApps.onPolicy(new dev.blockphone.common.protocol.packet.AppPackets.Policy(
                    "blacklist", List.of("tapper"), List.of(), List.of("bank")));
            phone(mc, p -> p.openApp("app.tapper"));
        });
        shot(30, "apps_blocked");
        step(1, mc -> phone(mc, p -> {
            p.openApp("settings");
            settingsPage("apps");
        }));
        shot(40, "apps_settings");
        // Скрипты: «Змейка» (набок, весь экран), «Крестики-нолики» (сеть), «Погода в мире» (HTTP), бесконечный цикл.
        step(1, mc -> {
            dev.blockphone.fabric.client.apps.custom.CustomApps.onPolicy(new dev.blockphone.common.protocol.packet.AppPackets.Policy(
                    "blacklist", List.of(), List.of(), List.of("bank")));
            phone(mc, p -> p.openApp("app.snake"));
        });
        shot(60, "apps_snake");
        step(1, mc -> phone(mc, p -> p.openApp("app.tictactoe")));
        shot(50, "apps_tictactoe");
        step(1, mc -> phone(mc, p -> p.openApp("app.weather-web")));
        shot(100, "apps_weather_web");
        // DOOM: «Новая игра» → уровень 1 (мышь захватывается), снимок, Esc → пауза.
        step(1, mc -> phone(mc, p -> p.openApp("app.doom")));
        shot(40, "apps_doom_title");
        step(1, mc -> customApp("doom", a -> a.debugClick(a.contentWidth() / 2.0, a.contentHeight() / 2.0 + 8)));
        shot(60, "apps_doom_play");
        step(1, mc -> customApp("doom", a -> a.debugKey("m")));
        shot(20, "apps_doom_map");
        step(1, mc -> phone(mc, PhoneScreen::back));
        shot(30, "apps_doom_pause");
        planStore();
        step(1, mc -> phone(mc, p -> p.openApp("app.loop")));
        step(20, mc -> customApp("loop", a -> a.debugClick(20, 20)));
        shot(30, "apps_script_timeout");
    }

    /** Часы: будильники (список и редактор), секундомер с кругами, таймер (выбор и идёт), звонок. */
    private void planClock() {
        int[] volume = new int[1];
        step(1, mc -> {
            // Звуки телефона — тихо (5 %); свой звук — копия музыки Minecraft в blockphone/sounds.
            volume[0] = PhoneSettings.get().soundVolume;
            PhoneSettings.get().soundVolume = 5;
            try {
                java.nio.file.Files.createDirectories(dev.blockphone.fabric.client.sound.PhoneSounds.dir());
                Path target = dev.blockphone.fabric.client.sound.PhoneSounds.dir().resolve("Мой звонок.ogg");
                var resource = mc.getResourceManager().getResource(
                        net.minecraft.resources.Identifier.withDefaultNamespace("sounds/music/game/clark.ogg"));
                if (!java.nio.file.Files.exists(target) && resource.isPresent()) {
                    try (var in = resource.get().open()) {
                        java.nio.file.Files.copy(in, target);
                    }
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("[DevShots] sound copy failed", e);
            }
            dev.blockphone.fabric.client.sound.PhoneSounds.setChoice(dev.blockphone.fabric.client.sound.PhoneSounds.Kind.TIMER,
                    "file:Мой звонок.ogg");
        });
        step(1, mc -> {
            PhoneSettings s = PhoneSettings.get();
            s.alarms.clear();
            PhoneSettings.Alarm a = new PhoneSettings.Alarm();
            a.hour = 7;
            a.minute = 30;
            a.days = 0x1F;
            a.label = "Проверить ферму";
            s.alarms.add(a);
            PhoneSettings.Alarm b = new PhoneSettings.Alarm();
            b.hour = 9;
            b.minute = 0;
            b.days = 0x60;
            b.enabled = false;
            s.alarms.add(b);
            if (mc.screen == null) {
                mc.setScreen(new PhoneScreen());
            }
            phone(mc, p -> p.openApp("clock"));
        });
        shot(30, "clock_alarms");
        step(1, mc -> clockDebug("edit"));
        shot(20, "clock_alarm_edit");
        step(1, mc -> {
            clockDebug("close");
            clockDebug("stopwatch");
            dev.blockphone.fabric.client.clock.ClockService.toggleStopwatch();
        });
        step(20, mc -> dev.blockphone.fabric.client.clock.ClockService.lap());
        step(15, mc -> dev.blockphone.fabric.client.clock.ClockService.lap());
        shot(15, "clock_stopwatch");
        step(1, mc -> clockDebug("timer"));
        shot(20, "clock_timer_pick");
        step(1, mc -> dev.blockphone.fabric.client.clock.ClockService.startTimer(3000));
        shot(20, "clock_timer_run");
        shot(60, "clock_ringing");
        step(1, mc -> {
            dev.blockphone.fabric.client.clock.ClockService.stopRinging();
            for (String tone : dev.blockphone.fabric.client.sound.PhoneSounds.BUILTIN) {
                long t0 = System.nanoTime();
                dev.blockphone.fabric.client.sound.PhoneSounds.play("builtin:" + tone, false).stop();
                BlockPhoneFabric.LOGGER.info("[DevShots] tone {} ok ({} ms)", tone, (System.nanoTime() - t0) / 1_000_000);
            }
            mc.setScreen(new PhoneScreen());
            phone(mc, p -> p.openApp("settings"));
        });
        step(5, mc -> settingsPage("notify"));
        shot(30, "settings_notify_sounds");
        step(1, mc -> settingsSound());
        shot(30, "settings_sound_pick");
        step(1, mc -> {
            dev.blockphone.fabric.client.sound.PhoneSounds.stopPreview();
            PhoneSettings.get().soundVolume = volume[0];
            PhoneSettings.get().sounds.clear();
            dev.blockphone.fabric.client.clock.ClockService.resetStopwatch();
            PhoneSettings.get().alarms.clear();
            PhoneSettings.get().save();
        });
    }

    private static void settingsSound() {
        if (PhoneState.app("settings") instanceof dev.blockphone.fabric.client.apps.settings.SettingsApp app) {
            app.debugSoundPage();
        }
    }

    private static void clockDebug(String what) {
        if (PhoneState.existing("clock") instanceof dev.blockphone.fabric.client.apps.clock.ClockApp app) {
            app.debug(what);
        }
    }

    /** Магазин: каталог-пример из docs/examples/store (file:), ход установки, отзывы (выдуманные), установка. */
    private void planStore() {
        step(1, mc -> {
            dev.blockphone.fabric.client.store.AppStore.allowFiles = true;
            PhoneSettings.get().storeSources.clear();
            PhoneSettings.get().storeSources.add(Path.of("..", "..", "docs", "examples", "store", "catalog.json").toAbsolutePath().normalize().toUri().toString());
            dev.blockphone.fabric.client.store.AppStore.uninstall("tictactoe");
            if (mc.screen == null) {
                mc.setScreen(new PhoneScreen());
            }
            phone(mc, p -> p.openApp("store"));
            dev.blockphone.fabric.client.store.AppStore.refresh();
        });
        step(30, mc -> {
            dev.blockphone.fabric.client.store.AppStore.debugFakeReviews();
            dev.blockphone.fabric.client.store.AppStore.debugProgress("snake", 0.42f);
        });
        shot(20, "store_catalog");
        step(1, mc -> {
            dev.blockphone.fabric.client.store.AppStore.debugProgress("snake", -1);
            storeDebug("detail");
        });
        shot(20, "store_detail");
        step(1, mc -> dev.blockphone.fabric.client.store.AppStore.entries().stream().filter(e -> e.id().equals("tictactoe")).findFirst()
                .ifPresent(e -> dev.blockphone.fabric.client.store.AppStore.install(e,
                        err -> BlockPhoneFabric.LOGGER.info("[DevShots] store install: {}", err == null ? "ok" : err))));
        step(40, mc -> storeDebug("detail:tictactoe"));
        shot(20, "store_detail_installed");
        step(1, mc -> storeDebug("scroll"));
        shot(20, "store_reviews");
        step(1, mc -> storeDebug("installed"));
        shot(20, "store_installed");
        step(1, mc -> PhoneSettings.get().storeSources.clear());
    }

    private static void storeDebug(String what) {
        if (PhoneState.existing("store") instanceof dev.blockphone.fabric.client.apps.store.StoreApp app) {
            app.debug(what);
        }
    }

    private static void telegramDebug(String what) {
        if (PhoneState.existing("telegram") instanceof dev.blockphone.fabric.client.apps.telegram.TelegramApp app) {
            app.debugChat(what);
        }
    }

    private static void customApp(String id, Consumer<dev.blockphone.fabric.client.apps.custom.CustomApp> action) {
        if (PhoneState.existing("app." + id) instanceof dev.blockphone.fabric.client.apps.custom.CustomApp app) {
            action.accept(app);
        }
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
        // Общие снимки (все приложения в меню, мир, «на ходу») — только в полном прогоне и «design»/«hand»:
        // сценарий конкретной части снимает только её (быстрее).
        boolean full = scenario.equals("all") || scenario.equals("design");
        for (boolean dark : !full ? new boolean[0] : new boolean[]{true, false}) {
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
        if (full) {
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
            step(1, mc -> settingsPage("notify"));
            shot(20, "notify_page");
            // Экран блокировки с уведомлениями.
            step(1, mc -> {
                UUID steve = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");
                dev.blockphone.fabric.client.phone.PhoneNotifications.add("call:1", "messenger", "Пропущенный звонок", "Строители",
                        steve, p -> {
                        });
                dev.blockphone.fabric.client.phone.PhoneNotifications.add("feed:1", "messenger", "Steve оценил ваш пост",
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
        boolean world = scenario.equals("all") || scenario.equals("hand");
        if (!world) {
            step(100, mc -> mc.setScreen(new PhoneScreen())); // мир прогрузился — сразу к своему сценарию
        } else {
            shot(200, "world_game");            step(1, mc -> mc.setScreen(new PhoneScreen()));            shot(30, "world_home");            step(1, mc -> phone(mc, p -> p.openApp("weather")));            shot(20, "world_weather");            // Режим «на ходу»: телефон прикреплён в углу, мышь у игры (курсора нет).            step(1, mc -> {                mc.setScreen(null);                dev.blockphone.fabric.client.phone.PhonePin.pin(mc);            });            shot(30, "world_compact");            // Телефон в руке: свой персонаж от третьего лица спереди.            step(1, mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));            shot(30, "world_phone_in_hand");            step(1, mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));            // Как при зажатом Alt: курсор, телефоном можно пользоваться.            step(1, mc -> dev.blockphone.fabric.client.phone.PhonePin.debugCursor = true);            shot(30, "world_compact_cursor");            step(1, mc -> {                dev.blockphone.fabric.client.phone.PhonePin.debugCursor = false;                dev.blockphone.fabric.client.phone.PhonePin.unpin(mc);                mc.setScreen(new PhoneScreen());            });
        }

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
        } else if (scenario.equals("apps")) {
            planApps();
        } else if (scenario.equals("clock")) {
            planClock();
        } else if (scenario.equals("store")) {
            planStore();
        } else if (scenario.equals("strike")) {
            planStrike();
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
        step(1, mc -> {
            phone(mc, p -> p.openApp("messenger"));
            messenger(app -> { });
        });
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
        step(1, mc -> {
            phone(mc, p -> p.openApp("messenger"));
            social(app -> app.switchTab(SocialApp.Tab.FEED));
        });
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
        // Стикеры: свой набор, стикер из снимка (круг с обводкой), панель в чате, отправка.
        step(1, mc -> {
            dev.blockphone.fabric.client.messenger.StickerClient.ensureLoaded();
            messenger(MessengerApp::debugStickers);
        });
        waitFor(mc -> dev.blockphone.fabric.client.messenger.StickerClient.limits() != null);
        step(5, mc -> {
            if (dev.blockphone.fabric.client.messenger.StickerClient.ownCount() == 0) {
                dev.blockphone.fabric.client.messenger.StickerClient.edit(
                        dev.blockphone.common.protocol.packet.StickerPackets.PackAction.CREATE, 0, "Мои стикеры");
            }
        });
        waitFor(mc -> dev.blockphone.fabric.client.messenger.StickerClient.ownCount() > 0);
        step(5, mc -> messenger(app -> app.debugStickerEditor(dev.blockphone.fabric.client.messenger.StickerClient.mine().stream()
                .filter(dev.blockphone.common.protocol.data.StickerData.PackInfo::own).findFirst().orElseThrow().id(), newestPhoto())));
        shot(60, "sticker_editor");
        step(1, mc -> messenger(MessengerApp::debugAddSticker));
        shot(80, "stickers_mine");
        step(1, mc -> messenger(app -> app.openChat(directChat.get())));
        step(10, mc -> messenger(app -> app.debugChatStickers(true, false)));
        shot(40, "sticker_panel");
        step(1, mc -> messenger(app -> app.debugChatStickers(false, true)));
        shot(60, "sticker_sent");
        step(1, mc -> {
            settings.darkTheme = true;
            phone(mc, p -> p.openApp("messenger"));
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
                "[01:04.50]Ночь опустилась на сонный лес и звёзды горят над тихой рекой у старого дома",
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
        // Плавность времени трека (для синхронизации текста): позиция каждый кадр против настоящих часов.
        java.util.List<long[]> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 120; i++) {
            step(1, mc -> samples.add(new long[]{System.nanoTime() / 1_000_000L, MusicPlayer.positionMs()}));
        }
        step(1, mc -> {
            long maxBack = 0;
            long maxStall = 0;
            long maxDrift = 0;
            long stallStart = samples.get(0)[0];
            for (int i = 1; i < samples.size(); i++) {
                long dWall = samples.get(i)[0] - samples.get(0)[0];
                long dPos = samples.get(i)[1] - samples.get(0)[1];
                maxDrift = Math.max(maxDrift, Math.abs(dPos - dWall));
                long step = samples.get(i)[1] - samples.get(i - 1)[1];
                maxBack = Math.max(maxBack, -step);
                if (step != 0) {
                    stallStart = samples.get(i)[0];
                }
                maxStall = Math.max(maxStall, samples.get(i)[0] - stallStart);
            }
            BlockPhoneFabric.LOGGER.info("[DevShots] music timing: max backward {} ms, longest freeze {} ms, max drift from clock {} ms over {} ms",
                    maxBack, maxStall, maxDrift, samples.get(samples.size() - 1)[0] - samples.get(0)[0]);
        });
        step(1, mc -> {
            MusicPlayer.seek(62_000);
            music(MusicApp::openLyrics);
        });
        shot(30, "music_lyrics");
        step(1, mc -> phone(mc, PhoneScreen::back));
        step(1, mc -> phone(mc, PhoneScreen::back));
        step(1, mc -> music(app -> app.switchTab(MusicApp.Tab.YANDEX)));
        shot(40, "music_yandex_login");
        step(1, mc -> music(app -> app.switchTab(MusicApp.Tab.MEDIA)));
        shot(40, "music_media");
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
            settings.lyricsOutline = false;
            MusicPlayer.seek(61_500);
        });
        shot(40, "music_lyrics_no_outline");
        step(1, mc -> settings.lyricsOutline = true);
        step(1, mc -> {
            settings.lyricsMode = "view";
            settings.lyricsScatter = false;
            MusicPlayer.seek(64_600);
        });
        shot(40, "music_lyrics_view");
        // Стена в 4 блоках перед игроком, текст на 12 блоков: он должен встать перед стеной, а не в ней; длинная строка — в два ряда.
        java.util.List<net.minecraft.core.BlockPos> wall = new java.util.ArrayList<>();
        step(1, mc -> {
            settings.lyricsMode = "world";
            settings.lyricsScatter = false;
            settings.lyricsDistance = 12;
            net.minecraft.world.phys.Vec3 look = mc.player.getLookAngle().multiply(1, 0, 1).normalize();
            net.minecraft.world.phys.Vec3 side = new net.minecraft.world.phys.Vec3(-look.z, 0, look.x);
            net.minecraft.world.phys.Vec3 base = mc.player.getEyePosition().add(look.scale(4));
            for (int u = -4; u <= 4; u++) {
                for (int v = -3; v <= 4; v++) {
                    wall.add(net.minecraft.core.BlockPos.containing(base.add(side.scale(u)).add(0, v, 0)));
                }
            }
            var server = mc.getSingleplayerServer();
            var dimension = mc.level.dimension();
            server.execute(() -> wall.forEach(bp -> server.getLevel(dimension).setBlockAndUpdate(bp,
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState())));
        });
        step(20, mc -> MusicPlayer.seek(62_000));
        shot(40, "music_lyrics_wall");
        step(1, mc -> {
            var server = mc.getSingleplayerServer();
            var dimension = mc.level.dimension();
            server.execute(() -> wall.forEach(bp -> server.getLevel(dimension).setBlockAndUpdate(bp,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())));
            settings.lyricsDistance = 6;
            settings.lyricsScatter = true;
        });
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
        step(5, mc -> settingsPage("main"));
        shot(30, "settings_menu");
        step(1, mc -> settingsPage("lyrics"));
        shot(30, "settings_lyrics");
        step(1, mc -> phone(mc, p -> p.openApp(PhoneState.LOCK)));
        shot(30, "lock_music");
        step(1, mc -> phone(mc, p -> p.openApp("settings")));
        step(5, mc -> settingsPage("hud"));
        shot(30, "settings_hud");
        step(1, mc -> settingsPage("music"));
        shot(30, "settings_music");
        step(1, mc -> dev.blockphone.fabric.client.music.MusicRemote.debugOpen(mc, true));
        shot(30, "music_remote");
        step(1, mc -> dev.blockphone.fabric.client.music.MusicRemote.debugOpen(mc, false));
        // Текст песни на экране: все режимы, кроме «в мире».
        for (String mode : new String[]{"left", "right", "actionbar", "subtitles", "top"}) {
            step(1, mc -> {
                mc.setScreen(null);
                settings.lyricsMode = mode;
                MusicPlayer.seek(62_000);
            });
            shot(40, "lyrics_" + mode);
        }
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
        // Микрофон в автоснимках не трогаем; музыка (если играет) попадёт в звук «кружочка».
        step(1, mc -> {
            dev.blockphone.fabric.client.config.PhoneSettings.get().videoNoteMic = false;
            dev.blockphone.fabric.client.telegram.VideoNoteRecorder.start(0);
        });
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
        // Переписка на поддельных данных: альбом, ответ, пересланное, реакции, меню сообщения, полосы над полем.
        step(5, mc -> {
            List<Path> pictures = new ArrayList<>(dev.blockphone.fabric.client.photo.PhotoStore.list().stream()
                    .map(dev.blockphone.fabric.client.photo.PhotoStore.Photo::file).toList());
            // WEBP-стикер (если подложен в папку данных) — проверка плагина WEBP внутри игры.
            Path webp = dev.blockphone.fabric.client.storage.ClientFiles.root().resolve("dev_sticker.webp");
            if (Files.exists(webp)) {
                pictures.add(0, webp);
            }
            try {
                short[] tone = new short[16_000];
                for (int i = 0; i < tone.length; i++) {
                    tone[i] = (short) (Math.sin(i / 5.0) * 5000);
                }
                short[] back = dev.blockphone.fabric.client.telegram.OggOpus.decode(dev.blockphone.fabric.client.telegram.OggOpus.encode(tone));
                BlockPhoneFabric.LOGGER.info("[DevShots] opus round trip {} -> {} samples", tone.length, back.length);
            } catch (Throwable e) {
                BlockPhoneFabric.LOGGER.warn("[DevShots] opus failed", e);
            }
            Path video = null;
            try (var notes = Files.list(dev.blockphone.fabric.client.storage.ClientFiles.root().resolve("telegram").resolve("videonotes"))) {
                video = notes.filter(f -> f.toString().endsWith(".mp4")).findFirst().orElse(null);
            } catch (Exception ignored) {
            }
            long chatId = dev.blockphone.fabric.client.telegram.Telegram.debugInject(pictures, video);
            if (!(mc.screen instanceof PhoneScreen)) {
                mc.setScreen(new PhoneScreen());
            }
            phone(mc, p -> p.openApp("telegram"));
            if (PhoneState.existing("telegram") instanceof dev.blockphone.fabric.client.apps.telegram.TelegramApp app) {
                app.openChat(chatId);
            }
        });
        shot(40, "telegram_chat");
        step(1, mc -> telegramDebug("menu"));
        shot(20, "telegram_menu");
        step(1, mc -> telegramDebug("reply"));
        shot(30, "telegram_reply");
        step(1, mc -> telegramDebug("panel"));
        shot(30, "telegram_stickers");
        step(1, mc -> telegramDebug("gifs"));
        shot(40, "telegram_gifs");
        step(1, mc -> telegramDebug("viewer"));
        shot(30, "telegram_viewer");
        step(1, mc -> telegramDebug("profile"));
        shot(30, "telegram_profile");
        step(1, mc -> telegramDebug("list"));
        shot(30, "telegram_list");
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
        dev.blockphone.fabric.client.apps.hub.HubApp hub = dev.blockphone.fabric.client.apps.hub.HubApp.get();
        if (hub != null) {
            action.accept(hub.socialShown());
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
            repo.setLook(STEVE, new dev.blockphone.common.server.SocialRepository.LookRow(1, 1, 2, 4, 0, 0), System.currentTimeMillis());
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
        dev.blockphone.fabric.client.apps.hub.HubApp hub = dev.blockphone.fabric.client.apps.hub.HubApp.get();
        if (hub != null) {
            action.accept(hub.chatsShown());
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
