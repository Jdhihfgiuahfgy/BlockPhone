package dev.blockphone.common.apps;

import java.util.Locale;

/**
 * Разрешения клиентского приложения — объявляются в app.json ("permissions": [...]) и показываются игроку.
 * Действие, для которого нет разрешения, не загрузится. Доступа к миру, файлам, аккаунтам, мессенджеру, ленте,
 * музыке и Telegram нет ни при каких разрешениях.
 */
public enum AppPermission {
    /** Хранить свои переменные на устройстве (действия "set", "add"; файл blockphone/appdata/&lt;id&gt;.json, до 64 КБ). */
    STORAGE,
    /** Открывать ссылки в браузере (игрок подтверждает каждую). */
    LINKS,
    /** Копировать текст в буфер обмена (только запись, читать буфер нельзя). */
    CLIPBOARD,
    /** Отправлять команды от имени игрока (игрок подтверждает каждую, видит её текст). */
    COMMANDS,
    /** Работать с серверной частью приложения на сервере (переменные и функции). */
    SERVER;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static AppPermission parse(String value) {
        return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }
}
