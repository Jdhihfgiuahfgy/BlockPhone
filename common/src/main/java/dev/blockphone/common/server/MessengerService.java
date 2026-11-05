package dev.blockphone.common.server;

import dev.blockphone.common.protocol.data.PrivacyData.Kind;

import dev.blockphone.common.server.filter.ContentFilter;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.data.MessengerData;
import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.ChatType;
import dev.blockphone.common.protocol.data.MessengerData.MessageInfo;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.VoiceData.VoiceRef;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.server.MessengerRepository.ChatRow;
import dev.blockphone.common.server.MessengerRepository.MessageRow;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Серверный мессенджер: личные и групповые чаты, фото в сообщениях. Работает в фоновом потоке {@link Backend}
 * (база данных и состояние без гонок); бэкенд сообщает о входе/выходе игроков и передаёт пакеты.
 */
public final class MessengerService {
    private static final long TYPING_INTERVAL_MS = 1_000;
    private static final int SEARCH_LIMIT = 30;

    private final Backend backend;
    private final MessengerRepository repo;
    private final RateLimiter sendLimit = new RateLimiter(8, 10_000);
    private final Map<UUID, Long> lastTyping = new HashMap<>();

    MessengerService(Backend backend) {
        this.backend = backend;
        this.repo = new MessengerRepository(backend.database(), backend.users());
    }

    public boolean available() {
        return backend.available();
    }

    MessengerRepository repo() {
        return repo;
    }

    private Map<UUID, String> online() {
        return backend.online();
    }

    // --- События (уже в потоке бэкенда) ---

    void join(UUID uuid, String name) throws SQLException {
        List<ChatInfo> chats = new ArrayList<>();
        for (ChatRow row : repo.chatsOf(uuid)) {
            chats.add(chatInfo(row, uuid));
        }
        chats.sort(Comparator.comparingLong(ChatInfo::activity).reversed());
        send(uuid, new MessengerPackets.ChatList(chats));
        broadcastPresence(new UserInfo(uuid, name, true));
    }

    void quit(UUID uuid, String name) {
        sendLimit.forget(uuid);
        lastTyping.remove(uuid);
        broadcastPresence(new UserInfo(uuid, name, false));
    }

