package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.music.yandex.YandexBrowse;
import dev.blockphone.fabric.client.music.yandex.YandexCatalog;
import dev.blockphone.fabric.client.music.yandex.YandexMusic;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Настройки «Моей волны», как в приложении Яндекс Музыки: по характеру, под настроение, по языку (варианты — с сервера
 * Яндекса) и волна «под занятие». Изменение настроек перезапускает играющую «Мою волну».
 */
final class YandexWaveSettingsView extends MusicView {
    YandexWaveSettingsView(MusicApp app) {
        super(app);
    }

    @Override
    String title() {
        return tr("music.y.wave_settings");
    }

    @Override
    void render(GuiGraphics g, int mx, int my) {
        YandexCatalog.WaveSettings settings = YandexBrowse.waveSettings();
        if (settings == null) {
            hint(g, tr("music.loading"), height() / 2 - 10);
            return;
        }
        int w = width();
        beginList(g, 0, height());
        int y = 4 - (int) scroll;
        PhoneSettings s = PhoneSettings.get();
        y = chips(g, settings.settings().get("diversity"), y, w, mx, my, () -> s.yandexWaveDiversity, v -> s.yandexWaveDiversity = v);
        y = chips(g, settings.settings().get("moodEnergy"), y, w, mx, my, () -> s.yandexWaveMood, v -> s.yandexWaveMood = v);
        y = chips(g, settings.settings().get("language"), y, w, mx, my, () -> s.yandexWaveLanguage, v -> s.yandexWaveLanguage = v);
        // Занятия: отдельная волна (не меняет «Мою волну»).
        if (!settings.contexts().isEmpty()) {
            section(g, tr("music.y.contexts"), y + 2);
            y += 12;
            y = flow(g, settings.contexts(), y, w, mx, my, o -> false,
                    o -> YandexMusic.playStation(List.of(o.seed()), o.name()));
        }
        y += 4;
        PhoneDraw.wrapped(g, tr("music.y.wave_settings_hint"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.55f, false);
        y += 22;
        endList(g);
        clampScroll(y + (int) scroll, height());
    }

    /** Одна настройка: подпись и «таблетки» вариантов (выбранная — акцентом; «Любое» — пустой seed). */
    private int chips(GuiGraphics g, YandexCatalog.Setting setting, int y, int w, int mx, int my, Supplier<String> get, Consumer<String> set) {
        if (setting == null) {
            return y;
        }
        section(g, setting.name(), y + 2);
        y += 12;
        return flow(g, setting.values(), y, w, mx, my, o -> isDefault(o) ? get.get().isBlank() : o.seed().equals(get.get()), o -> {
            set.accept(isDefault(o) ? "" : o.seed());
            PhoneSettings.get().save();
            if (YandexMusic.wavePlaying()) {
                YandexMusic.playWave();
            }
        });
    }

    private static boolean isDefault(YandexCatalog.Option o) {
        return o.value().equals("default") || o.value().equals("all") || o.value().equals("any");
    }

    private int flow(GuiGraphics g, List<YandexCatalog.Option> options, int y, int w, int mx, int my,
                     java.util.function.Predicate<YandexCatalog.Option> selected, Consumer<YandexCatalog.Option> click) {
        int x = 8;
        for (YandexCatalog.Option o : options) {
            int cw = (int) PhoneDraw.width(o.name(), 0.65f) + 12;
            if (x + cw > w - 8) {
                x = 8;
                y += 17;
            }
            button(g, x, y, cw, 14, o.name(), mx, my, selected.test(o) ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL,
                    () -> click.accept(o));
            x += cw + 4;
        }
        return y + 20;
    }
}
