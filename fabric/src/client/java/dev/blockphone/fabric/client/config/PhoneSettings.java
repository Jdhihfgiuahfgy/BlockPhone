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
    /** Тёмная тема приложений. */
    public boolean darkTheme = false;
    /** 24-часовой формат часов в строке состояния. */
    public boolean clock24h = true;
    /** Показывать в строке состояния игровое время вместо реального. */
    public boolean gameTimeClock = false;
    /** Анимация выезда телефона. */
    public boolean animations = true;

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
