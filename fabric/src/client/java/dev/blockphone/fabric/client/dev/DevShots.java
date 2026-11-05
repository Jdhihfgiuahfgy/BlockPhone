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
import net.minecraft.world.level.LevelSettings;
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

    /**
     * Проверка неофициального API Яндекс Музыки входом игрока: файл BLOCKPHONE_DEV_PROBE — строки «GET /путь» или
     * «POST /путь {json}» ({uid} — id аккаунта); ответы — в папку снимков probe_<n>.txt (первые 300 КБ).
     */
    private void planYandexProbe() {
        boolean[] done = {false};
        step(5, mc -> new Thread(() -> {
            try {
                String token = dev.blockphone.fabric.client.music.Accounts.get().yandexToken;
                var api = new dev.blockphone.fabric.client.music.yandex.YandexApi(token);
                String uid = api.account().uid();
                List<String> lines = java.nio.file.Files.readAllLines(Path.of(System.getenv("BLOCKPHONE_DEV_PROBE")));
                int n = 0;
                for (String line : lines) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    n++;
                    String[] parts = line.strip().split(" ", 3);
                    String path = parts[1].replace("{uid}", uid);
                    String out;
                    try {
                        String body = parts.length > 2 ? parts[2].replace("{uid}", uid) : null;
                        var r = parts[0].equals("FORM") ? api.rawForm(path, body) : api.raw(parts[0], path, body);
                        String text = r.body() == null ? "" : r.body();
                        out = line + "\nHTTP " + r.code() + "\n" + (text.length() > 300_000 ? text.substring(0, 300_000) : text);
                    } catch (Exception e) {
                        out = line + "\nERROR " + e;
                    }
                    java.nio.file.Files.createDirectories(dir);
                    java.nio.file.Files.writeString(dir.resolve("probe_" + n + ".txt"), out);
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("[DevShots] yandex probe failed", e);
            }
            done[0] = true;
        }, "yandex probe").start());
        waitFor(mc -> done[0]);
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
                        local D = require("data")
                        local list = {"ak47", "m4a1s", "awp", "sg553", "xm1014", "mp7", "negev", "dualies", "r8", "zeus",
                          "knife", "molotov", "c4"}
                        local i = 0
                        local maps, m = {"quarter", "dust", "snow", "office", "lake"}, 0
                        return {key = function(G, key, Sim, api)
                          if key == "9" then
                            m = m % #maps + 1
                            local M = require("maps")
                            local idx = 1
                            for n, d in ipairs(M.LIST) do if d.key == maps[m] then idx = n end end
                            local def = M.LIST[idx]
                            api.start("offline", {map = idx, firstTo = 8, level = 2, bots = 5, team = 0,
                              wingman = def.wingman == true})
                            print("dev map " .. def.key)
                            return true
                          end
                          if key == "6" and G.S then
                            -- бросить молотов и ложную гранату вперёд (проверка моделей в полёте и огня)
                            local P = G.S.players[G.me.id]
                            local fx, fy, fz = Sim.forward(G.me.yaw, 0)
                            for _, wid in ipairs({D.MOLOTOV, D.DECOY}) do
                              P[D.NADE_FIELD[wid]] = 1
                              Sim.throw(G.S, P, wid, P.x + fx * 0.4, P.y + 0.85, P.z + fz * 0.4, fx * 7, 3.5, fz * 7)
                            end
                            print("dev nades")
                            return true
                          end
                          if key == "7" then
                            -- страницы настроек по очереди
                            G.setTab = G.menu == "settings" and (G.setTab % 3 + 1) or 1
                            G.menu = "settings"
                            print("dev settings " .. G.setTab)
                            return true
                          end
                          if key == "8" and G.S then
                            Sim.hurt(G.S, G.S.players[G.me.id], nil, D.HE, 500, false)
                            print("dev spectate")
                            return true
                          end
                          if key ~= "0" or not G.S then return false end
                          i = i % #list + 1
                          local wid = D[list[i]]
                          local P = G.S.players[G.me.id]
                          local w = D.WEAPONS[wid]
                          if w.slot == 1 then P.pri = wid elseif w.slot == 2 then P.sec = wid
                          elseif wid == D.ZEUS then P.zeus = 1
                          elseif D.NADE_FIELD[wid] then P[D.NADE_FIELD[wid]] = 1 elseif w.slot == 5 then P.bomb = true end
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
        String[] guns = {"ak47", "m4a1s", "awp", "sg553", "xm1014", "mp7", "negev", "dualies", "r8", "zeus", "knife",
                "molotov", "c4"};
        for (String gun : guns) {
            step(1, mc -> customApp("blockstrike", a -> a.debugKey("0")));
            shot(12, "strike_w_" + gun);
        }
        step(1, mc -> customApp("blockstrike", a -> {
            a.debugKey("0"); // снова AK — перезарядка
            a.debugKey("r");
        }));
        shot(25, "strike_reload");
        step(1, mc -> customApp("blockstrike", a -> a.debugKey("6")));
        shot(8, "strike_nades_fly");
        shot(40, "strike_nades_fire");
        shot(200, "strike_fight");
        // Наблюдение: свой игрок погибает — камера у бота (прицел, руки, оружие).
        step(1, mc -> customApp("blockstrike", a -> a.debugKey("8")));
        shot(30, "strike_spectate");
        shot(300, "strike_later");
        step(1, mc -> phone(mc, PhoneScreen::back));
        shot(20, "strike_pause");
        for (int tab = 1; tab <= 3; tab++) {
            step(1, mc -> customApp("blockstrike", a -> a.debugKey("7")));
            shot(12, "strike_settings_" + tab);
        }
        // Карты (Квартал, Пустыня, Ледник, Офис, Озеро — «Напарники»): игра с ботами, снимок в начале раунда.
        for (String map : new String[]{"quarter", "dust", "snow", "office", "lake"}) {
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

    /**
     * Режим экрана своих приложений на примере «Блочных гонок» (docs/examples/scripts/racing): меню, «Режим на экране»
     * (приглашение как из меню экрана), лобби на экране, джойстик (телефон набок двумя руками), гонка на двоих (второй
     * игрок — подставной «Alex» из dev.lua) с делением экрана, страница экрана в «Экранах».
     */
    private void planScreensGame() {
        step(1, mc -> {
            mc.setScreen(null);
            var c = mc.player.connection;
            c.sendCommand("time set noon");
            c.sendCommand("weather clear");
            c.sendCommand("gamerule doDaylightCycle false");
            c.sendCommand("tp @s ~ ~ ~ -90 0");
            try {
                Path example = Path.of("..", "..", "docs", "examples", "scripts", "racing");
                Path target = dev.blockphone.fabric.client.apps.custom.CustomApps.folder().resolve("racing");
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
                // Только для снимков: второй «игрок» Alex едет сам (гонка на двоих — экран пополам).
                Files.writeString(target.resolve("dev.lua"), """
                        return function(game, now)
                          if game.phase == "lobby" or game.phase == "countdown" then
                            game:input("Alex", 1, now)
                          else
                            local c = game:carOf("Alex")
                            if c then c.input = game:bot(c) c.seen = now end
                          end
                        end
                        """);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("[DevShots] racing copy failed", e);
            }
            dev.blockphone.fabric.client.apps.custom.CustomApps.scan(true);
        });
        step(20, mc -> {
            var c = mc.player.connection;
            c.sendCommand("fill ~1 ~ ~-4 ~9 ~5 ~4 minecraft:air");
            c.sendCommand("fill ~9 ~ ~-4 ~9 ~4 ~4 minecraft:black_concrete");
        });
        step(20, mc -> {
            net.minecraft.core.BlockPos p = mc.player.blockPosition();
            var data = dev.blockphone.fabric.client.screens.ScreenStore.data();
            data.screens.clear();
            data.speakers.clear();
            data.groups.clear();
            var s = new dev.blockphone.fabric.client.screens.ScreenData.Screen();
            s.id = "devgame";
            s.name = "Гонки";
            s.dim = mc.level.dimension().identifier().toString();
            s.x1 = p.getX() + 9;
            s.y1 = p.getY();
            s.z1 = p.getZ() - 4;
            s.x2 = p.getX() + 9;
            s.y2 = p.getY() + 4;
            s.z2 = p.getZ() + 4;
            s.face = net.minecraft.core.Direction.WEST.get3DDataValue();
            data.screens.add(s);
            dev.blockphone.fabric.client.screens.ScreenStore.changed();
        });
        step(20, mc -> mc.setScreen(new PhoneScreen()));
        step(20, mc -> phone(mc, p -> p.openApp("app.racing")));
        shot(40, "racing_menu");
        // Как «Экраны → экран → Приложение → Блочные гонки»: приглашение на свой экран — приложение начинает лобби.
        step(1, mc -> dev.blockphone.fabric.client.screens.AppDisplays.invite("racing",
                dev.blockphone.fabric.client.screens.AppDisplays.key(mc.getUser().getName(), "devgame")));
        shot(40, "racing_lobby_phone");
        step(1, mc -> mc.setScreen(null));
        step(60, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] racing: {}", dev.blockphone.fabric.client.screens.AppDisplays.debugStatus()));
        shot(20, "racing_screen_lobby");
        // Джойстик (клавиша G у экрана) и газ — «готов».
        step(1, mc -> dev.blockphone.fabric.client.screens.AppDisplays.toggleControl(mc));
        step(1, mc -> dev.blockphone.fabric.client.screens.AppDisplays.debugKeys.add("w"));
        shot(20, "racing_control");
        step(50, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] racing countdown: {}", dev.blockphone.fabric.client.screens.AppDisplays.debugStatus()));
        shot(1, "racing_countdown");
        step(120, mc -> dev.blockphone.fabric.client.screens.AppDisplays.debugKeys.add("d"));
        step(6, mc -> dev.blockphone.fabric.client.screens.AppDisplays.debugKeys.remove("d"));
        shot(1, "racing_race");
        step(1, mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
        shot(30, "racing_hand");
        step(1, mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
        shot(200, "racing_race_later");
        step(1, mc -> {
            dev.blockphone.fabric.client.screens.AppDisplays.debugKeys.clear();
            mc.setScreen(null);
        });
        step(20, mc -> mc.setScreen(new PhoneScreen()));
        step(20, mc -> phone(mc, p -> p.openApp("screens")));
        step(1, mc -> {
            if (dev.blockphone.fabric.client.phone.PhoneState.existing("screens") instanceof dev.blockphone.fabric.client.apps.screens.ScreensApp a) {
                a.debugOpen("devgame");
            }
        });
        shot(40, "racing_screens_app");
        step(1, mc -> mc.setScreen(null));
    }

    /** Браузер (Chromium): копия примера docs/examples/scripts/browser, ожидание скачивания, страница, набок. */
    /** Экраны: стена перед игроком, экран с видео, звук из экрана, приложение и выбор углов прицелом. */
    private void planScreens() {
        if ("spr".equals(System.getenv("BLOCKPHONE_DEV_SCREENS"))) {
            planScreensSoundPhysics();
            return;
        }
        if ("sites".equals(System.getenv("BLOCKPHONE_DEV_SCREENS"))) {
            planScreensSites();
            return;
        }
        if ("game".equals(System.getenv("BLOCKPHONE_DEV_SCREENS"))) {
            planScreensGame();
            return;
        }
        long[] started = new long[1];
        step(1, mc -> {
            mc.setScreen(null);
            var c = mc.player.connection;
            c.sendCommand("time set noon");
            c.sendCommand("weather clear");
            c.sendCommand("gamerule doDaylightCycle false");
            c.sendCommand("tp @s ~ ~ ~ -90 0");
        });
        step(20, mc -> {
            var c = mc.player.connection;
            c.sendCommand("fill ~1 ~ ~-4 ~6 ~5 ~4 minecraft:air");
            c.sendCommand("fill ~6 ~ ~-4 ~6 ~4 ~4 minecraft:white_concrete");
            c.sendCommand("setblock ~2 ~ ~-4 minecraft:note_block");
        });
        step(20, mc -> {
            net.minecraft.core.BlockPos p = mc.player.blockPosition();
            var data = dev.blockphone.fabric.client.screens.ScreenStore.data();
            data.screens.clear();
            data.speakers.clear();
            var s = new dev.blockphone.fabric.client.screens.ScreenData.Screen();
            s.id = "devscreen";
            s.name = "Кино";
            s.dim = mc.level.dimension().identifier().toString();
            s.x1 = p.getX() + 6;
            s.y1 = p.getY();
            s.z1 = p.getZ() - 4;
            s.x2 = p.getX() + 6;
            s.y2 = p.getY() + 4;
            s.z2 = p.getZ() + 4;
            s.face = net.minecraft.core.Direction.WEST.get3DDataValue();
            // BLOCKPHONE_DEV_SCREEN_URL — своя ссылка (например, ролик YouTube) вместо тестового видео.
            String devUrl = System.getenv("BLOCKPHONE_DEV_SCREEN_URL");
            s.url = devUrl != null && !devUrl.isBlank() ? devUrl : "https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/Big_Buck_Bunny_4K.webm/Big_Buck_Bunny_4K.webm.480p.vp9.webm";
            s.paused = false;
            s.volume = 15;
            s.setPosition(devUrl != null && !devUrl.isBlank() ? 0 : 60_000, System.currentTimeMillis());
            data.screens.add(s);
            var sp = new dev.blockphone.fabric.client.screens.ScreenData.Speaker();
            sp.id = "devspeaker";
            sp.dim = s.dim;
            sp.x = p.getX() + 2;
            sp.y = p.getY();
            sp.z = p.getZ() - 4;
            sp.screen = s.id;
            sp.channel = "L";
            data.speakers.add(sp);
            // Правая колонка — объёмный звук (левый/правый канал).
            var right = new dev.blockphone.fabric.client.screens.ScreenData.Speaker();
            right.id = "devright";
            right.dim = s.dim;
            right.x = p.getX() + 2;
            right.y = p.getY();
            right.z = p.getZ() + 4;
            right.screen = s.id;
            right.channel = "R";
            data.speakers.add(right);
            dev.blockphone.fabric.client.screens.ScreenStore.changed();
            started[0] = System.currentTimeMillis();
        });
        waitFor(mc -> dev.blockphone.fabric.client.browser.WebEngine.state() == dev.blockphone.fabric.client.browser.WebEngine.State.READY
                || dev.blockphone.fabric.client.browser.WebEngine.state() == dev.blockphone.fabric.client.browser.WebEngine.State.FAILED
                || System.currentTimeMillis() - started[0] > 600_000);
        // BLOCKPHONE_DEV_SCREENS=audio — минута записей журнала раз в 2 с (проверка звука страницы).
        boolean audioLog = "audio".equals(System.getenv("BLOCKPHONE_DEV_SCREENS"));
        for (int i = 0; i < (audioLog ? 30 : 4); i++) {
            step(audioLog ? 40 : 100, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] screens: {}", dev.blockphone.fabric.client.screens.Screens.debugStatus()));
        }
        shot(20, "screens_world");
        // Перемотка и пауза владельцем.
        step(1, mc -> {
            var s = dev.blockphone.fabric.client.screens.ScreenStore.screen("devscreen");
            s.setPosition(300_000, System.currentTimeMillis());
            dev.blockphone.fabric.client.screens.ScreenStore.changed();
        });
        step(100, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] screens after seek: {}", dev.blockphone.fabric.client.screens.Screens.debugStatus()));
        shot(20, "screens_world_seek");
        step(1, mc -> mc.setScreen(new PhoneScreen()));
        step(20, mc -> phone(mc, p -> p.openApp("screens")));
        shot(40, "screens_app_list");
        step(1, mc -> {
            if (dev.blockphone.fabric.client.phone.PhoneState.existing("screens") instanceof dev.blockphone.fabric.client.apps.screens.ScreensApp a) {
                a.debugOpen("devscreen");
            }
        });
        shot(40, "screens_app_screen");
        step(1, mc -> {
            if (dev.blockphone.fabric.client.phone.PhoneState.existing("screens") instanceof dev.blockphone.fabric.client.apps.screens.ScreensApp a) {
                a.mouseScrolled(10, 10, -40);
            }
        });
        shot(30, "screens_app_screen_bottom");
        step(1, mc -> dev.blockphone.fabric.client.screens.ScreenPicker.start(dev.blockphone.fabric.client.screens.ScreenPicker.Mode.SCREEN, ""));
        shot(40, "screens_pick");
        // Выбор двух углов взглядом: второй экран на той же стене (слева сверху — справа снизу).
        net.minecraft.core.BlockPos[] base = new net.minecraft.core.BlockPos[1];
        step(1, mc -> {
            base[0] = mc.player.blockPosition();
            mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    net.minecraft.world.phys.Vec3.atCenterOf(base[0].offset(6, 3, -3)).add(-0.5, 0, 0));
        });
        shot(20, "screens_pick_hover");
        step(1, mc -> dev.blockphone.fabric.client.screens.ScreenPicker.debugClick());
        step(5, mc -> mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                net.minecraft.world.phys.Vec3.atCenterOf(base[0].offset(6, 1, 0)).add(-0.5, 0, 0)));
        shot(20, "screens_pick_second");
        step(1, mc -> dev.blockphone.fabric.client.screens.ScreenPicker.debugClick());
        shot(40, "screens_created");
        step(1, mc -> {
            BlockPhoneFabric.LOGGER.info("[DevShots] screens created: {}", dev.blockphone.fabric.client.screens.ScreenStore.data().screens.size());
            mc.setScreen(null);
            mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    net.minecraft.world.phys.Vec3.atCenterOf(base[0].offset(6, 2, 0)));
        });
        shot(20, "screens_two");
        // Группы: оба экрана объединены (одна вкладка), колонки — в группе с общей громкостью.
        String[] groupIds = new String[1];
        step(1, mc -> {
            var data = dev.blockphone.fabric.client.screens.ScreenStore.data();
            var grp = new dev.blockphone.fabric.client.screens.ScreenData.Group();
            grp.id = "devgroup";
            grp.name = "Зал";
            grp.kind = "screens";
            grp.sync = true;
            for (var sc : data.screens) {
                grp.members.add(sc.id);
            }
            data.groups.removeIf(x -> true);
            data.groups.add(grp);
            var sg = new dev.blockphone.fabric.client.screens.ScreenData.Group();
            sg.id = "devspk";
            sg.name = "Колонки зала";
            sg.kind = "speakers";
            sg.volume = 50;
            for (var sp : data.speakers) {
                sg.members.add(sp.id);
            }
            data.groups.add(sg);
            data.propagate(data.screen("devscreen"));
            dev.blockphone.fabric.client.screens.ScreenStore.changed();
            groupIds[0] = grp.id;
        });
        step(120, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] screens group: {}", dev.blockphone.fabric.client.screens.Screens.debugStatus()));
        shot(10, "screens_group_sync");
        // Подсветка колонок (сквозь стены, с подписями) и «качание» от басов.
        step(1, mc -> {
            dev.blockphone.fabric.client.screens.Screens.setShowSpeakers(true);
            mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    net.minecraft.world.phys.Vec3.atCenterOf(mc.player.blockPosition().offset(2, 0, -2)));
        });
        for (int i = 0; i < 5; i++) {
            step(7, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] screens pulse: {}", dev.blockphone.fabric.client.screens.Screens.debugStatus()
                    .replaceAll(".*[|] pulse: ", "")));
        }
        shot(5, "screens_speakers_highlight");
        step(1, mc -> dev.blockphone.fabric.client.screens.Screens.setShowSpeakers(false));
        step(1, mc -> mc.setScreen(new PhoneScreen()));
        step(20, mc -> phone(mc, p -> p.openApp("screens")));
        step(1, mc -> {
            if (dev.blockphone.fabric.client.phone.PhoneState.existing("screens") instanceof dev.blockphone.fabric.client.apps.screens.ScreensApp a) {
                a.debugOpenGroup(groupIds[0]);
            }
        });
        shot(30, "screens_group_page");
        step(1, mc -> {
            if (dev.blockphone.fabric.client.phone.PhoneState.existing("screens") instanceof dev.blockphone.fabric.client.apps.screens.ScreensApp a) {
                a.onBack();
            }
            mc.setScreen(null);
        });
        step(1, mc -> {
            var s = dev.blockphone.fabric.client.screens.ScreenStore.screen("devscreen");
            s.paused = true;
            dev.blockphone.fabric.client.screens.ScreenStore.changed();
        });
        step(60, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] screens paused: {}", dev.blockphone.fabric.client.screens.Screens.debugStatus()));
        // Страница сайта с видео (не прямой файл): видео должно занять весь экран.
        step(1, mc -> {
            mc.setScreen(null);
            var s = dev.blockphone.fabric.client.screens.ScreenStore.screen("devscreen");
            s.url = "https://commons.wikimedia.org/wiki/File:Big_Buck_Bunny_4K.webm";
            s.paused = false;
            s.setPosition(30_000, System.currentTimeMillis());
            dev.blockphone.fabric.client.screens.ScreenStore.changed();
        });
        for (int i = 0; i < 3; i++) {
            step(100, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] screens page: {}", dev.blockphone.fabric.client.screens.Screens.debugStatus()));
        }
        shot(10, "screens_world_page");
        // Колонка телефона: музыка проигрывателя телефона звучит из блока рядом.
        float[] volume = new float[1];
        step(1, mc -> {
            volume[0] = PhoneSettings.get().musicVolume;
            dev.blockphone.fabric.client.screens.PhoneSpeakers.debugLoopback = true;
            PhoneSettings.get().musicVolume = 0.2f;
            copyMinecraftMusic(mc);
            dev.blockphone.fabric.client.music.LocalLibrary.rescan();
            var s = dev.blockphone.fabric.client.screens.ScreenStore.screen("devscreen");
            s.on = false;
            var p = mc.player.blockPosition();
            var sp = new dev.blockphone.fabric.client.screens.ScreenData.Speaker();
            sp.id = "devphone";
            sp.dim = s.dim;
            sp.x = p.getX() + 2;
            sp.y = p.getY();
            sp.z = p.getZ() + 3;
            sp.screen = dev.blockphone.fabric.client.screens.ScreenData.PHONE;
            dev.blockphone.fabric.client.screens.ScreenStore.data().speakers.add(sp);
            dev.blockphone.fabric.client.screens.ScreenStore.changed();
        });
        waitFor(mc -> dev.blockphone.fabric.client.music.LocalLibrary.scanned() && !dev.blockphone.fabric.client.music.LocalLibrary.scanning());
        step(1, mc -> dev.blockphone.fabric.client.music.MusicPlayer.play(dev.blockphone.fabric.client.music.LocalLibrary.tracks(), 0, null, "dev"));
        for (int i = 0; i < 2; i++) {
            step(80, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] screens phone: {}", dev.blockphone.fabric.client.screens.Screens.debugStatus()));
        }
        step(1, mc -> mc.setScreen(new PhoneScreen()));
        step(20, mc -> phone(mc, p -> p.openApp("screens")));
        step(1, mc -> {
            if (dev.blockphone.fabric.client.phone.PhoneState.existing("screens") instanceof dev.blockphone.fabric.client.apps.screens.ScreensApp a) {
                a.onBack();
            }
        });
        shot(40, "screens_phone_speakers");
        step(1, mc -> {
            if (dev.blockphone.fabric.client.phone.PhoneState.existing("screens") instanceof dev.blockphone.fabric.client.apps.screens.ScreensApp a) {
                a.debugOpenSpeaker("devphone");
            }
        });
        shot(30, "screens_speaker_page");
        step(1, mc -> {
            if (dev.blockphone.fabric.client.phone.PhoneState.existing("screens") instanceof dev.blockphone.fabric.client.apps.screens.ScreensApp a) {
                a.onBack();
            }
        });
        step(1, mc -> {
            dev.blockphone.fabric.client.music.MusicPlayer.stop();
            PhoneSettings.get().musicVolume = volume[0];
        });
        // Трансляция телефона: экран «Кино» показывает телефон; телефон «на ходу» в углу, потом закрыт.
        step(1, mc -> {
            dev.blockphone.fabric.client.screens.PhoneMirror.debugLoopback = true;
            var s = dev.blockphone.fabric.client.screens.ScreenStore.screen("devscreen");
            s.on = true;
            s.mirror = true;
            dev.blockphone.fabric.client.screens.ScreenStore.changed();
            mc.setScreen(null);
            dev.blockphone.fabric.client.phone.PhonePin.pin(mc);
            mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    net.minecraft.world.phys.Vec3.atCenterOf(base[0].offset(6, 2, 0)));
        });
        step(20, mc -> phone(mc, p -> p.openApp("weather")));
        step(80, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] screens mirror: {}", dev.blockphone.fabric.client.screens.Screens.debugStatus()));
        shot(10, "screens_mirror");
        step(1, mc -> dev.blockphone.fabric.client.phone.PhonePin.unpin(mc));
        shot(40, "screens_mirror_closed");
        step(1, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] screens mirror closed: {}", dev.blockphone.fabric.client.screens.Screens.debugStatus()));
    }

    /** Видеосайты на экране: Rutube, Vimeo, YouTube — плеер без сайта, видео на весь экран, «Управлять страницей». */
    private void planScreensSites() {
        String[] urls = {"https://rutube.ru/video/69fcca9724a5c697d1c105950b0ae56f/", "https://vimeo.com/76979871",
                "https://www.youtube.com/watch?v=aqz-KE-bpKQ"};
        long[] started = new long[1];
        planScreensSoundPhysics(); // стена и экран
        for (int i = 0; i < urls.length; i++) {
            String url = urls[i];
            String name = "site_" + i;
            step(1, mc -> {
                var s = dev.blockphone.fabric.client.screens.ScreenStore.screen("devscreen");
                s.url = url;
                s.paused = false;
                s.setPosition(5_000, System.currentTimeMillis());
                dev.blockphone.fabric.client.screens.ScreenStore.changed();
            });
            for (int k = 0; k < 3; k++) {
                step(100, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] screens {}: {}", name, dev.blockphone.fabric.client.screens.Screens.debugStatus()));
            }
            shot(10, "screens_" + name);
            if (i == 0) {
                step(1, mc -> mc.setScreen(new PhoneScreen()));
                step(20, mc -> phone(mc, p -> p.openApp("screens")));
                step(1, mc -> {
                    if (dev.blockphone.fabric.client.phone.PhoneState.existing("screens") instanceof dev.blockphone.fabric.client.apps.screens.ScreensApp a) {
                        a.debugOpen("devscreen");
                        a.debugPage();
                    }
                });
                shot(60, "screens_page_control");
                // Нажатие на странице записывается; после перезагрузки страницы оно повторяется само.
                step(1, mc -> {
                    if (dev.blockphone.fabric.client.phone.PhoneState.existing("screens") instanceof dev.blockphone.fabric.client.apps.screens.ScreensApp a) {
                        a.mouseClicked(a.contentWidth() / 2.0, a.contentHeight() / 2.0, 0);
                        a.mouseReleased(a.contentWidth() / 2.0, a.contentHeight() / 2.0, 0);
                    }
                });
                step(20, mc -> {
                    var s = dev.blockphone.fabric.client.screens.ScreenStore.screen("devscreen");
                    BlockPhoneFabric.LOGGER.info("[DevShots] screens actions: {}", s.actions);
                    s.rev++;
                    dev.blockphone.fabric.client.screens.ScreenStore.changed();
                });
                step(100, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] screens after reload"));
                step(1, mc -> {
                    if (dev.blockphone.fabric.client.phone.PhoneState.existing("screens") instanceof dev.blockphone.fabric.client.apps.screens.ScreensApp a) {
                        a.onBack();
                        a.onBack();
                    }
                    mc.setScreen(null);
                });
            }
        }
    }

    /** Проверка Sound Physics: колонка экрана за стеной, журнал Sound Physics (debug) показывает обработку. */
    private void planScreensSoundPhysics() {
        long[] started = new long[1];
        step(1, mc -> {
            mc.setScreen(null);
            var c = mc.player.connection;
            c.sendCommand("time set noon");
            c.sendCommand("tp @s ~ ~ ~ -90 0");
        });
        step(20, mc -> {
            var c = mc.player.connection;
            c.sendCommand("fill ~1 ~ ~-4 ~6 ~5 ~4 minecraft:air");
            c.sendCommand("fill ~6 ~ ~-4 ~6 ~4 ~4 minecraft:white_concrete");
        });
        step(20, mc -> {
            net.minecraft.core.BlockPos p = mc.player.blockPosition();
            var data = dev.blockphone.fabric.client.screens.ScreenStore.data();
            data.screens.clear();
            data.speakers.clear();
            var s = new dev.blockphone.fabric.client.screens.ScreenData.Screen();
            s.id = "devscreen";
            s.dim = mc.level.dimension().identifier().toString();
            s.x1 = p.getX() + 6;
            s.y1 = p.getY();
            s.z1 = p.getZ() - 4;
            s.x2 = p.getX() + 6;
            s.y2 = p.getY() + 4;
            s.z2 = p.getZ() + 4;
            s.face = net.minecraft.core.Direction.WEST.get3DDataValue();
            s.url = "https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/Big_Buck_Bunny_4K.webm/Big_Buck_Bunny_4K.webm.480p.vp9.webm";
            s.volume = 10;
            s.setPosition(60_000, System.currentTimeMillis());
            data.screens.add(s);
            dev.blockphone.fabric.client.screens.ScreenStore.changed();
            started[0] = System.currentTimeMillis();
        });
        waitFor(mc -> dev.blockphone.fabric.client.browser.WebEngine.state() == dev.blockphone.fabric.client.browser.WebEngine.State.READY
                || System.currentTimeMillis() - started[0] > 600_000);
        for (int i = 0; i < 4; i++) {
            step(100, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] screens spr: {}", dev.blockphone.fabric.client.screens.Screens.debugStatus()));
        }
    }

    private void planBrowser() {
        long[] started = new long[1];
        step(1, mc -> {
            try {
                Path example = Path.of("..", "..", "docs", "examples", "scripts", "browser");
                Path target = dev.blockphone.fabric.client.apps.custom.CustomApps.folder().resolve("browser");
                Files.createDirectories(target);
                try (var files = Files.list(example)) {
                    for (Path f : files.toList()) {
                        Files.copy(f, target.resolve(f.getFileName().toString()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                dev.blockphone.fabric.client.apps.custom.CustomApps.scan(true);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("[DevShots] browser copy failed", e);
            }
            PhoneSettings.get().language = "ru_ru";
            started[0] = System.currentTimeMillis();
            if (mc.screen == null) {
                mc.setScreen(new PhoneScreen());
            }
        });
        step(20, mc -> phone(mc, p -> p.openApp("app.browser")));
        shot(40, "browser_status");
        waitFor(mc -> {
            var st = dev.blockphone.fabric.client.browser.WebEngine.state();
            return st == dev.blockphone.fabric.client.browser.WebEngine.State.READY
                    || st == dev.blockphone.fabric.client.browser.WebEngine.State.FAILED
                    || System.currentTimeMillis() - started[0] > 600_000;
        });
        step(1, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] browser state {} after {} s: {}",
                dev.blockphone.fabric.client.browser.WebEngine.state(), (System.currentTimeMillis() - started[0]) / 1000,
                dev.blockphone.fabric.client.browser.WebEngine.error()));
        shot(20, "browser_home");
        step(1, mc -> customApp("browser", a -> a.debugSubmit("url", "https://en.wikipedia.org/wiki/Minecraft")));
        shot(200, "browser_page");
        // Нажать в страницу (фокус) и набрать текст — проверка ввода (раньше KEY_TYPED ронял игру).
        step(1, mc -> customApp("browser", a -> a.debugClick(a.contentWidth() / 2.0, a.contentHeight() / 2.0)));
        step(10, mc -> customApp("browser", a -> a.debugType("blockphone test")));
        shot(40, "browser_typed");
        step(1, mc -> customApp("browser", a -> a.debugClick(a.contentWidth() - 11, 10)));
        shot(100, "browser_fullscreen");
    }

    /**
     * Любое своё приложение (уже лежит в blockphone/apps): BLOCKPHONE_DEV_APP — его id, BLOCKPHONE_DEV_APP_STEPS — шаги
     * через «;»: shot:имя, wait:тики, click:x,y, rclick:x,y (правая кнопка), pclick/prclick:x,y (в текущее приложение телефона), submit:поле=текст, type:текст, key:клавиша,
     * back, lang:ru_ru|en_us. Пример: «shot:list;click:80,40;wait:40;shot:chat».
     */
    private void planAppScript() {
        String id = System.getenv().getOrDefault("BLOCKPHONE_DEV_APP", "");
        step(1, mc -> {
            dev.blockphone.fabric.client.apps.custom.CustomApps.scan(true);
            if (mc.screen == null) {
                mc.setScreen(new PhoneScreen());
            }
        });
        step(20, mc -> phone(mc, p -> p.openApp("app." + id)));
        step(40, mc -> {
        });
        for (String raw : System.getenv().getOrDefault("BLOCKPHONE_DEV_APP_STEPS", "shot:app").split(";")) {
            String stepText = raw.strip();
            int colon = stepText.indexOf(':');
            String kind = colon < 0 ? stepText : stepText.substring(0, colon);
            String arg = colon < 0 ? "" : stepText.substring(colon + 1);
            switch (kind) {
                case "shot" -> shot(30, "app_" + arg);
                case "wait" -> step(Integer.parseInt(arg), mc -> {
                });
                case "click", "rclick" -> {
                    String[] xy = arg.split(",");
                    double x = Double.parseDouble(xy[0]);
                    double y = Double.parseDouble(xy[1]);
                    int button = kind.equals("click") ? 0 : 1;
                    step(5, mc -> customApp(id, a -> a.mouseClicked(x, y, button)));
                }
                case "pclick", "prclick" -> {
                    // Нажатие в текущее приложение телефона (рабочий стол и т. п.), не только в своё приложение.
                    String[] xy = arg.split(",");
                    double x = Double.parseDouble(xy[0]);
                    double y = Double.parseDouble(xy[1]);
                    int button = kind.equals("pclick") ? 0 : 1;
                    step(5, mc -> dev.blockphone.fabric.client.phone.PhoneState.current().mouseClicked(x, y, button));
                }
                case "submit" -> {
                    int eq = arg.indexOf('=');
                    step(5, mc -> customApp(id, a -> a.debugSubmit(arg.substring(0, eq), arg.substring(eq + 1))));
                }
                case "type" -> step(5, mc -> customApp(id, a -> a.debugType(arg)));
                case "key" -> step(5, mc -> customApp(id, a -> a.debugKey(arg)));
                case "back" -> step(5, mc -> phone(mc, PhoneScreen::back));
                case "lang" -> step(5, mc -> PhoneSettings.get().language = arg);
                default -> BlockPhoneFabric.LOGGER.warn("[DevShots] unknown app step: {}", stepText);
            }
        }
    }

    /** Часы: будильники (список и редактор), секундомер с кругами, таймер (выбор и идёт), звонок. */
    private void planClock() {
        // «Отзывы» и предупреждение перед входом в Telegram.
        step(1, mc -> {
            if (mc.screen == null) {
                mc.setScreen(new PhoneScreen());
            }
            phone(mc, p -> p.openApp("feedback"));
        });
        shot(30, "feedback_app");
        boolean[] warned = new boolean[1];
        step(1, mc -> {
            warned[0] = PhoneSettings.get().telegramBetaAccepted;
            PhoneSettings.get().telegramBetaAccepted = false;
            phone(mc, p -> p.openApp("telegram"));
        });
        shot(30, "telegram_beta_warning");
        step(1, mc -> {
            PhoneSettings.get().telegramBetaAccepted = warned[0];
            phone(mc, PhoneScreen::goHome);
        });
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
                LevelSettings level = dev.blockphone.fabric.client.compat.ClientCompat.devLevelSettings("BlockPhone Dev");
                mc.createWorldOpenFlows().createFreshLevel(WORLD, level, new WorldOptions(20261019L, true, false),
                        WorldPresets::createNormalWorldDimensions, new TitleScreen());
            }
        });
        waitFor(mc -> mc.level != null && mc.player != null && mc.screen == null);
        boolean world = scenario.equals("all") || scenario.equals("hand");
        if (!world) {
            step(100, mc -> mc.setScreen(new PhoneScreen())); // мир прогрузился — сразу к своему сценарию
        } else {
            shot(200, "world_game");
            step(1, mc -> mc.setScreen(new PhoneScreen()));
            shot(30, "world_home");
            step(1, mc -> phone(mc, p -> p.openApp("weather")));
            shot(20, "world_weather");
            // Режим «на ходу»: телефон прикреплён в углу, мышь у игры (курсора нет).
            step(1, mc -> {
                mc.setScreen(null);
                dev.blockphone.fabric.client.phone.PhonePin.pin(mc);
            });
            shot(30, "world_compact");
            // Как при зажатом Alt: курсор, телефоном можно пользоваться.
            step(1, mc -> dev.blockphone.fabric.client.phone.PhonePin.debugCursor = true);
            shot(30, "world_compact_cursor");
            // Телефон в руке: свой персонаж от третьего лица спереди (рука поднята, пока экран телефона открыт).
            step(1, mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
            shot(30, "world_phone_in_hand");
            step(1, mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
            step(1, mc -> {
                dev.blockphone.fabric.client.phone.PhonePin.debugCursor = false;
                dev.blockphone.fabric.client.phone.PhonePin.unpin(mc);
                mc.setScreen(new PhoneScreen());
            });
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
        } else if (scenario.equals("browser")) {
            planBrowser();
        } else if (scenario.equals("app")) {
            planAppScript();
        } else if (scenario.equals("soundcloud")) {
            planSoundCloud(settings);
        } else if (scenario.equals("yandex")) {
            planYandex(settings);
        } else if (scenario.equals("yprobe")) {
            planYandexProbe();
        } else if (scenario.equals("together")) {
            planTogether(settings);
        } else if (scenario.equals("clock")) {
            planClock();
        } else if (scenario.equals("store")) {
            planStore();
        } else if (scenario.equals("strike")) {
            planStrike();
        } else if (scenario.equals("screens")) {
            planScreens();
        } else if (scenario.equals("hand")) {
            // только общая часть (мир, телефон, «на ходу», телефон в руке)
            // «На ходу» набок: курсор показан, открыта галерея.
            step(1, mc -> {
                mc.setScreen(null);
                PhoneSettings.get().compactLandscape = true;
                dev.blockphone.fabric.client.phone.PhonePin.pin(mc);
                dev.blockphone.fabric.client.phone.PhonePin.debugCursor = true;
            });
            step(20, mc -> phone(mc, p -> p.openApp("gallery")));
            shot(40, "world_compact_side");
            step(1, mc -> {
                dev.blockphone.fabric.client.phone.PhonePin.debugCursor = false;
                dev.blockphone.fabric.client.phone.PhonePin.unpin(mc);
                PhoneSettings.get().compactLandscape = false;
            });
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

    /**
     * Совместное прослушивание (сервер комнат — BLOCKPHONE_LISTEN_URL, обычно wrangler dev): сначала ведущий — играет
     * трек Minecraft, создаёт комнату (код — в лог «[DevShots] together host room …»), снимки `together_lobby`,
     * `together_host`; затем гость — код из файла `together_guest_code.txt` в папке игры (его пишет проверочный скрипт),
     * снимок `together_guest`. Гость слушает через Яндекс (Spotify на ПК не открывается).
     */
    private void planTogether(PhoneSettings settings) {
        float originalVolume = settings.musicVolume;
        String originalVia = settings.togetherVia;
        step(1, mc -> {
            settings.musicVolume = 0.05f;
            settings.togetherVia = "yandex";
            copyMinecraftMusic(mc);
            LocalLibrary.rescan();
            PhoneState.setCurrent(PhoneState.HOME);
            mc.setScreen(new PhoneScreen());
        });
        waitFor(mc -> LocalLibrary.scanned() && !LocalLibrary.scanning());
        step(10, mc -> phone(mc, p -> p.openApp("music")));
        step(1, mc -> music(app -> app.switchTab(MusicApp.Tab.TOGETHER)));
        shot(30, "together_lobby");
        step(1, mc -> MusicPlayer.play(LocalLibrary.tracks(), 0, null, "Моя музыка"));
        step(40, mc -> dev.blockphone.fabric.client.music.together.ListenTogether.create(err ->
                BlockPhoneFabric.LOGGER.info("[DevShots] together create: {}", err == null ? "ok" : err)));
        waitFor(mc -> dev.blockphone.fabric.client.music.together.ListenTogether.link()
                == dev.blockphone.fabric.client.music.together.ListenTogether.Link.ONLINE);
        step(1, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] together host room {}",
                dev.blockphone.fabric.client.music.together.ListenTogether.code()));
        // Скрипт-гость успевает войти; ведущий перематывает и ставит паузу — гость должен это получить.
        step(200, mc -> MusicPlayer.seek(30_000));
        step(60, mc -> MusicPlayer.pause());
        shot(60, "together_host");
        step(1, mc -> MusicPlayer.toggle());
        step(60, mc -> {
            dev.blockphone.fabric.client.music.together.ListenTogether.leave();
            MusicPlayer.stop();
        });
        Path codeFile = Path.of("together_guest_code.txt");
        waitFor(mc -> Files.exists(codeFile));
        step(20, mc -> {
            try {
                String code = Files.readString(codeFile).strip();
                BlockPhoneFabric.LOGGER.info("[DevShots] together guest join {}: {}", code,
                        dev.blockphone.fabric.client.music.together.ListenTogether.join(code));
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("[DevShots] together code", e);
            }
        });
        shot(160, "together_guest");
        step(1, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] together guest status {} track '{}' members {}",
                dev.blockphone.fabric.client.music.together.ListenTogether.guestStatus(),
                dev.blockphone.fabric.client.music.together.ListenTogether.shared().title(),
                dev.blockphone.fabric.client.music.together.ListenTogether.members()));
        step(1, mc -> {
            dev.blockphone.fabric.client.music.together.ListenTogether.leave();
            settings.musicVolume = originalVolume;
            settings.togetherVia = originalVia;
            try {
                Files.deleteIfExists(codeFile);
            } catch (Exception ignored) {
                // не важно
            }
        });
    }

    /** SoundCloud во вкладке «Музыки» (сайт в браузере): страница, кодеки Chromium, проигрывание первого трека. */
    private void planSoundCloud(PhoneSettings settings) {
        step(1, mc -> {
            PhoneState.setCurrent(PhoneState.HOME);
            mc.setScreen(new PhoneScreen());
        });
        step(10, mc -> phone(mc, p -> p.openApp("music")));
        step(20, mc -> music(a -> a.switchTab(MusicApp.Tab.SOUNDCLOUD)));
        waitFor(mc -> dev.blockphone.fabric.client.browser.WebEngine.state() == dev.blockphone.fabric.client.browser.WebEngine.State.READY
                || dev.blockphone.fabric.client.browser.WebEngine.state() == dev.blockphone.fabric.client.browser.WebEngine.State.FAILED);
        shot(400, "soundcloud_page");
        step(1, mc -> dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.view().eval(
                "console.log('__bpsc-probe:'+JSON.stringify({aac:window.MediaSource&&MediaSource.isTypeSupported('audio/mp4; codecs=\\\"mp4a.40.2\\\"'),"
                        + "opus:window.MediaSource&&MediaSource.isTypeSupported('audio/webm; codecs=\\\"opus\\\"'),"
                        + "mp3:document.createElement('audio').canPlayType('audio/mpeg'),ua:navigator.userAgent}))"));
        // Окно согласия на куки — «Отклонить всё» (только необходимые).
        step(20, mc -> dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.view().eval(
                "(function(){var b=document.querySelector('#onetrust-reject-all-handler');console.log('__bpsc-probe:reject '+!!b);if(b)b.click();})()"));
        step(20, mc -> dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.view().load("https://soundcloud.com/forss/flickermood"));
        step(200, mc -> dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.view().eval(
                "(function(){var b=Array.from(document.querySelectorAll('.sc-button-play,.playButton')).find(function(x){return !x.classList.contains('playControl')});console.log('__bpsc-probe:play button '+!!b);if(b)b.click();})()"));
        for (int i = 0; i < 4; i++) {
            step(100, mc -> dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.view().eval(
                    "(function(){var pw=document.querySelector('.playbackTimeline__progressWrapper');var pc=document.querySelector('.playControl');"
                            + "console.log('__bpsc-probe:state '+(pw&&pw.getAttribute('aria-valuenow'))+'/'+(pw&&pw.getAttribute('aria-valuemax'))"
                            + "+' playing='+(pc&&pc.className)+' title='+(navigator.mediaSession&&navigator.mediaSession.metadata&&navigator.mediaSession.metadata.title))})()"));
        }
        shot(20, "soundcloud_playing");
        step(1, mc -> dev.blockphone.fabric.client.music.soundcloud.SoundCloudWeb.pause());
    }

    /** Яндекс Музыка входом игрока: главная, настройки волны, коллекция, книги, книга, артист, поиск, волна и меню трека. */
    private void planYandex(PhoneSettings settings) {
        float originalVolume = settings.musicVolume;
        step(1, mc -> {
            settings.musicVolume = 0.03f;
            PhoneState.setCurrent(PhoneState.HOME);
            mc.setScreen(new PhoneScreen());
        });
        step(10, mc -> phone(mc, p -> p.openApp("music")));
        step(20, mc -> music(a -> a.switchTab(MusicApp.Tab.SOUNDCLOUD)));
        shot(80, "soundcloud");
        step(20, mc -> music(a -> a.debugYandex("home")));
        shot(120, "yandex_home");
        step(1, mc -> music(a -> a.debugYandex("wave")));
        shot(80, "yandex_wave_settings");
        step(1, mc -> music(a -> a.debugYandex("collection")));
        shot(80, "yandex_collection");
        step(1, mc -> music(a -> a.debugYandex("books")));
        shot(100, "yandex_books");
        step(1, mc -> music(a -> a.debugYandex("album:22391418")));
        shot(80, "yandex_book");
        step(1, mc -> music(a -> a.debugYandex("artist:249991")));
        shot(80, "yandex_artist");
        shot(100, "yandex_artist_later");
        step(1, mc -> music(a -> a.debugYandex("search:Oxxxymiron")));
        shot(100, "yandex_search");
        shot(100, "yandex_search_later");
        step(1, mc -> dev.blockphone.fabric.client.music.yandex.YandexMusic.playWave());
        step(200, mc -> BlockPhoneFabric.LOGGER.info("[DevShots] yandex wave: {} {} ({}), queue {}", MusicPlayer.state(),
                MusicPlayer.current() == null ? "-" : MusicPlayer.current().label(), MusicPlayer.queueName(), MusicPlayer.queue().size()));
        step(1, mc -> music(a -> a.debugYandex("menu")));
        shot(30, "yandex_track_menu");
        step(1, mc -> {
            MusicPlayer.stop();
            settings.musicVolume = originalVolume;
        });
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
