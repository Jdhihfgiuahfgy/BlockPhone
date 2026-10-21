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
 */
public record AppScript(String main, Map<String, String> sources, Map<String, byte[]> assets, boolean landscape,
                        boolean fullscreen, List<String> hosts) {
    /** Пределы: файлов кода, размер одного и всего кода; картинок — число и размер. */
    public static final int MAX_SOURCES = 64;
    public static final int MAX_SOURCE_CHARS = 256 * 1024;
    public static final int MAX_TOTAL_SOURCE = 1024 * 1024;
    public static final int MAX_ASSETS = 256;
    public static final int MAX_ASSET_BYTES = 1024 * 1024;
    public static final int MAX_TOTAL_ASSETS = 16 * 1024 * 1024;

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
