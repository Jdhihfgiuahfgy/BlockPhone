package dev.blockphone.common.apps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;

/**
 * Клиентское приложение из папки игрока {@code blockphone/apps/} (формат — docs/CUSTOM_APPS.md).
 * Это данные, а не код: страницы, элементы и действия из фиксированного набора.
 *
 * @param id          латиница/цифры/«_»/«-»; по нему сервер находит серверную часть и блокирует приложение
 * @param name        название: строка или {"ru_ru": "...", "en_us": "..."}
 * @param version     версия (для игрока)
 * @param author      автор (для игрока)
 * @param color       цвет плитки (ARGB)
 * @param icon        PNG иконки или null
 * @param permissions что приложению разрешено (см. {@link AppPermission}); всё остальное телефон не даст сделать
 * @param start       первая страница
 * @param order       место на рабочем столе
 * @param pages       страницы; у элементов с действием — "_a" (id действия)
 * @param actions     id действия → описание
 * @param inputs      id страницы → (id поля → описание поля)
 * @param sha256      хэш файла app.json (+ кода скриптов; сервер может заблокировать конкретную версию)
 * @param script      скриптовая часть (Lua) или null — приложение только из страниц
 */
public record AppDefinition(String id, JsonElement name, String version, String author, int color, byte[] icon,
                            Set<AppPermission> permissions, String start, int order, JsonObject pages,
                            Map<String, JsonObject> actions, Map<String, Map<String, JsonObject>> inputs, String sha256,
                            AppScript script) {

    public AppDefinition(String id, JsonElement name, String version, String author, int color, byte[] icon,
                         Set<AppPermission> permissions, String start, int order, JsonObject pages,
                         Map<String, JsonObject> actions, Map<String, Map<String, JsonObject>> inputs, String sha256) {
        this(id, name, version, author, color, icon, permissions, start, order, pages, actions, inputs, sha256, null);
    }

    /** Название на нужном языке (ru_ru, en_us…). */
    public String name(String language) {
        return AppText.pick(name, language);
    }

    public boolean has(AppPermission permission) {
        return permissions.contains(permission);
    }
}
