package dev.blockphone.common.server;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.data.MessengerData;
import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.ChatType;
import dev.blockphone.common.protocol.data.MessengerData.MessageInfo;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.server.MessengerRepository.ChatRow;
import dev.blockphone.common.server.MessengerRepository.MessageRow;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Серверный мессенджер: личные и групповые чаты. Вся работа — в одном фоновом потоке
 * (база данных и состояние без гонок). Платформа сообщает о входе/выходе игроков и передаёт пакеты.
 */
public final class MessengerService {
    private static final int SEND_LIMIT = 8;
    private static final long SEND_WINDOW_MS = 10_000;
    private static final long TYPING_INTERVAL_MS = 1_000;
    private static final int SEARCH_LIMIT = 30;

    private final ServerPlatform platform;
    private final Database database;
    private final MessengerRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BlockPhone Messenger");
        thread.setDaemon(true);
        return thread;
    });

    /** Игроки с модом, которые сейчас онлайн (в сети серверов — во всей сети): UUID → ник. */
    private final Map<UUID, String> online = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<Long>> sendTimes = new HashMap<>();
    private final Map<UUID, Long> lastTyping = new HashMap<>();
    private volatile boolean available;

    public MessengerService(ServerPlatform platform, ServerSettings.Storage storage) {
        this.platform = platform;
        this.database = new Database(storage);
        this.repo = new MessengerRepository(database);
    }

    public void start() {
        executor.execute(() -> {
            try {
                database.migrate();
                available = true;
                platform.info("Messenger storage ready");
            } catch (Exception e) {
                platform.warn("Messenger storage failed to start, messenger disabled", e);
            }
        });
    }

    public void stop() {
        executor.execute(database::close);
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean available() {
        return available;
    }

    // --- События платформы ---

    public void onJoin(UUID uuid, String name) {
        run(uuid, () -> {
            long now = System.currentTimeMillis();
            repo.upsertUser(uuid, name, now);
            online.put(uuid, name);
            List<ChatInfo> chats = new ArrayList<>();
            for (ChatRow row : repo.chatsOf(uuid)) {
                chats.add(chatInfo(row, uuid));
            }
            chats.sort(Comparator.comparingLong(ChatInfo::activity).reversed());
            send(uuid, new MessengerPackets.ChatList(chats));
            broadcastPresence(new UserInfo(uuid, name, true));
        });
    }

    public void onQuit(UUID uuid) {
        run(uuid, () -> {
            String name = online.remove(uuid);
            sendTimes.remove(uuid);
            lastTyping.remove(uuid);
            if (name != null) {
                repo.touchUser(uuid, System.currentTimeMillis());
                broadcastPresence(new UserInfo(uuid, name, false));
            }
        });
    }

    /** Пакет мессенджера от игрока (уже декодированный). */
    public void handle(UUID player, Packet packet) {
        run(player, () -> {
            if (!online.containsKey(player)) {
                return; // Не поздоровался (нет мода или старая версия).
            }
            switch (packet) {
                case MessengerPackets.RequestHistory p -> history(player, p);
                case MessengerPackets.SendMessage p -> sendMessage(player, p);
                case MessengerPackets.EditMessage p -> editMessage(player, p);
                case MessengerPackets.DeleteMessage p -> deleteMessage(player, p);
                case MessengerPackets.MarkRead p -> {
                    if (repo.isMember(p.chatId(), player)) {
                        repo.markRead(p.chatId(), player, p.messageId());
                    }
                }
                case MessengerPackets.SearchUsers p -> searchUsers(player, p);
                case MessengerPackets.OpenDirect p -> openDirect(player, p);
                case MessengerPackets.CreateGroup p -> createGroup(player, p);
                case MessengerPackets.GroupAction p -> groupAction(player, p);
                case MessengerPackets.Typing p -> typing(player, p);
                default -> {
                }
            }
        });
    }

    private void run(UUID player, SqlTask task) {
        executor.execute(() -> {
            if (!available) {
                return;
            }
            try {
                task.run();
            } catch (Exception e) {
                platform.warn("Messenger error for " + player, e);
                send(player, new MessengerPackets.Failure("messenger.error.internal", ""));
            }
        });
    }

    @FunctionalInterface
    private interface SqlTask {
        void run() throws SQLException;
    }

    // --- Обработчики ---

    private void history(UUID player, MessengerPackets.RequestHistory p) throws SQLException {
        if (!repo.isMember(p.chatId(), player)) {
            return;
        }
        int page = MessengerData.HISTORY_PAGE;
        List<MessageRow> rows = repo.history(p.chatId(), p.beforeId(), page + 1);
        boolean hasMore = rows.size() > page;
        if (hasMore) {
            rows = rows.subList(0, page);
        }
        List<MessageRow> ordered = new ArrayList<>(rows);
        java.util.Collections.reverse(ordered);
        Map<UUID, String> names = names(ordered.stream().map(MessageRow::sender).toList());
        List<MessageInfo> messages = ordered.stream().map(m -> messageInfo(m, names)).toList();
        send(player, new MessengerPackets.History(p.chatId(), messages, p.beforeId() > 0, hasMore));
    }

    private void sendMessage(UUID player, MessengerPackets.SendMessage p) throws SQLException {
        String text = p.text().strip();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > MessengerData.MAX_MESSAGE_LENGTH) {
            fail(player, "too_long");
            return;
        }
        if (!repo.isMember(p.chatId(), player)) {
            fail(player, "not_member");
            return;
        }
        if (!allowSend(player)) {
            fail(player, "rate_limit");
            return;
        }
        long now = System.currentTimeMillis();
        long id = repo.insertMessage(p.chatId(), player, text, now);
        repo.markRead(p.chatId(), player, id);
        MessageInfo message = new MessageInfo(id, p.chatId(), user(player, online.get(player)), text, now, 0, false);
        for (UUID member : repo.members(p.chatId())) {
            if (online.containsKey(member)) {
                send(member, new MessengerPackets.NewMessage(message, member.equals(player) ? p.nonce() : ""));
            }
        }
    }

    private boolean allowSend(UUID player) {
        long now = System.currentTimeMillis();
        Deque<Long> times = sendTimes.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > SEND_WINDOW_MS) {
            times.pollFirst();
        }
        if (times.size() >= SEND_LIMIT) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    private void editMessage(UUID player, MessengerPackets.EditMessage p) throws SQLException {
        MessageRow row = repo.message(p.messageId());
        String text = p.text().strip();
        if (row == null || row.deleted() || !row.sender().equals(player) || text.isEmpty()) {
            return;
        }
        if (text.length() > MessengerData.MAX_MESSAGE_LENGTH) {
            fail(player, "too_long");
            return;
        }
        long now = System.currentTimeMillis();
        repo.editMessage(row.id(), text, now);
        broadcastUpdated(new MessageRow(row.id(), row.chatId(), row.sender(), text, row.sentAt(), now, false));
    }

    private void deleteMessage(UUID player, MessengerPackets.DeleteMessage p) throws SQLException {
        MessageRow row = repo.message(p.messageId());
        if (row == null || row.deleted() || !row.sender().equals(player)) {
            return;
        }
        repo.deleteMessage(row.id());
        broadcastUpdated(new MessageRow(row.id(), row.chatId(), row.sender(), "", row.sentAt(), row.editedAt(), true));
    }

    private void broadcastUpdated(MessageRow row) throws SQLException {
        MessageInfo info = messageInfo(row, names(List.of(row.sender())));
        for (UUID member : repo.members(row.chatId())) {
            if (online.containsKey(member)) {
                send(member, new MessengerPackets.MessageUpdated(info));
            }
        }
    }

    private void searchUsers(UUID player, MessengerPackets.SearchUsers p) throws SQLException {
        String query = p.query().strip();
        List<UserInfo> result = new ArrayList<>();
        if (query.isEmpty()) {
            online.forEach((uuid, name) -> {
                if (!uuid.equals(player)) {
                    result.add(new UserInfo(uuid, name, true));
                }
            });
            result.sort(Comparator.comparing(u -> u.name().toLowerCase(java.util.Locale.ROOT)));
        } else {
            repo.searchUsers(query, SEARCH_LIMIT + 1).forEach((uuid, name) -> {
                if (!uuid.equals(player) && result.size() < SEARCH_LIMIT) {
                    result.add(user(uuid, name));
                }
            });
        }
        send(player, new MessengerPackets.UserList(p.requestId(), result));
    }

    private void openDirect(UUID player, MessengerPackets.OpenDirect p) throws SQLException {
        UUID target = p.target();
        if (target.equals(player) || !repo.userExists(target)) {
            fail(player, "user_not_found");
            return;
        }
        String key = directKey(player, target);
        Long chatId = repo.findDirectChat(key);
        if (chatId == null) {
            long now = System.currentTimeMillis();
            chatId = repo.createChat(ChatType.DIRECT, "", player, key, now);
            repo.addMember(chatId, player, now);
            repo.addMember(chatId, target, now);
            pushChat(repo.chat(chatId), List.of(player, target));
        } else {
            pushChat(repo.chat(chatId), List.of(player));
        }
        send(player, new MessengerPackets.ChatOpened(chatId));
    }

    private static String directKey(UUID a, UUID b) {
        String sa = a.toString();
        String sb = b.toString();
        return sa.compareTo(sb) < 0 ? sa + ":" + sb : sb + ":" + sa;
    }

    private void createGroup(UUID player, MessengerPackets.CreateGroup p) throws SQLException {
        String title = p.title().strip();
        if (title.isEmpty() || title.length() > MessengerData.MAX_TITLE_LENGTH) {
            fail(player, "bad_title");
            return;
        }
        Set<UUID> invited = new LinkedHashSet<>(p.members());
        invited.remove(player);
        Set<UUID> known = repo.userNames(invited).keySet();
        if (known.size() + 1 > MessengerData.MAX_GROUP_MEMBERS) {
            fail(player, "group_full");
            return;
        }
        long now = System.currentTimeMillis();
        long chatId = repo.createChat(ChatType.GROUP, title, player, null, now);
        repo.addMember(chatId, player, now);
        for (UUID member : known) {
            repo.addMember(chatId, member, now + 1);
        }
        pushChat(repo.chat(chatId), repo.members(chatId));
        send(player, new MessengerPackets.ChatOpened(chatId));
    }

    private void groupAction(UUID player, MessengerPackets.GroupAction p) throws SQLException {
        ChatRow chat = repo.chat(p.chatId());
        if (chat == null || chat.type() != ChatType.GROUP || !repo.isMember(chat.id(), player)) {
            fail(player, "not_member");
            return;
        }
        boolean owner = chat.owner().equals(player);
        switch (p.action()) {
            case RENAME -> {
                String title = p.title().strip();
                if (!owner) {
                    fail(player, "not_owner");
                    return;
                }
                if (title.isEmpty() || title.length() > MessengerData.MAX_TITLE_LENGTH) {
                    fail(player, "bad_title");
                    return;
                }
                repo.renameChat(chat.id(), title);
                pushChat(repo.chat(chat.id()), repo.members(chat.id()));
            }
            case ADD -> {
                List<UUID> current = repo.members(chat.id());
                Set<UUID> known = repo.userNames(p.users()).keySet();
                long now = System.currentTimeMillis();
                int count = current.size();
                for (UUID uuid : known) {
                    if (!current.contains(uuid)) {
                        if (count >= MessengerData.MAX_GROUP_MEMBERS) {
                            fail(player, "group_full");
                            break;
                        }
                        repo.addMember(chat.id(), uuid, now++);
                        count++;
                    }
                }
                pushChat(chat, repo.members(chat.id()));
            }
            case REMOVE -> {
                if (!owner) {
                    fail(player, "not_owner");
                    return;
                }
                for (UUID uuid : p.users()) {
                    if (!uuid.equals(player) && repo.isMember(chat.id(), uuid)) {
                        repo.removeMember(chat.id(), uuid);
                        send(uuid, new MessengerPackets.ChatRemoved(chat.id()));
                    }
                }
                pushChat(chat, repo.members(chat.id()));
            }
            case LEAVE -> {
                repo.removeMember(chat.id(), player);
                send(player, new MessengerPackets.ChatRemoved(chat.id()));
                List<UUID> rest = repo.members(chat.id());
                if (rest.isEmpty()) {
                    repo.deleteChat(chat.id());
                    return;
                }
                if (owner) {
                    repo.setOwner(chat.id(), rest.get(0));
                }
                pushChat(repo.chat(chat.id()), rest);
            }
        }
    }

    private void typing(UUID player, MessengerPackets.Typing p) throws SQLException {
        long now = System.currentTimeMillis();
        Long last = lastTyping.get(player);
        if (last != null && now - last < TYPING_INTERVAL_MS) {
            return;
        }
        lastTyping.put(player, now);
        if (!repo.isMember(p.chatId(), player)) {
            return;
        }
        UserInfo user = new UserInfo(player, online.get(player), true);
        for (UUID member : repo.members(p.chatId())) {
            if (!member.equals(player) && online.containsKey(member)) {
                send(member, new MessengerPackets.TypingState(p.chatId(), user));
            }
        }
    }

    // --- Сборка данных ---

    /** Отправить актуальную карточку чата перечисленным участникам (каждому — со своими непрочитанными). */
    private void pushChat(ChatRow chat, List<UUID> recipients) throws SQLException {
        for (UUID uuid : recipients) {
            if (online.containsKey(uuid)) {
                send(uuid, new MessengerPackets.ChatUpdate(chatInfo(chat, uuid)));
            }
        }
    }

    private ChatInfo chatInfo(ChatRow row, UUID viewer) throws SQLException {
        List<UUID> memberIds = repo.members(row.id());
        MessageRow last = repo.lastMessage(row.id());
        List<UUID> nameIds = new ArrayList<>(memberIds);
        if (last != null) {
            nameIds.add(last.sender());
        }
        Map<UUID, String> names = names(nameIds);
        List<UserInfo> members = memberIds.stream().map(u -> user(u, names.get(u))).toList();
        MessageInfo lastInfo = last == null ? null : messageInfo(last, names);
        return new ChatInfo(row.id(), row.type(), row.title(), row.owner(), members, lastInfo, repo.unread(row.id(), viewer),
                row.createdAt());
    }

    private Map<UUID, String> names(List<UUID> uuids) throws SQLException {
        return repo.userNames(new LinkedHashSet<>(uuids));
    }

    private MessageInfo messageInfo(MessageRow row, Map<UUID, String> names) {
        return new MessageInfo(row.id(), row.chatId(), user(row.sender(), names.get(row.sender())), row.text(), row.sentAt(),
                row.editedAt(), row.deleted());
    }

    private UserInfo user(UUID uuid, String name) {
        String resolved = name != null ? name : online.getOrDefault(uuid, "?");
        return new UserInfo(uuid, resolved, online.containsKey(uuid));
    }

    private void broadcastPresence(UserInfo user) {
        for (UUID uuid : online.keySet()) {
            if (!uuid.equals(user.uuid())) {
                send(uuid, new MessengerPackets.Presence(user));
            }
        }
    }

    private void fail(UUID player, String key) {
        send(player, new MessengerPackets.Failure("messenger.error." + key, ""));
    }

    private void send(UUID player, Packet packet) {
        platform.send(player, Packets.encode(packet));
    }

    // --- Для отладки и тестов ---

    /** Выполнить действие в потоке мессенджера (например, добавить тестового игрока). */
    public void runInThread(SqlRunnable action) {
        executor.execute(() -> {
            try {
                action.run(repo);
            } catch (Exception e) {
                platform.warn("Messenger task failed", e);
            }
        });
    }

    @FunctionalInterface
    public interface SqlRunnable {
        void run(MessengerRepository repo) throws SQLException;
    }

    /** Сообщение «от имени» игрока, который может быть и не онлайн (для отладки). */
    public void injectMessage(UUID sender, String senderName, long chatId, String text) {
        executor.execute(() -> {
            try {
                long now = System.currentTimeMillis();
                long id = repo.insertMessage(chatId, sender, text, now);
                MessageInfo message = new MessageInfo(id, chatId, new UserInfo(sender, senderName, online.containsKey(sender)),
                        text, now, 0, false);
                for (UUID member : repo.members(chatId)) {
                    if (online.containsKey(member)) {
                        send(member, new MessengerPackets.NewMessage(message, ""));
                    }
                }
            } catch (Exception e) {
                platform.warn("Inject failed", e);
            }
        });
    }
}
