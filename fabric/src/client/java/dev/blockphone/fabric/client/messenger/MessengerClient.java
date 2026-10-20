package dev.blockphone.fabric.client.messenger;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.ChatType;
import dev.blockphone.common.protocol.data.MessengerData.MessageInfo;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.network.ClientNetwork;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Состояние мессенджера на клиенте: чаты, загруженные сообщения, кто в сети, кто печатает.
 * Обновляется пакетами сервера ({@link #handle}), UI читает его каждый кадр.
 * Всё — только на потоке рендера; сбрасывается при выходе с сервера.
 */
public final class MessengerClient {
    private static final long TYPING_SHOW_MS = 4000;

    /** Загруженная часть переписки чата. */
    public static final class ChatMessages {
        public final List<MessageInfo> list = new ArrayList<>();
        public boolean loaded;
        public boolean hasMore;
        public boolean loadingOlder;
    }

    /** Отправленное, но ещё не подтверждённое сервером сообщение (показывается сразу, полупрозрачным). image — фото или null. */
    public record Pending(String nonce, long chatId, String text, dev.blockphone.common.protocol.data.ImageRef image,
                          dev.blockphone.common.protocol.data.VoiceData.VoiceRef voice, long sentAt) {
    }

    private static final Map<Long, ChatInfo> CHATS = new LinkedHashMap<>();
    private static final Map<Long, ChatMessages> MESSAGES = new HashMap<>();
    private static final Map<String, Pending> PENDING = new LinkedHashMap<>();
    private static final Map<UUID, Boolean> ONLINE = new HashMap<>();
    private static final Map<Long, Map<UUID, TypingInfo>> TYPING = new HashMap<>();
    private static List<UserInfo> searchResults = List.of();
    private static int searchRequest;
    private static boolean chatListLoaded;
    private static @Nullable Long pendingOpen;
    private static @Nullable String lastError;
    private static long lastErrorAt;
    private static long openChatId = -1;
    private static int nonceCounter;

    private record TypingInfo(String name, long until) {
    }

    // --- Чтение состояния ---

    public static boolean loaded() {
        return chatListLoaded;
    }

    /** Чаты, сначала с последней активностью. */
    public static List<ChatInfo> chats() {
        List<ChatInfo> list = new ArrayList<>(CHATS.values());
        list.sort(Comparator.comparingLong(MessengerClient::activity).reversed());
        return list;
    }

    private static long activity(ChatInfo chat) {
        long pending = PENDING.values().stream().filter(p -> p.chatId() == chat.id()).mapToLong(Pending::sentAt).max().orElse(0);
        return Math.max(chat.activity(), pending);
    }

    public static @Nullable ChatInfo chat(long id) {
        return CHATS.get(id);
    }

    public static ChatMessages messages(long chatId) {
        return MESSAGES.computeIfAbsent(chatId, k -> new ChatMessages());
    }

    public static List<Pending> pending(long chatId) {
        return PENDING.values().stream().filter(p -> p.chatId() == chatId).toList();
    }

    public static int unreadTotal() {
        return CHATS.values().stream().mapToInt(ChatInfo::unread).sum();
    }

    public static @Nullable UUID self() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getUUID() : null;
    }

    public static boolean isSelf(UUID uuid) {
        return uuid.equals(self());
    }

    public static boolean isOnline(UserInfo user) {
        return ONLINE.getOrDefault(user.uuid(), user.online());
    }

    /** Собеседник в личном чате (или null). */
    public static @Nullable UserInfo partner(ChatInfo chat) {
        if (chat.type() != ChatType.DIRECT) {
            return null;
        }
        for (UserInfo member : chat.members()) {
            if (!isSelf(member.uuid())) {
                return member;
            }
        }
        return null;
    }

    /** Название чата: имя собеседника для личного, название — для группы. */
    public static String title(ChatInfo chat) {
        UserInfo partner = partner(chat);
        if (partner != null) {
            return partner.name();
        }
        return chat.title().isEmpty() ? PhoneLang.tr("messenger.untitled") : chat.title();
    }

    /** Ники тех, кто сейчас печатает в чате. */
    public static List<String> typing(long chatId) {
        Map<UUID, TypingInfo> map = TYPING.get(chatId);
        if (map == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        map.values().removeIf(t -> t.until() < now);
        return map.values().stream().map(TypingInfo::name).toList();
    }

    public static List<UserInfo> searchResults() {
        return searchResults;
    }

    /** Забрать запрос «открыть чат» (после ответа сервера на «написать»/«создать группу»). */
    public static @Nullable Long takePendingOpen() {
        Long id = pendingOpen;
        pendingOpen = null;
        return id;
    }

    /** Последняя ошибка сервера, если была не позже 4 секунд назад. */
    public static @Nullable String recentError() {
        return lastError != null && System.currentTimeMillis() - lastErrorAt < 4000 ? lastError : null;
    }

    /** Чат, открытый сейчас на экране (его сообщения сразу считаются прочитанными). -1 — никакой. */
    public static void setOpenChat(long chatId) {
        openChatId = chatId;
        if (chatId >= 0) {
            dev.blockphone.fabric.client.phone.PhoneNotifications.remove("messenger:" + chatId);
            markRead(chatId);
        }
    }

    // --- Действия ---

    public static void requestHistory(long chatId) {
        ChatMessages m = messages(chatId);
        if (!m.loaded) {
            ClientNetwork.send(new MessengerPackets.RequestHistory(chatId, 0));
        }
    }

    public static void requestOlder(long chatId) {
        ChatMessages m = messages(chatId);
        if (m.loaded && m.hasMore && !m.loadingOlder && !m.list.isEmpty()) {
            m.loadingOlder = true;
            ClientNetwork.send(new MessengerPackets.RequestHistory(chatId, m.list.get(0).id()));
        }
    }

    public static void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, null);
    }

    /** Сообщение с фото (image — уже загруженное на сервер, или null). */
    public static void sendMessage(long chatId, String text, dev.blockphone.common.protocol.data.ImageRef image) {
        sendMessage(chatId, text, image, null);
    }

    /** Сообщение с фото и/или голосовым (уже загруженными на сервер; null — нет). */
    public static void sendMessage(long chatId, String text, dev.blockphone.common.protocol.data.ImageRef image,
                                   dev.blockphone.common.protocol.data.VoiceData.VoiceRef voice) {
        String nonce = "c" + (++nonceCounter) + "_" + System.nanoTime();
        PENDING.put(nonce, new Pending(nonce, chatId, text, image, voice, System.currentTimeMillis()));
        ClientNetwork.send(new MessengerPackets.SendMessage(chatId, nonce, text, image != null ? image.id() : 0,
                voice != null ? voice.id() : 0));
    }

    /** Текст сообщения для превью и уведомлений: «[Фото] подпись», «[Голосовое 0:07]». */
    public static String previewText(MessageInfo message) {
        String text = message.text().replace('\n', ' ');
        if (message.voice() != null) {
            return PhoneLang.tr("messenger.voice_preview", dev.blockphone.fabric.client.voice.VoiceMessages.time(message.voice().durationMs()));
        }
        if (message.image() == null) {
            return text;
        }
        String photo = PhoneLang.tr("messenger.photo");
        return text.isEmpty() ? photo : photo + " " + text;
    }

    public static void search(String query) {
        searchRequest++;
        ClientNetwork.send(new MessengerPackets.SearchUsers(searchRequest, query));
    }

    public static void markRead(long chatId) {
        ChatInfo chat = CHATS.get(chatId);
        ChatMessages m = MESSAGES.get(chatId);
        long lastId = m != null && !m.list.isEmpty() ? m.list.get(m.list.size() - 1).id()
                : chat != null && chat.lastMessage() != null ? chat.lastMessage().id() : 0;
        if (chat != null && chat.unread() > 0) {
            CHATS.put(chatId, withUnread(chat, 0));
        }
        if (lastId > 0) {
            ClientNetwork.send(new MessengerPackets.MarkRead(chatId, lastId));
        }
    }

    // --- Пакеты сервера ---

    public static void handle(Packet packet) {
        switch (packet) {
            case MessengerPackets.ChatList p -> {
                CHATS.clear();
                for (ChatInfo chat : p.chats()) {
                    CHATS.put(chat.id(), chat);
                }
                chatListLoaded = true;
            }
            case MessengerPackets.ChatUpdate p -> {
                ChatInfo chat = p.chat();
                if (chat.id() == openChatId && chat.unread() > 0) {
                    chat = withUnread(chat, 0);
                }
                CHATS.put(chat.id(), chat);
            }
            case MessengerPackets.ChatRemoved p -> {
                CHATS.remove(p.chatId());
                MESSAGES.remove(p.chatId());
            }
            case MessengerPackets.History p -> onHistory(p);
            case MessengerPackets.NewMessage p -> onNewMessage(p);
            case MessengerPackets.MessageUpdated p -> onUpdated(p.message());
            case MessengerPackets.UserList p -> {
                if (p.requestId() == searchRequest) {
                    searchResults = p.users();
                }
            }
            case MessengerPackets.ChatOpened p -> pendingOpen = p.chatId();
            case MessengerPackets.TypingState p -> TYPING.computeIfAbsent(p.chatId(), k -> new HashMap<>())
                    .put(p.user().uuid(), new TypingInfo(p.user().name(), System.currentTimeMillis() + TYPING_SHOW_MS));
            case MessengerPackets.Presence p -> ONLINE.put(p.user().uuid(), p.user().online());
            case MessengerPackets.Failure p -> {
                lastError = PhoneLang.tr(p.key(), p.arg());
                lastErrorAt = System.currentTimeMillis();
                // Неотправленные сообщения больше не ждём.
                PENDING.clear();
            }
            default -> {
            }
        }
    }

    private static void onHistory(MessengerPackets.History p) {
        ChatMessages m = messages(p.chatId());
        if (p.older()) {
            m.list.addAll(0, p.messages());
            m.loadingOlder = false;
        } else {
            m.list.clear();
            m.list.addAll(p.messages());
            m.loaded = true;
        }
        m.hasMore = p.hasMore();
        if (p.chatId() == openChatId) {
            markRead(p.chatId());
        }
    }

    private static void onNewMessage(MessengerPackets.NewMessage p) {
        MessageInfo message = p.message();
        if (!p.nonce().isEmpty()) {
            PENDING.remove(p.nonce());
        }
        ChatMessages m = MESSAGES.get(message.chatId());
        if (m != null && m.loaded && m.list.stream().noneMatch(x -> x.id() == message.id())) {
            m.list.add(message);
        }
        Map<UUID, TypingInfo> typing = TYPING.get(message.chatId());
        if (typing != null) {
            typing.remove(message.sender().uuid());
        }
        ChatInfo chat = CHATS.get(message.chatId());
        if (chat != null) {
            boolean own = isSelf(message.sender().uuid());
            boolean viewing = message.chatId() == openChatId;
            int unread = own || viewing ? chat.unread() : chat.unread() + 1;
            CHATS.put(chat.id(), new ChatInfo(chat.id(), chat.type(), chat.title(), chat.owner(), chat.members(), message,
                    own || viewing ? 0 : unread, chat.createdAt()));
            if (viewing && !own) {
                ClientNetwork.send(new MessengerPackets.MarkRead(chat.id(), message.id()));
            }
            if (!own && !viewing) {
                MessengerNotifications.notify(chat, message);
            }
        }
    }

    private static void onUpdated(MessageInfo message) {
        ChatMessages m = MESSAGES.get(message.chatId());
        if (m != null) {
            for (int i = 0; i < m.list.size(); i++) {
                if (m.list.get(i).id() == message.id()) {
                    m.list.set(i, message);
                }
            }
        }
        ChatInfo chat = CHATS.get(message.chatId());
        if (chat != null && chat.lastMessage() != null && chat.lastMessage().id() == message.id()) {
            CHATS.put(chat.id(), new ChatInfo(chat.id(), chat.type(), chat.title(), chat.owner(), chat.members(), message,
                    chat.unread(), chat.createdAt()));
        }
    }

    private static ChatInfo withUnread(ChatInfo c, int unread) {
        return new ChatInfo(c.id(), c.type(), c.title(), c.owner(), c.members(), c.lastMessage(), unread, c.createdAt());
    }

    public static boolean isOwner(ChatInfo chat) {
        return Objects.equals(chat.owner(), self());
    }

    public static void reset() {
        CHATS.clear();
        MESSAGES.clear();
        PENDING.clear();
        ONLINE.clear();
        TYPING.clear();
        searchResults = List.of();
        chatListLoaded = false;
        pendingOpen = null;
        lastError = null;
        openChatId = -1;
    }

    private MessengerClient() {
    }
}
