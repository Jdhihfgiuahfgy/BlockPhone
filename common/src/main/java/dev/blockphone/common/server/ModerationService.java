package dev.blockphone.common.server;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.data.MessengerData;
import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.ChatType;
import dev.blockphone.common.protocol.data.ModerationData;
import dev.blockphone.common.protocol.data.ModerationData.LogEntry;
import dev.blockphone.common.protocol.data.ModerationData.PlayerStatus;
import dev.blockphone.common.protocol.packet.ModerationPackets;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.common.server.ModerationRepository.Punishment;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Панель модерации и наказания: мут (нельзя писать сообщения, посты и комментарии), бан в телефоне (мессенджер, лента,
 * фото, голосовые, звонки — всё, кроме сведений о сессии), снятие, список наказанных, журнал, просмотр чатов игрока
 * (право {@link Permission#MOD_CHATS}, каждое открытие пишется в журнал) и лог сообщений в чат игры
 * ({@link ModerationSettings.ChatLog}, видят игроки с правом {@link Permission#CHAT_LOG}). Работает в потоке бэкенда.
 */
public final class ModerationService {
    private static final UUID SYSTEM = new UUID(0, 0);
    private static final int SEARCH_LIMIT = 30;

    private final Backend backend;
    private final ModerationRepository repo;
    /** Бан игрока: проверяется на каждый пакет, поэтому помнится 30 с (наказание через панель сбрасывает запись). */
    private record CachedBan(Punishment ban, long checkedAt) {
    }

    private static final long BAN_CACHE_MS = 30_000;
    private final Map<UUID, CachedBan> banCache = new java.util.HashMap<>();

    ModerationService(Backend backend) {
        this.backend = backend;
        this.repo = new ModerationRepository(backend.database());
    }

    ModerationRepository repo() {
        return repo;
    }

    // --- Проверки для других сервисов ---

    /** Действующий бан или null. */
    Punishment banned(UUID player) throws SQLException {
        long now = System.currentTimeMillis();
        CachedBan cached = banCache.get(player);
        if (cached == null || now - cached.checkedAt() > BAN_CACHE_MS) {
            cached = new CachedBan(repo.ban(player, now), now);
            banCache.put(player, cached);
        }
        return cached.ban() != null && cached.ban().until() > now ? cached.ban() : null;
    }

    void forget(UUID player) {
        banCache.remove(player);
    }

    /** До какого времени игрок заглушён (0 — нет). */
    long mutedUntil(UUID player) throws SQLException {
        Punishment p = repo.mute(player, System.currentTimeMillis());
        return p != null ? p.until() : 0;
    }

    PlayerStatus status(UUID uuid, String name) throws SQLException {
        long now = System.currentTimeMillis();
        Punishment mute = repo.mute(uuid, now);
        Punishment ban = repo.ban(uuid, now);
        String resolved = name;
        if (resolved == null) {
            resolved = backend.users().userNames(List.of(uuid)).getOrDefault(uuid, uuid.toString().substring(0, 8));
        }
        return new PlayerStatus(backend.user(uuid, resolved), mute != null ? mute.until() : 0, mute != null ? mute.reason() : "",
                ban != null ? ban.until() : 0, ban != null ? ban.reason() : "", backend.users().lastSeen(uuid));
    }

    // --- Пакеты ---

    void handle(UUID player, Packet packet) throws SQLException {
        switch (packet) {
            case ModerationPackets.SearchPlayers p -> {
                if (anyModerator(player)) {
                    search(player, p.query().strip());
                }
            }
            case ModerationPackets.RequestPunished p -> {
                if (anyModerator(player)) {
                    punished(player);
                }
            }
            case ModerationPackets.Punish p -> punish(player, p);
            case ModerationPackets.RequestLog p -> {
                if (anyModerator(player)) {
                    sendLog(player, p.beforeId());
                }
            }
            case ModerationPackets.RequestUserChats p -> userChats(player, p.user());
            case ModerationPackets.RequestChat p -> chat(player, p.chatId(), p.beforeId());
            default -> {
            }
        }
    }

    private boolean anyModerator(UUID player) {
        if (backend.has(player, Permission.MOD_MUTE) || backend.has(player, Permission.MOD_BAN)
                || backend.has(player, Permission.MOD_REPORTS) || backend.has(player, Permission.MOD_CHATS)) {
            return true;
        }
        fail(player, "no_permission", "");
        return false;
    }

    private void search(UUID player, String query) throws SQLException {
        Map<UUID, String> found;
        if (query.isEmpty()) {
            found = new java.util.LinkedHashMap<>(backend.online());
        } else {
            found = backend.users().searchUsers(query, SEARCH_LIMIT);
        }
        List<PlayerStatus> list = new ArrayList<>();
        for (Map.Entry<UUID, String> e : found.entrySet()) {
            list.add(status(e.getKey(), e.getValue()));
        }
        if (query.isEmpty()) {
            list.sort(Comparator.comparing(s -> s.user().name().toLowerCase(Locale.ROOT)));
        }
        send(player, new ModerationPackets.Players(ModerationPackets.ListKind.SEARCH, list));
    }

    private void punished(UUID player) throws SQLException {
        List<UUID> ids = repo.punished(System.currentTimeMillis(), ModerationData.MAX_LIST);
        Map<UUID, String> names = backend.users().userNames(ids);
        List<PlayerStatus> list = new ArrayList<>();
        for (UUID uuid : ids) {
            list.add(status(uuid, names.get(uuid)));
        }
        send(player, new ModerationPackets.Players(ModerationPackets.ListKind.PUNISHED, list));
    }

    private void punish(UUID player, ModerationPackets.Punish p) throws SQLException {
        Permission needed = switch (p.action()) {
            case MUTE, UNMUTE -> Permission.MOD_MUTE;
            case BAN, UNBAN -> Permission.MOD_BAN;
        };
        if (!backend.has(player, needed)) {
            fail(player, "no_permission", "");
            return;
        }
        if (p.user().equals(player)) {
            fail(player, "self", "");
            return;
        }
        if (!backend.users().userExists(p.user())) {
            fail(player, "user_not_found", "");
            return;
        }
        String reason = p.reason().strip();
        if (reason.length() > ModerationData.MAX_REASON) {
            reason = reason.substring(0, ModerationData.MAX_REASON);
        }
        long now = System.currentTimeMillis();
        long until = p.minutes() < 0 ? ModerationData.FOREVER : now + Math.max(1, Math.min(p.minutes(), 60L * 24 * 3650)) * 60_000L;
        String duration = p.minutes() < 0 ? "forever" : p.minutes() + "m";
        Map<UUID, String> names = backend.users().userNames(List.of(p.user(), player));
        String targetName = names.getOrDefault(p.user(), p.user().toString());
        String modName = names.getOrDefault(player, backend.online().getOrDefault(player, "?"));
        switch (p.action()) {
            case MUTE -> {
                repo.setMute(p.user(), until, reason, player, now);
                log(player, "mute", p.user(), duration + (reason.isEmpty() ? "" : ": " + reason));
            }
            case UNMUTE -> {
                repo.unmute(p.user());
                log(player, "unmute", p.user(), reason);
            }
            case BAN -> {
                repo.setBan(p.user(), until, reason, player, now);
                banCache.remove(p.user());
                log(player, "ban", p.user(), duration + (reason.isEmpty() ? "" : ": " + reason));
                command(backend.settings().moderation().banCommand, targetName, p.user(), reason, p.minutes(), modName);
            }
            case UNBAN -> {
                repo.unban(p.user());
                banCache.remove(p.user());
                log(player, "unban", p.user(), reason);
                command(backend.settings().moderation().unbanCommand, targetName, p.user(), reason, 0, modName);
            }
        }
        backend.platform().info("[moderation] " + modName + " " + p.action().name().toLowerCase(Locale.ROOT) + " " + targetName
                + (p.action() == ModerationPackets.Action.MUTE || p.action() == ModerationPackets.Action.BAN ? " (" + duration + ")" : "")
                + (reason.isEmpty() ? "" : ": " + reason));
        if (backend.isOnline(p.user())) {
            backend.sendSession(p.user());
            if (p.action() == ModerationPackets.Action.BAN) {
                // Бан: идущие звонки игрока завершаются.
                backend.calls().quit(p.user());
            }
        }
        send(player, new ModerationPackets.PlayerUpdated(status(p.user(), targetName)));
        send(player, new SocialPackets.Done("moderation.done." + p.action().name().toLowerCase(Locale.ROOT) + "|" + targetName));
    }

    /** Команда сервера при бане/разбане (из настроек), от имени консоли в главном потоке. */
    private void command(String template, String name, UUID uuid, String reason, int minutes, String moderator) {
        if (template == null || template.isBlank()) {
            return;
        }
        String safeReason = reason.replaceAll("[\\r\\n;§]", " ");
        String command = template.replace("{player}", name).replace("{uuid}", uuid.toString()).replace("{reason}", safeReason)
                .replace("{minutes}", String.valueOf(minutes)).replace("{moderator}", moderator).strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        String run = command;
        backend.platform().runOnMain(() -> {
            if (!backend.platform().runConsoleCommand(run)) {
                backend.platform().warn("[moderation] command failed: " + run, null);
            }
        });
    }

    private void log(UUID moderator, String action, UUID target, String details) throws SQLException {
        repo.log(moderator, action, target.toString(), details, System.currentTimeMillis());
    }

    private void sendLog(UUID player, long beforeId) throws SQLException {
        List<ModerationRepository.LogRow> rows = repo.log(beforeId, ModerationData.LOG_PAGE + 1);
        boolean more = rows.size() > ModerationData.LOG_PAGE;
        if (more) {
            rows = rows.subList(0, ModerationData.LOG_PAGE);
        }
        Set<UUID> ids = new HashSet<>();
        for (ModerationRepository.LogRow row : rows) {
            ids.add(row.moderator());
            UUID target = parseUuid(row.target());
            if (target != null) {
                ids.add(target);
            }
        }
        Map<UUID, String> names = backend.users().userNames(ids);
        List<LogEntry> entries = new ArrayList<>();
        for (ModerationRepository.LogRow row : rows) {
            UUID target = parseUuid(row.target());
            String moderator = row.moderator().equals(SYSTEM) ? "BlockPhone" : names.getOrDefault(row.moderator(), "?");
            String targetName = target != null ? names.getOrDefault(target, row.target()) : row.target();
            entries.add(new LogEntry(row.id(), moderator, row.action(), targetName, row.details(), row.createdAt()));
        }
        send(player, new ModerationPackets.Log(entries, beforeId > 0, more));
    }

    private static UUID parseUuid(String text) {
        try {
            return text != null && text.length() == 36 ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // --- Просмотр чатов ---

    private void userChats(UUID player, UUID user) throws SQLException {
        if (!backend.has(player, Permission.MOD_CHATS)) {
            fail(player, "no_permission", "");
            return;
        }
        MessengerService messenger = backend.messenger();
        if (messenger == null) {
            fail(player, "no_messenger", "");
            return;
        }
        List<ChatInfo> chats = new ArrayList<>();
        for (MessengerRepository.ChatRow row : messenger.repo().chatsOf(user)) {
            chats.add(messenger.chatInfo(row, user));
        }
        chats.sort(Comparator.comparingLong(ChatInfo::activity).reversed());
        String name = backend.users().userNames(List.of(user)).getOrDefault(user, "?");
        log(player, "view_chats", user, chats.size() + " chats");
        send(player, new ModerationPackets.UserChats(user, name, chats));
    }

    private void chat(UUID player, long chatId, long beforeId) throws SQLException {
        if (!backend.has(player, Permission.MOD_CHATS)) {
            fail(player, "no_permission", "");
            return;
        }
        MessengerService messenger = backend.messenger();
        if (messenger == null || messenger.repo().chat(chatId) == null) {
            fail(player, "chat_not_found", "");
            return;
        }
        int page = MessengerData.HISTORY_PAGE;
        List<MessengerRepository.MessageRow> rows = messenger.repo().history(chatId, beforeId, page + 1);
        boolean more = rows.size() > page;
        if (more) {
            rows = rows.subList(0, page);
        }
        List<MessengerRepository.MessageRow> ordered = new ArrayList<>(rows);
        java.util.Collections.reverse(ordered);
        if (beforeId == 0) {
            repo.log(player, "view_chat", "chat " + chatId, "", System.currentTimeMillis());
        }
        send(player, new ModerationPackets.ChatHistory(chatId, messenger.messageInfos(ordered), beforeId > 0, more));
    }

    // --- Лог сообщений в чат игры ---

    /**
     * Новое сообщение мессенджера: если лог включён — строка в чат игры тем, у кого право {@link Permission#CHAT_LOG}
     * (кроме участников этого чата — они и так его видят), и в консоль, если нужно.
     */
    void logMessage(MessengerRepository.ChatRow chat, UUID sender, Collection<UUID> members, String text, boolean photo, boolean voice,
                    boolean sticker) throws SQLException {
        ModerationSettings.ChatLog cfg = backend.settings().moderation().chatLog;
        if (!cfg.enabled || (chat.type() == ChatType.GROUP && !cfg.groups)) {
            return;
        }
        Map<UUID, String> names = backend.users().userNames(members);
        String senderName = names.getOrDefault(sender, backend.online().getOrDefault(sender, "?"));
        List<String> others = new ArrayList<>();
        for (UUID member : members) {
            if (!member.equals(sender)) {
                others.add(names.getOrDefault(member, "?"));
            }
        }
        StringBuilder message = new StringBuilder();
        if (photo) {
            message.append(cfg.photo).append(' ');
        }
        if (voice) {
            message.append(cfg.voice).append(' ');
        }
        if (sticker) {
            message.append(cfg.sticker).append(' ');
        }
        // Текст игрока — без цветовых кодов (§ вырезается, & остаётся как есть).
        message.append(text.replace('§', ' ').replace('\n', ' '));
        String format = chat.type() == ChatType.GROUP ? cfg.groupFormat : cfg.format;
        String line = ModerationSettings.colors(format.replace("{message}", "\u0000"))
                .replace("{sender}", senderName)
                .replace("{receiver}", String.join(", ", others))
                .replace("{group}", chat.title().replace('§', ' '))
                .replace("\u0000", message.toString().strip());
        if (cfg.console) {
            backend.platform().info("[chatlog] " + line.replaceAll("§.", ""));
        }
        Set<UUID> skip = new HashSet<>(members);
        ServerPlatform platform = backend.platform();
        platform.runOnMain(() -> {
            for (UUID viewer : platform.onlinePlayers()) {
                if (!skip.contains(viewer) && backend.has(viewer, Permission.CHAT_LOG)) {
                    platform.sendChat(viewer, line);
                }
            }
        });
    }

    private void fail(UUID player, String key, String arg) {
        send(player, new SocialPackets.Failure("moderation.error." + key, arg));
    }

    private void send(UUID player, Packet packet) {
        backend.send(player, packet);
    }
}
