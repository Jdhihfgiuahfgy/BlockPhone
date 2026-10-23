package dev.blockphone.fabric.client.social;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.MessageInfo;
import dev.blockphone.common.protocol.data.ModerationData.LogEntry;
import dev.blockphone.common.protocol.data.ModerationData.PlayerStatus;
import dev.blockphone.common.protocol.packet.ModerationPackets;
import dev.blockphone.fabric.client.network.ClientNetwork;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Данные панели модерации на клиенте: найденные игроки, наказанные, журнал, чаты игрока и их переписка (только чтение).
 * Поток рендера; сбрасывается при выходе с сервера.
 */
public final class ModerationClient {
    private static @Nullable List<PlayerStatus> search;
    private static @Nullable List<PlayerStatus> punished;
    private static final Map<UUID, PlayerStatus> PLAYERS = new HashMap<>();
    private static @Nullable List<LogEntry> log;
    private static boolean logHasMore;
    private static boolean logLoading;
    private static final Map<UUID, ModerationPackets.UserChats> USER_CHATS = new HashMap<>();
    private static final Map<Long, List<MessageInfo>> HISTORY = new HashMap<>();
    private static final Map<Long, Boolean> HISTORY_MORE = new HashMap<>();
    private static final Map<Long, Boolean> HISTORY_LOADING = new HashMap<>();

    public static void handle(Packet packet) {
        switch (packet) {
            case ModerationPackets.Players p -> {
                for (PlayerStatus s : p.players()) {
                    PLAYERS.put(s.user().uuid(), s);
                }
                if (p.kind() == ModerationPackets.ListKind.SEARCH) {
                    search = p.players();
                } else {
                    punished = p.players();
                }
            }
            case ModerationPackets.PlayerUpdated p -> {
                PlayerStatus s = p.player();
                PLAYERS.put(s.user().uuid(), s);
                search = replace(search, s);
                punished = null;
            }
            case ModerationPackets.Log p -> {
                List<LogEntry> next = p.append() && log != null ? new ArrayList<>(log) : new ArrayList<>();
                next.addAll(p.entries());
                log = next;
                logHasMore = p.hasMore();
                logLoading = false;
            }
            case ModerationPackets.UserChats p -> USER_CHATS.put(p.user(), p);
            case ModerationPackets.ChatHistory p -> {
                List<MessageInfo> list = new ArrayList<>(p.messages());
                if (p.append()) {
                    list.addAll(HISTORY.getOrDefault(p.chatId(), List.of()));
                }
                HISTORY.put(p.chatId(), list);
                HISTORY_MORE.put(p.chatId(), p.hasMore());
                HISTORY_LOADING.put(p.chatId(), false);
            }
            default -> {
            }
        }
    }

    private static @Nullable List<PlayerStatus> replace(@Nullable List<PlayerStatus> list, PlayerStatus s) {
        if (list == null) {
            return null;
        }
        List<PlayerStatus> next = new ArrayList<>(list);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).user().uuid().equals(s.user().uuid())) {
                next.set(i, s);
            }
        }
        return next;
    }

    // --- Запросы ---

    public static void search(String query) {
        search = null;
        ClientNetwork.send(new ModerationPackets.SearchPlayers(query));
    }

    public static void requestPunished() {
        ClientNetwork.send(new ModerationPackets.RequestPunished());
    }

    public static void punish(UUID user, ModerationPackets.Action action, int minutes, String reason) {
        ClientNetwork.send(new ModerationPackets.Punish(user, action, minutes, reason));
    }

    public static void requestLog(boolean more) {
        if (logLoading) {
            return;
        }
        logLoading = true;
        long before = more && log != null && !log.isEmpty() ? log.get(log.size() - 1).id() : 0;
        if (!more) {
            log = null;
        }
        ClientNetwork.send(new ModerationPackets.RequestLog(before));
    }

    public static void requestUserChats(UUID user) {
        USER_CHATS.remove(user);
        ClientNetwork.send(new ModerationPackets.RequestUserChats(user));
    }

    public static void requestChat(long chatId, boolean more) {
        if (Boolean.TRUE.equals(HISTORY_LOADING.get(chatId))) {
            return;
        }
        HISTORY_LOADING.put(chatId, true);
        List<MessageInfo> list = HISTORY.get(chatId);
        long before = more && list != null && !list.isEmpty() ? list.get(0).id() : 0;
        if (!more) {
            HISTORY.remove(chatId);
        }
        ClientNetwork.send(new ModerationPackets.RequestChat(chatId, before));
    }

    // --- Данные ---

    public static @Nullable List<PlayerStatus> searchResults() {
        return search;
    }

    public static @Nullable List<PlayerStatus> punished() {
        return punished;
    }

    public static @Nullable PlayerStatus player(UUID uuid) {
        return PLAYERS.get(uuid);
    }

    public static @Nullable List<LogEntry> log() {
        return log;
    }

    public static boolean logHasMore() {
        return logHasMore;
    }

    public static @Nullable ModerationPackets.UserChats userChats(UUID user) {
        return USER_CHATS.get(user);
    }

    public static @Nullable List<MessageInfo> history(long chatId) {
        return HISTORY.get(chatId);
    }

    public static boolean historyHasMore(long chatId) {
        return Boolean.TRUE.equals(HISTORY_MORE.get(chatId));
    }

    /** Найти чат среди загруженных чатов игроков (для заголовка экрана переписки). */
    public static @Nullable ChatInfo chat(long chatId) {
        for (ModerationPackets.UserChats chats : USER_CHATS.values()) {
            for (ChatInfo chat : chats.chats()) {
                if (chat.id() == chatId) {
                    return chat;
                }
            }
        }
        return null;
    }

    public static void reset() {
        search = null;
        punished = null;
        PLAYERS.clear();
        log = null;
        logHasMore = false;
        logLoading = false;
        USER_CHATS.clear();
        HISTORY.clear();
        HISTORY_MORE.clear();
        HISTORY_LOADING.clear();
    }

    private ModerationClient() {
    }
}
