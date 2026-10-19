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
        String originalLanguage = settings.language;
        step(1, mc -> mc.options.pauseOnLostFocus = false);

        // 1. Все приложения в главном меню.
        for (boolean dark : new boolean[]{false, true}) {
            String suffix = dark ? "_dark" : "";
            step(1, mc -> {
                settings.darkTheme = dark;
                settings.language = dark ? "ru_ru" : "en_us";
                PhoneState.reset();
                mc.setScreen(new PhoneScreen());
            });
            shot(30, "menu_home" + suffix);
            for (AppRegistry.Entry entry : AppRegistry.visible()) {
                step(1, mc -> phone(mc, p -> p.openApp(entry.id())));
                shot(20, "menu_" + entry.id() + suffix);
            }
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

        step(5, mc -> {
            settings.darkTheme = originalDark;
            settings.language = originalLanguage;
            CameraMode.SETTINGS.reset();
            BlockPhoneFabric.LOGGER.info("[DevShots] done");
            mc.stop();
        });
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
