package dev.blockphone.common.server;

import java.util.UUID;

/**
 * То, что серверная логика BlockPhone требует от платформы (Fabric-сервер, Paper, Velocity).
 * Реализации должны быть потокобезопасными: методы вызываются из фонового потока BlockPhone.
 */
public interface ServerPlatform {
    /** "fabric", "paper", "velocity" — уходит клиенту в приветствии (для отладки). */
    String name();

    /** Отправить игроку байты канала blockphone:main (если он ещё онлайн). */
    void send(UUID player, byte[] data);

    /**
     * Есть ли у игрока право (LuckPerms и т. п.). Если система прав ничего не знает об узле —
     * {@link Permission#defaultAllowed()} для обычных прав и «оператор ли игрок» для модераторских.
     * Вызывается из фонового потока.
     */
    default boolean hasPermission(UUID player, Permission permission) {
        return permission.defaultAllowed();
    }

    /** Право по произвольному узлу (свои приложения: "permission"). defaultValue — если система прав узел не знает. */
    default boolean hasPermissionNode(UUID player, String node, boolean defaultValue) {
        return defaultValue;
    }

    /**
     * Выполнить команду (без «/») от имени игрока или консоли. Вызывается в главном потоке.
     * false — платформа не умеет или игрока нет.
     */
    default boolean runCommand(UUID player, String command, boolean console) {
        return false;
    }

    /** Подставить плейсхолдеры платформы (PlaceholderAPI на Paper). Вызывается в главном потоке. */
    default String placeholders(UUID player, String text) {
        return text;
    }

    /** Выполнить в главном потоке сервера (для Simple Voice Chat). По умолчанию — сразу. */
    default void runOnMain(Runnable task) {
        task.run();
    }

    /** Где игрок: мир и координаты (для громкой связи в звонке). */
    record Position(String world, double x, double y, double z) {
        public double distanceSq(Position other) {
            if (!world.equals(other.world)) {
                return Double.MAX_VALUE;
            }
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** Позиции игроков онлайн (вызывается в главном потоке). Пусто — платформа не знает (прокси). */
    default java.util.Map<UUID, Position> positions() {
        return java.util.Map.of();
    }

    /** Игроки онлайн (все, не только с модом) — кому показывать лог сообщений. По умолчанию — никто. */
    default java.util.Collection<UUID> onlinePlayers() {
        return java.util.List.of();
    }

    /** Сообщение в чат игры (текст с цветовыми кодами §). Вызывается в главном потоке ({@link #runOnMain}). */
    default void sendChat(UUID player, String text) {
    }

    /** Команда от имени консоли (бан в телефоне → команда сервера). Вызывается в главном потоке. */
    default boolean runConsoleCommand(String command) {
        return false;
    }

    void info(String message);

    void warn(String message, Throwable error);
}
