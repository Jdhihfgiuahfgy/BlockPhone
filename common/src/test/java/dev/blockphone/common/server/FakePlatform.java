package dev.blockphone.common.server;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** Платформа-заглушка для тестов: складывает отправленные пакеты по игрокам, права задаются вручную. */
final class FakePlatform implements ServerPlatform {
    final Map<UUID, List<Packet>> inbox = new ConcurrentHashMap<>();
    /** Выданные права (кроме тех, что есть у всех по умолчанию). */
    final Map<UUID, Set<Permission>> granted = new ConcurrentHashMap<>();
    /** Отобранные права (даже те, что по умолчанию у всех). */
    final Map<UUID, Set<Permission>> denied = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "test";
    }

    @Override
    public void send(UUID player, byte[] data) {
        // Через кодирование/декодирование — заодно проверяем формат пакетов.
        inbox.computeIfAbsent(player, k -> new CopyOnWriteArrayList<>()).add(Packets.decode(data));
    }

    /** Команды, выполненные приложениями: «player /…» или «console /…». */
    final List<String> commands = new CopyOnWriteArrayList<>();

    @Override
    public boolean runCommand(UUID player, String command, boolean console) {
        commands.add((console ? "console /" : "player /") + command);
        return !command.startsWith("fail");
    }

    @Override
    public String placeholders(UUID player, String text) {
        return text.replace("%test_balance%", "500");
    }

    @Override
    public boolean hasPermissionNode(UUID player, String node, boolean defaultValue) {
        return !node.equals("test.denied") && defaultValue | node.equals("test.allowed");
    }

    @Override
    public boolean hasPermission(UUID player, Permission permission) {
        if (denied.getOrDefault(player, Set.of()).contains(permission)) {
            return false;
        }
        return permission.defaultAllowed() || granted.getOrDefault(player, Set.of()).contains(permission);
    }

    void grant(UUID player, Permission permission) {
        granted.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(permission);
    }

    void deny(UUID player, Permission permission) {
        denied.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(permission);
    }

    /** Все игроки онлайн (для лога сообщений), сообщения в чат игры и команды консоли. */
    final Set<UUID> online = ConcurrentHashMap.newKeySet();
    final Map<UUID, List<String>> chat = new ConcurrentHashMap<>();
    final List<String> consoleCommands = new CopyOnWriteArrayList<>();

    @Override
    public java.util.Collection<UUID> onlinePlayers() {
        return online;
    }

    @Override
    public void sendChat(UUID player, String text) {
        chat.computeIfAbsent(player, k -> new CopyOnWriteArrayList<>()).add(text);
    }

    @Override
    public boolean runConsoleCommand(String command) {
        consoleCommands.add(command);
        return true;
    }

    @Override
    public void info(String message) {
    }

    /** Ожидаемые предупреждения (начало текста): записываются в warnings, остальные валят тест. */
    final List<String> allowedWarnings = new java.util.ArrayList<>();
    final List<String> warnings = new java.util.ArrayList<>();

    @Override
    public void warn(String message, Throwable error) {
        if (allowedWarnings.stream().anyMatch(message::startsWith)) {
            warnings.add(message);
            return;
        }
        throw new AssertionError(message, error);
    }

    <T extends Packet> List<T> of(UUID player, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (Packet p : inbox.getOrDefault(player, List.of())) {
            if (type.isInstance(p)) {
                result.add(type.cast(p));
            }
        }
        return result;
    }

    <T extends Packet> T last(UUID player, Class<T> type) {
        List<T> all = of(player, type);
        assertFalse(all.isEmpty(), "no " + type.getSimpleName() + " for " + player);
        return all.get(all.size() - 1);
    }

    void clear() {
        inbox.clear();
    }
}
