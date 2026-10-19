package dev.blockphone.fabric.client.dev;

import com.mojang.blaze3d.platform.NativeImage;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.phone.AppRegistry;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.phone.PhoneState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Только для разработки. Если задана переменная окружения {@code BLOCKPHONE_DEV_SHOTS=<папка>},
 * то в главном меню открывает телефон, делает скриншоты всех приложений (светлая тема на английском, тёмная — на русском)
 * и закрывает игру. Паузы большие: окно в фоне рисует мало кадров. Нужен, чтобы проверять интерфейс без ручного прокликивания.
 */
public final class DevShots {
    private record Step(int delayTicks, Consumer<Minecraft> action) {
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

    private void plan() {
        PhoneSettings settings = PhoneSettings.get();
        boolean originalDark = settings.darkTheme;
        String originalLanguage = settings.language;
        // Светлая тема — на английском, тёмная — на русском (проверка обоих языков).
        for (boolean dark : new boolean[]{false, true}) {
            String suffix = dark ? "_dark" : "";
            steps.add(new Step(1, mc -> {
                settings.darkTheme = dark;
                settings.language = dark ? "ru_ru" : "en_us";
                PhoneState.reset();
                mc.setScreen(new PhoneScreen());
            }));
            steps.add(new Step(30, mc -> shot(mc, "home" + suffix)));
            for (AppRegistry.Entry entry : AppRegistry.visible()) {
                steps.add(new Step(1, mc -> {
                    if (mc.screen instanceof PhoneScreen phone) {
                        phone.openApp(entry.id());
                    }
                }));
                steps.add(new Step(20, mc -> shot(mc, entry.id() + suffix)));
            }
        }
        steps.add(new Step(5, mc -> {
            settings.darkTheme = originalDark;
            settings.language = originalLanguage;
            BlockPhoneFabric.LOGGER.info("[DevShots] done");
            mc.stop();
        }));
    }

    private void tick(Minecraft mc) {
        if (index < 0 && !(mc.screen instanceof TitleScreen)) {
            return;
        }
        if (--wait > 0) {
            return;
        }
        index++;
        if (index >= steps.size()) {
            return;
        }
        Step step = steps.get(index);
        step.action().accept(mc);
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