    /** Пакет мессенджера от игрока. */
    void handle(UUID player, Packet packet) throws SQLException {
        switch (packet) {
            case MessengerPackets.RequestHistory p -> history(player, p);
            case MessengerPackets.SendMessage p -> sendMessage(player, p);
            case MessengerPackets.ForwardMessage p -> forward(player, p);
            case MessengerPackets.SharePost p -> sharePost(player, p);
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
        send(player, new MessengerPackets.History(p.chatId(), messageInfos(ordered), p.beforeId() > 0, hasMore));
    }

    /** Место для автомодерации: личный чат или группа. */
    private ContentFilter.Context messageContext(long chatId) throws SQLException {
        ChatRow chat = repo.chat(chatId);
        return chat != null && chat.type() == ChatType.GROUP ? ContentFilter.Context.GROUP_MESSAGE : ContentFilter.Context.MESSAGE;
    }

    private void sendMessage(UUID player, MessengerPackets.SendMessage p) throws SQLException {
        String text = p.text().strip();
        if (p.stickerId() > 0) {
            sendSticker(player, p);
            return;
        }
        if (text.isEmpty() && p.imageId() <= 0 && p.voiceId() <= 0) {
            return;
        }
        if (filterMuted(player)) {
            return;
        }
        ContentFilter.Verdict verdict = backend.filter(player, messageContext(p.chatId()), text);
        if (verdict.blocked()) {
            fail(player, verdict.reason());
            return;
        }
        text = verdict.text();
        if (text.length() > MessengerData.MAX_MESSAGE_LENGTH) {
            fail(player, "too_long");
            return;
        }
        if (!repo.isMember(p.chatId(), player)) {
            fail(player, "not_member");
            return;
        }
        if (!sendLimit.allow(player)) {
            fail(player, "rate_limit");
            return;
        }
        if (!directAllowed(player, p.chatId(), Kind.MESSAGES, "privacy_messages")
                || (p.imageId() > 0 && !directAllowed(player, p.chatId(), Kind.PHOTOS, "privacy_photos"))
                || (p.voiceId() > 0 && !directAllowed(player, p.chatId(), Kind.VOICE, "privacy_voice"))) {
            return;
        }
        ImageRef image = null;
        if (p.imageId() > 0) {
            String error = backend.images().checkAllowed(player, ImageRef.Purpose.CHAT);
            image = error == null ? backend.images().claim(player, p.imageId(), ImageRepository.KIND_MESSAGE, p.chatId()) : null;
            if (image == null) {
                send(player, new MessengerPackets.Failure(error != null ? error : "photo.error.not_found", ""));
                return;
            }
        }
        VoiceRef voice = null;
        if (p.voiceId() > 0) {
            voice = backend.voices().claim(player, p.voiceId(), p.chatId());
            if (voice == null) {
                send(player, new MessengerPackets.Failure("voice.error.not_found", ""));
                return;
            }
        }
        long now = System.currentTimeMillis();
        long id = repo.insertMessage(p.chatId(), player, text, image != null ? image.id() : 0, voice != null ? voice.id() : 0, now);
        repo.markRead(p.chatId(), player, id);
        MessageInfo message = new MessageInfo(id, p.chatId(), backend.user(player, online().get(player)), text, image, voice, now, 0,
                false);
        List<UUID> members = repo.members(p.chatId());
        for (UUID member : members) {
            if (backend.isOnline(member)) {
                send(member, new MessengerPackets.NewMessage(message, member.equals(player) ? p.nonce() : ""));
            }
        }
        backend.moderation().logMessage(repo.chat(p.chatId()), player, members, text, image != null, voice != null, false);
    }

    private void editMessage(UUID player, MessengerPackets.EditMessage p) throws SQLException {
        MessageRow row = repo.message(p.messageId());
        String text = p.text().strip();
        if (row == null || row.deleted() || !row.sender().equals(player) || (text.isEmpty() && row.imageId() <= 0 && row.voiceId() <= 0)) {
            return;
        }
        if (text.length() > MessengerData.MAX_MESSAGE_LENGTH) {
            fail(player, "too_long");
            return;
        }
        if (filterMuted(player)) {
            return;
        }
        ContentFilter.Verdict verdict = backend.filter(player, messageContext(row.chatId()), text);
        if (verdict.blocked()) {
            fail(player, verdict.reason());
            return;
        }
        text = verdict.text();
        long now = System.currentTimeMillis();
        repo.editMessage(row.id(), text, now);
        broadcastUpdated(new MessageRow(row.id(), row.chatId(), row.sender(), text, row.imageId(), row.voiceId(), row.sentAt(), now,
                false));
    }

    private void deleteMessage(UUID player, MessengerPackets.DeleteMessage p) throws SQLException {
        MessageRow row = repo.message(p.messageId());
        if (row == null || row.deleted() || !row.sender().equals(player)) {
            return;
        }
        repo.deleteMessage(row.id());
        backend.imageRepo().delete(row.imageId());
        backend.voices().repo().delete(row.voiceId());
        broadcastUpdated(new MessageRow(row.id(), row.chatId(), row.sender(), "", 0, 0, row.sentAt(), row.editedAt(), true));
    }

    private void broadcastUpdated(MessageRow row) throws SQLException {
        MessageInfo info = messageInfos(List.of(row)).get(0);
        for (UUID member : repo.members(row.chatId())) {
            if (backend.isOnline(member)) {
                send(member, new MessengerPackets.MessageUpdated(info));
            }
        }
    }

    private void searchUsers(UUID player, MessengerPackets.SearchUsers p) throws SQLException {
        String query = p.query().strip();
        List<UserInfo> result = new ArrayList<>();
        if (query.isEmpty()) {
            online().forEach((uuid, name) -> {
                if (!uuid.equals(player)) {
                    result.add(new UserInfo(uuid, name, true));
                }
            });
            result.sort(Comparator.comparing(u -> u.name().toLowerCase(java.util.Locale.ROOT)));
        } else {
            repo.searchUsers(query, SEARCH_LIMIT + 1).forEach((uuid, name) -> {
                if (!uuid.equals(player) && result.size() < SEARCH_LIMIT) {
                    result.add(backend.user(uuid, name));
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
        if (chatId == null && !backend.privacy().allows(target, player, Kind.MESSAGES)) {
            backend.privacy().deny(player, "privacy_messages", "");
            return;
        }
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

    static String directKey(UUID a, UUID b) {
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
        ContentFilter.Verdict verdict = backend.filter(player, ContentFilter.Context.GROUP_NAME, title);
        if (verdict.blocked()) {
            fail(player, verdict.reason());
            return;
        }
        title = verdict.text();
        Set<UUID> invited = new LinkedHashSet<>(p.members());
        invited.remove(player);
        Set<UUID> known = new LinkedHashSet<>(repo.userNames(invited).keySet());
        int refused = removeRefusingInvites(player, known);
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
        if (refused > 0) {
            backend.privacy().deny(player, "privacy_invites", String.valueOf(refused));
        }
    }

    /** Убрать из приглашённых тех, кто не разрешает приглашать себя этому игроку; вернуть их число. */
    private int removeRefusingInvites(UUID player, Set<UUID> invited) throws SQLException {
        int refused = 0;
        for (java.util.Iterator<UUID> it = invited.iterator(); it.hasNext(); ) {
            if (!backend.privacy().allows(it.next(), player, Kind.INVITES)) {
                it.remove();
                refused++;
            }
        }
        return refused;
    }

    /** В личном чате — проверить приватность собеседника; в группе — всегда можно. */
    private boolean directAllowed(UUID player, long chatId, Kind kind, String errorKey) throws SQLException {
        ChatRow chat = repo.chat(chatId);
        if (chat == null || chat.type() != ChatType.DIRECT) {
            return true;
        }
        for (UUID member : repo.members(chatId)) {
            if (!member.equals(player) && !backend.privacy().allows(member, player, kind)) {
                backend.privacy().deny(player, errorKey, "");
                return false;
            }
        }
        return true;
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
                ContentFilter.Verdict verdict = backend.filter(player, ContentFilter.Context.GROUP_NAME, title);
                if (verdict.blocked()) {
                    fail(player, verdict.reason());
                    return;
                }
                title = verdict.text();
                repo.renameChat(chat.id(), title);
                pushChat(repo.chat(chat.id()), repo.members(chat.id()));
            }
            case ADD -> {
                List<UUID> current = repo.members(chat.id());
                Set<UUID> known = new LinkedHashSet<>(repo.userNames(p.users()).keySet());
                known.removeAll(current);
                int refused = removeRefusingInvites(player, known);
                if (refused > 0) {
                    backend.privacy().deny(player, "privacy_invites", String.valueOf(refused));
                }
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
        UserInfo user = new UserInfo(player, online().get(player), true);
        for (UUID member : repo.members(p.chatId())) {
            if (!member.equals(player) && backend.isOnline(member)) {
                send(member, new MessengerPackets.TypingState(p.chatId(), user));
            }
        }
    }

    // --- Сборка данных ---

    /** Отправить актуальную карточку чата перечисленным участникам (каждому — со своими непрочитанными). */
    private void pushChat(ChatRow chat, List<UUID> recipients) throws SQLException {
        for (UUID uuid : recipients) {
            if (backend.isOnline(uuid)) {
                send(uuid, new MessengerPackets.ChatUpdate(chatInfo(chat, uuid)));
            }
        }
    }

    ChatInfo chatInfo(ChatRow row, UUID viewer) throws SQLException {
        List<UUID> memberIds = repo.members(row.id());
        MessageRow last = repo.lastMessage(row.id());
        Map<UUID, String> names = names(memberIds);
        List<UserInfo> members = memberIds.stream().map(u -> backend.user(u, names.get(u))).toList();
        MessageInfo lastInfo = last == null ? null : messageInfos(List.of(last)).get(0);
        return new ChatInfo(row.id(), row.type(), row.title(), row.owner(), members, lastInfo, repo.unread(row.id(), viewer),
                row.createdAt());
    }

    private Map<UUID, String> names(List<UUID> uuids) throws SQLException {
        return repo.userNames(new LinkedHashSet<>(uuids));
    }

    /** Сообщения для пакетов: ники отправителей и размеры фото одним запросом. */
    List<MessageInfo> messageInfos(List<MessageRow> rows) throws SQLException {
        Map<UUID, String> names = names(rows.stream().map(MessageRow::sender).toList());
        Map<Long, ImageRef> images = backend.imageRepo().refs(rows.stream().map(MessageRow::imageId).toList());
        Map<Long, VoiceRef> voices = backend.voices().repo().refs(rows.stream().map(MessageRow::voiceId).toList());
        Map<Long, Long> stickerIds = repo.stickerIds(rows.stream().map(MessageRow::id).toList());
        Map<Long, Long> stickerPacks = backend.stickers().packsOf(stickerIds.values());
        Map<Long, MessengerRepository.Extra> extras = repo.extras(rows.stream().map(MessageRow::id).toList());
        Map<Long, MessengerData.PostShare> posts = backend.social().sharePreviews(
                extras.values().stream().map(MessengerRepository.Extra::postId).filter(id -> id > 0).toList());
        List<MessageInfo> result = new ArrayList<>(rows.size());
        for (MessageRow row : rows) {
            ImageRef image = images.get(row.imageId());
            Long stickerId = stickerIds.get(row.id());
            dev.blockphone.common.protocol.data.StickerData.StickerRef sticker = stickerId != null && image != null && !row.deleted()
                    ? new dev.blockphone.common.protocol.data.StickerData.StickerRef(stickerId, stickerPacks.getOrDefault(stickerId, 0L), image)
                    : null;
            MessengerRepository.Extra extra = extras.get(row.id());
            result.add(new MessageInfo(row.id(), row.chatId(), backend.user(row.sender(), names.get(row.sender())), row.text(),
                    image, voices.get(row.voiceId()), row.sentAt(), row.editedAt(), row.deleted(), sticker,
                    extra != null ? extra.forwardedFrom() : "", extra != null && !row.deleted() ? posts.get(extra.postId()) : null));
        }
        return result;
    }

    private void broadcastPresence(UserInfo user) {
        for (UUID uuid : online().keySet()) {
            if (!uuid.equals(user.uuid())) {
                send(uuid, new MessengerPackets.Presence(user));
            }
        }
    }

    private void fail(UUID player, String key) {
        send(player, new MessengerPackets.Failure("messenger.error." + key, ""));
    }

    /** Сообщение-стикер: стикер должен существовать; в личке — как фото (приватность «фото»). */
    private void sendSticker(UUID player, MessengerPackets.SendMessage p) throws SQLException {
        if (!repo.isMember(p.chatId(), player)) {
            fail(player, "not_member");
            return;
        }
        if (filterMuted(player) || !directAllowed(player, p.chatId(), Kind.PHOTOS, "privacy_photos")) {
            return;
        }
        if (!sendLimit.allow(player)) {
            fail(player, "rate_limit");
            return;
        }
        dev.blockphone.common.protocol.data.StickerData.StickerRef sticker = backend.stickers().sticker(p.stickerId());
        if (sticker == null) {
            send(player, new MessengerPackets.Failure("sticker.error.not_found", ""));
            return;
        }
        long now = System.currentTimeMillis();
        long id = repo.insertMessage(p.chatId(), player, "", sticker.image().id(), 0, now);
        repo.setSticker(id, sticker.id());
        repo.markRead(p.chatId(), player, id);
        MessageInfo message = new MessageInfo(id, p.chatId(), backend.user(player, online().get(player)), "", sticker.image(), null, now, 0,
                false, sticker);
        List<UUID> members = repo.members(p.chatId());
        for (UUID member : members) {
            if (backend.isOnline(member)) {
                send(member, new MessengerPackets.NewMessage(message, member.equals(player) ? p.nonce() : ""));
            }
        }
        backend.moderation().logMessage(repo.chat(p.chatId()), player, members, "", false, false, true);
    }

    /**
     * Переслать сообщение: копия текста, фото и голосового (свои строки в images/voices, привязанные к новому чату), стикер —
     * тот же; «Переслано от» — автор оригинала (у пересланного дальше — первый автор). Проверки — как у обычной отправки.
     */
    private void forward(UUID player, MessengerPackets.ForwardMessage p) throws SQLException {
        MessageRow row = repo.message(p.messageId());
        if (row == null || row.deleted() || !repo.isMember(row.chatId(), player)) {
            fail(player, "not_found");
            return;
        }
        if (!repo.isMember(p.toChatId(), player)) {
            fail(player, "not_member");
            return;
        }
        if (filterMuted(player)) {
            return;
        }
        Long stickerId = repo.stickerIds(List.of(row.id())).get(row.id());
        boolean photo = row.imageId() > 0;
        if (!directAllowed(player, p.toChatId(), Kind.MESSAGES, "privacy_messages")
                || (photo && !directAllowed(player, p.toChatId(), Kind.PHOTOS, "privacy_photos"))
                || (row.voiceId() > 0 && !directAllowed(player, p.toChatId(), Kind.VOICE, "privacy_voice"))) {
            return;
        }
        if (!sendLimit.allow(player)) {
            fail(player, "rate_limit");
            return;
        }
        long now = System.currentTimeMillis();
        long image = 0;
        if (photo) {
            image = stickerId != null ? row.imageId() : backend.imageRepo().copy(row.imageId(), player, ImageRepository.KIND_MESSAGE, p.toChatId(), now);
        }
        long voice = row.voiceId() > 0 ? backend.voices().repo().copy(row.voiceId(), player, p.toChatId(), now) : 0;
        MessengerRepository.Extra extra = repo.extras(List.of(row.id())).get(row.id());
        String from = extra != null && !extra.forwardedFrom().isEmpty() ? extra.forwardedFrom()
                : names(List.of(row.sender())).getOrDefault(row.sender(), "?");
        long id = repo.insertMessage(p.toChatId(), player, row.text(), image, voice, now);
        if (stickerId != null) {
            repo.setSticker(id, stickerId);
        }
        repo.setExtra(id, from, extra != null ? extra.postId() : 0);
        deliver(player, p.toChatId(), id, p.nonce(), row.text(), photo && stickerId == null, voice > 0, stickerId != null);
    }

    /** Поделиться постом ленты в чате: сообщение с превью поста (и своим комментарием). */
    private void sharePost(UUID player, MessengerPackets.SharePost p) throws SQLException {
        if (!repo.isMember(p.toChatId(), player)) {
            fail(player, "not_member");
            return;
        }
        MessengerData.PostShare preview = backend.social().sharePreviews(List.of(p.postId())).get(p.postId());
        if (preview == null || preview.deleted()) {
            send(player, new MessengerPackets.Failure("social.error.post_not_found", ""));
            return;
        }
        if (filterMuted(player) || !directAllowed(player, p.toChatId(), Kind.MESSAGES, "privacy_messages")) {
            return;
        }
        ContentFilter.Verdict verdict = backend.filter(player, messageContext(p.toChatId()), p.comment().strip());
        if (verdict.blocked()) {
            fail(player, verdict.reason());
            return;
        }
        if (!sendLimit.allow(player)) {
            fail(player, "rate_limit");
            return;
        }
        long now = System.currentTimeMillis();
        long id = repo.insertMessage(p.toChatId(), player, verdict.text(), 0, 0, now);
        repo.setExtra(id, "", p.postId());
        deliver(player, p.toChatId(), id, p.nonce(), verdict.text(), false, false, false);
    }

    /** Разослать участникам только что записанное сообщение (полные сведения — как в истории) и записать в лог. */
    private void deliver(UUID player, long chatId, long id, String nonce, String text, boolean photo, boolean voice, boolean sticker)
            throws SQLException {
        repo.markRead(chatId, player, id);
        MessageInfo message = messageInfos(List.of(repo.message(id))).get(0);
        List<UUID> members = repo.members(chatId);
        for (UUID member : members) {
            if (backend.isOnline(member)) {
                send(member, new MessengerPackets.NewMessage(message, member.equals(player) ? nonce : ""));
            }
        }
        backend.moderation().logMessage(repo.chat(chatId), player, members, text, photo, voice, sticker);
    }

    /** Мут модератора или автомьют фильтра: сообщить игроку, до какого времени, и не пропустить сообщение. */
    private boolean filterMuted(UUID player) throws SQLException {
        long muted = backend.moderation().mutedUntil(player);
        if (muted > 0) {
            send(player, new MessengerPackets.Failure("messenger.error.muted",
                    muted == dev.blockphone.common.protocol.data.ModerationData.FOREVER ? "" : String.valueOf(muted)));
            return true;
        }
        long until = backend.filterMutedUntil(player);
        if (until <= 0) {
            return false;
        }
        long minutes = Math.max(1, (until - System.currentTimeMillis() + 59_999) / 60_000);
        send(player, new MessengerPackets.Failure("messenger.error.filter_muted", String.valueOf(minutes)));
        return true;
    }

    private void send(UUID player, Packet packet) {
        backend.send(player, packet);
    }

    // --- Для отладки и тестов ---

    /** Выполнить действие в фоновом потоке (например, добавить тестового игрока). */
    public void runInThread(SqlRunnable action) {
        backend.execute(() -> action.run(repo));
    }

    @FunctionalInterface
    public interface SqlRunnable {
        void run(MessengerRepository repo) throws SQLException;
    }

    /** Сообщение «от имени» игрока, который может быть и не онлайн (для отладки). */
    public void injectMessage(UUID sender, String senderName, long chatId, String text) {
        backend.execute(() -> {
            long now = System.currentTimeMillis();
            long id = repo.insertMessage(chatId, sender, text, now);
            MessageInfo message = new MessageInfo(id, chatId, new UserInfo(sender, senderName, backend.isOnline(sender)),
                    text, null, null, now, 0, false);
            for (UUID member : repo.members(chatId)) {
                if (backend.isOnline(member)) {
                    send(member, new MessengerPackets.NewMessage(message, ""));
                }
            }
        });
    }
}
