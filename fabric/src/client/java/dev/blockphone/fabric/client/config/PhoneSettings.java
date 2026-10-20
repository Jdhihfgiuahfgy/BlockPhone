package dev.blockphone.fabric.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.blockphone.fabric.BlockPhoneFabric;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Настройки телефона игрока. Хранятся в {@code config/blockphone/settings.json}.
 * Поля публичные — их напрямую меняет приложение «Настройки», после чего вызывается {@link #save()}.
 */
public final class PhoneSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PhoneSettings instance;

    /** "auto" — как в игре, иначе код языка: "ru_ru", "en_us". */
    public String language = "auto";
    /** Индекс обоев из {@link dev.blockphone.fabric.client.phone.PhoneTheme#WALLPAPERS}. */
    public int wallpaper = 0;
    /** Свои обои: имя файла в {@code blockphone/wallpapers/}; null — используются стандартные. */
    public String customWallpaper = null;
    /** Тёмная тема приложений (по умолчанию). */
    public boolean darkTheme = true;
    /** Акцентный цвет (ARGB): выделения, кнопки, свои сообщения. */
    public int accentColor = 0xFF4C8DF6;
    /** 24-часовой формат часов в строке состояния. */
    public boolean clock24h = true;
    /** Показывать в строке состояния игровое время вместо реального. */
    public boolean gameTimeClock = false;
    /** Анимация выезда телефона. */
    public boolean animations = true;
    /** Громкость музыкального плеера 0..1 (умножается на общую громкость игры). */
    public float musicVolume = 0.7f;
    /** Показывать виджет «сейчас играет» на экране игры. */
    public boolean musicHud = true;
    /** Экран блокировки с уведомлениями при открытии телефона (если он был закрыт дольше 15 секунд). */
    public boolean lockScreen = true;
    /** Режим «на ходу»: телефон маленький в правом нижнем углу, можно ходить. */
    public boolean compactPhone = false;
    /** Текст песни в мире (если у трека есть текст). */
    public boolean lyricsWorld = true;
    /** "world" — строка остаётся там, где появилась; "view" — следует за взглядом. */
    public String lyricsMode = "world";
    /** Размер текста 1..4. */
    public int lyricsSize = 2;
    /** Расстояние до текста, блоков. */
    public int lyricsDistance = 6;
    /** Слова разного размера и чуть вразброс. */
    public boolean lyricsScatter = true;
    /** Последнее появившееся слово — акцентным цветом. */
    public boolean lyricsAccentWord = true;
    /** Сдвиг текста относительно музыки, мс (плюс — раньше). */
    public int lyricsOffsetMs = 0;
    /** Искать текст в интернете (LRCLIB), если его нет в файлах. */
    public boolean lyricsOnline = true;

    public static PhoneSettings get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("blockphone");
    }

    private static Path file() {
        return dir().resolve("settings.json");
    }

    private static PhoneSettings load() {
        Path file = file();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                PhoneSettings loaded = GSON.fromJson(reader, PhoneSettings.class);
                if (loaded != null) {
                    return loaded;
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Failed to read {}, using defaults", file, e);
            }
        }
        return new PhoneSettings();
    }

    public void save() {
        try {
            Files.createDirectories(dir());
            try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            BlockPhoneFabric.LOGGER.warn("Failed to save phone settings", e);
        }
    }
}
