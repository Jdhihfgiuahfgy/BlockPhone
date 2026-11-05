package dev.blockphone.common.apps;

import java.util.List;
import java.util.Map;

/**
 * Скриптовая часть приложения (app.json: "script"). Код — Lua; выполняется телефоном в песочнице (без доступа к Java,
 * файлам, миру, аккаунтам и данным мессенджеров). Формат — docs/CUSTOM_APPS.md, раздел «Скрипты».
 *
 * @param main        главный файл (относительный путь, например "main.lua")
 * @param sources     все файлы *.lua папки приложения: путь (через «/») → текст
 * @param assets      картинки *.png папки: путь → байты (для gfx.image)
 * @param landscape   начальная ориентация — набок (скрипт может поменять)
 * @param fullscreen  начать в полном экране (без строки состояния, заголовка и навигации)
 * @param hosts       сайты, к которым можно обращаться (разрешение internet); «*.example.com» — с поддоменами
 * @param relay       свой сервер комнат для net.* без плагина (адрес https://…; null — общий сервер BlockPhone)
 */
public record AppScript(String main, Map<String, String> sources, Map<String, byte[]> assets, boolean landscape,
                        boolean fullscreen, List<String> hosts, String relay) {
    /** Пределы: файлов кода, размер одного и всего кода; картинок — число и размер. */
    public static final int MAX_SOURCES = 128;
    public static final int MAX_SOURCE_CHARS = 512 * 1024;
    public static final int MAX_TOTAL_SOURCE = 2 * 1024 * 1024;
    public static final int MAX_ASSETS = 1024;
    public static final int MAX_ASSET_BYTES = 2 * 1024 * 1024;
    public static final int MAX_TOTAL_ASSETS = 48 * 1024 * 1024;
    /** Звуки (*.ogg, *.wav, *.mp3) — тоже в assets: один не больше 4 МБ (общий предел — MAX_TOTAL_ASSETS). */
    public static final int MAX_SOUND_BYTES = 4 * 1024 * 1024;

    public static boolean isSound(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".ogg") || lower.endsWith(".wav") || lower.endsWith(".mp3");
    }

    /** Разрешён ли хост (без порта, в нижнем регистре). */
    public boolean hostAllowed(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        for (String allowed : hosts) {
            if (allowed.startsWith("*.") ? h.equals(allowed.substring(2)) || h.endsWith(allowed.substring(1)) : h.equals(allowed)) {
                return true;
            }
        }
        return false;
    }
}
