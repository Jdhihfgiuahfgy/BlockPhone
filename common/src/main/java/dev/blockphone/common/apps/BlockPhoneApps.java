package dev.blockphone.common.apps;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Java-API серверной части приложений для плагинов и модов сервера (Paper, Fabric, Velocity, BungeeCord).
 * Описание и пример банка — docs/CUSTOM_APPS.md, раздел «Java-API».
 *
 * <pre>{@code
 * BlockPhoneApps.handler("bank", new BlockPhoneApps.AppHandler() {
 *     public Map<String, String> variables(UUID player, String page) { return Map.of("balance", "100"); }
 *     public AppResult call(UUID player, String function, Map<String, String> inputs) { return AppResult.ok("Готово"); }
 * });
 * }</pre>
 * Обработчики вызываются в главном потоке сервера. Методы класса потокобезопасны.
 */
public final class BlockPhoneApps {
    /** Логика серверной части. Все методы необязательны. */
    public interface AppHandler {
        /** Переменные «{имя}» для страницы {@code page} у игрока (например, баланс). */
        default Map<String, String> variables(UUID player, String page) {
            return Map.of();
        }

        /**
         * Функция, вызванная действием {"server": "имя"}. inputs — значения полей страницы (если у функции в server.json
         * описаны "inputs" — уже проверенные). Вернуть {@link AppResult#ok(String)}, {@link AppResult#error(String)},
         * {@link AppResult#open(String)}; null — функцию не знаю.
         */
        default AppResult call(UUID player, String function, Map<String, String> inputs) {
            return null;
        }
    }

    private static final Map<String, AppHandler> HANDLERS = new ConcurrentHashMap<>();
    private static final List<ServerPart> PARTS = new CopyOnWriteArrayList<>();
    private static volatile Runnable onChange = () -> {
    };

    /** Подключить Java-логику к приложению {@code appId} (серверного файла может не быть). */
    public static void handler(String appId, AppHandler handler) {
        HANDLERS.put(appId, handler);
        onChange.run();
    }

    public static AppHandler handler(String appId) {
        return HANDLERS.get(appId);
    }

    /** Серверная часть из кода (JSON как в папке apps). Замена — по id. */
    public static ServerPart register(String serverJson) throws AppLoader.AppException {
        ServerPart part = ServerPart.parse(serverJson, "app");
        PARTS.removeIf(p -> p.id().equals(part.id()));
        PARTS.add(part);
        onChange.run();
        return part;
    }

    public static void unregister(String appId) {
        PARTS.removeIf(p -> p.id().equals(appId));
        HANDLERS.remove(appId);
        onChange.run();
    }

    public static List<ServerPart> registered() {
        return List.copyOf(PARTS);
    }

    /** Все id приложений, у которых есть Java-обработчик. */
    public static java.util.Set<String> handlerIds() {
        return java.util.Set.copyOf(HANDLERS.keySet());
    }

    /** Для сервера BlockPhone: узнать, что состав изменился. */
    public static void onChange(Runnable listener) {
        onChange = listener == null ? () -> {
        } : listener;
    }

    private BlockPhoneApps() {
    }
}
