package dev.blockphone.fabric.client.telegram;

import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.music.Accounts;
import dev.blockphone.fabric.client.storage.ClientFiles;
import it.tdlight.ClientFactory;
import it.tdlight.Init;
import it.tdlight.Log;
import it.tdlight.jni.TdApi;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Настоящий Telegram в телефоне через TDLib (TDLight, нативная библиотека — только Windows x64).
 * Вход по номеру телефона, коду и паролю (2FA), список чатов, переписка, отправка текста и «кружочков».
 * Данные TDLib — в {@code blockphone/telegram/}. Ключи api_id/api_hash игрок получает на my.telegram.org.
 * Состояние меняется только на потоке рендера (обновления TDLib пересылаются туда).
 */
public final class Telegram {
    public enum Status {
        /** Не Windows x64 — нативной библиотеки нет. */
        UNSUPPORTED,
        /** Нет api_id/api_hash. */
        NOT_CONFIGURED,
        /** Ещё не запускали (или клиент закрыт). */
        CLOSED,
        STARTING,
        WAIT_PHONE,
        WAIT_CODE,
        WAIT_PASSWORD,
        /** Нужна регистрация/почта/подтверждение — сделать в официальном приложении. */
        WAIT_OTHER,
        READY,
        /** Нативная библиотека не загрузилась. */
        ERROR
    }

    /** Загруженная часть переписки (от старых к новым). */
    public static final class ChatMessages {
        public final List<TdApi.Message> list = new ArrayList<>();
        public boolean loaded;
        public boolean loading;
        public boolean hasMore = true;
    }

    private static Status status = Status.CLOSED;
    private static @Nullable String fatal;
    private static @Nullable String message;
    private static long messageAt;
    private static @Nullable String passwordHint;
    private static @Nullable String codePhone;
    private static boolean nativeLoaded;
    private static boolean restartAfterClose;
    private static @Nullable ClientFactory factory;
    private static it.tdlight.@Nullable TelegramClient client;
    private static long myId;
    private static long openChatId;

    private static final Map<Long, TdApi.Chat> CHATS = new HashMap<>();
    private static final Map<Long, TdApi.User> USERS = new HashMap<>();
    private static final Map<Integer, TdApi.File> FILES = new HashMap<>();
    private static final Set<Integer> DOWNLOADING = new HashSet<>();
    private static final Map<Long, ChatMessages> MESSAGES = new HashMap<>();

    // --- Состояние ---

    public static boolean supported() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return os.startsWith("windows") && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    public static Status status() {
        return status;
    }

    /** Текст фатальной ошибки (статус ERROR). */
    public static @Nullable String fatal() {
        return fatal;
    }

    /** Ошибка от Telegram (неверный код и т. п.), если была не позже 5 секунд назад. */
    public static @Nullable String recentMessage() {
        return message != null && System.currentTimeMillis() - messageAt < 5000 ? message : null;
    }

    public static @Nullable String passwordHint() {
        return passwordHint;
    }

    public static @Nullable String codePhone() {
        return codePhone;
    }

    public static long myId() {
        return myId;
    }

    /** Запустить клиент при первом открытии приложения. */
    public static void ensureStarted() {
        if (status == Status.CLOSED || status == Status.NOT_CONFIGURED) {
            start();
        }
    }

    // --- Запуск и вход ---

    public static void configure(int apiId, String apiHash) {
        Accounts a = Accounts.get();
        a.telegramApiId = apiId;
        a.telegramApiHash = apiHash.strip();
        Accounts.save();
        start();
    }

    private static void start() {
        if (!supported()) {
            status = Status.UNSUPPORTED;
            return;
        }
        Accounts a = Accounts.get();
        if (a.telegramApiId <= 0 || a.telegramApiHash.isBlank()) {
            status = Status.NOT_CONFIGURED;
            return;
        }
        if (client != null) {
            return;
        }
        try {
            if (!nativeLoaded) {
                Init.init();
                Log.setVerbosityLevel(1);
                nativeLoaded = true;
            }
            if (factory == null) {
                factory = ClientFactory.create();
            }
            client = factory.createClient();
            it.tdlight.UpdatesHandler handler = updates -> Minecraft.getInstance().execute(() -> updates.forEach(Telegram::handle));
            client.initialize(handler,
                    error -> BlockPhoneFabric.LOGGER.warn("Telegram update error", error),
                    error -> BlockPhoneFabric.LOGGER.warn("Telegram error", error));
            status = Status.STARTING;
        } catch (Throwable e) {
            BlockPhoneFabric.LOGGER.error("TDLib failed to start", e);
            fatal = e.getClass().getSimpleName() + ": " + e.getMessage();
            status = Status.ERROR;
            client = null;
        }
    }

    /** Отправить запрос TDLib; ответ — на потоке рендера (ошибки показываются сами). */
    public static <R extends TdApi.Object> void send(TdApi.Function<R> function, @Nullable Consumer<TdApi.Object> onResult) {
        it.tdlight.TelegramClient c = client;
        if (c == null) {
            return;
        }
        c.send(function, result -> Minecraft.getInstance().execute(() -> {
            if (result instanceof TdApi.Error error) {
                showError(error);
            }
            if (onResult != null) {
                onResult.accept(result);
            }
        }), error -> BlockPhoneFabric.LOGGER.warn("Telegram request failed", error));
    }

    private static void showError(TdApi.Error error) {
        String key = "telegram.error." + error.message.toLowerCase(Locale.ROOT);
        String text = PhoneLang.tr(key);
        message = text.equals(key) ? PhoneLang.tr("telegram.error.generic", error.message) : text;
        messageAt = System.currentTimeMillis();
    }

    public static void submitPhone(String phone) {
        TdApi.SetAuthenticationPhoneNumber request = new TdApi.SetAuthenticationPhoneNumber();
        request.phoneNumber = phone.replaceAll("[^+0-9]", "");
        send(request, null);
    }

    public static void submitCode(String code) {
        TdApi.CheckAuthenticationCode request = new TdApi.CheckAuthenticationCode();
        request.code = code.strip();
        send(request, null);
    }

    public static void submitPassword(String password) {
        TdApi.CheckAuthenticationPassword request = new TdApi.CheckAuthenticationPassword();
        request.password = password;
        send(request, null);
    }

    /** Выйти из аккаунта (сессия удаляется); после закрытия клиент запустится снова — для нового входа. */
    public static void logOut() {
        restartAfterClose = true;
        send(new TdApi.LogOut(), null);
    }

    /** Закрыть клиент при выходе из игры. */
    public static void shutdown() {
        restartAfterClose = false;
        if (client != null) {
            send(new TdApi.Close(), null);
        }
    }

    // --- Обновления ---

    private static void handle(TdApi.Object update) {
        switch (update) {
            case TdApi.UpdateAuthorizationState u -> onAuth(u.authorizationState);
            case TdApi.UpdateNewChat u -> CHATS.put(u.chat.id, u.chat);
            case TdApi.UpdateChatTitle u -> withChat(u.chatId, c -> c.title = u.title);
            case TdApi.UpdateChatPhoto u -> withChat(u.chatId, c -> c.photo = u.photo);
            case TdApi.UpdateChatLastMessage u -> withChat(u.chatId, c -> {
                c.lastMessage = u.lastMessage;
                c.positions = u.positions;
            });
            case TdApi.UpdateChatPosition u -> withChat(u.chatId, c -> c.positions = merge(c.positions, u.position));
            case TdApi.UpdateChatReadInbox u -> withChat(u.chatId, c -> {
                c.lastReadInboxMessageId = u.lastReadInboxMessageId;
                c.unreadCount = u.unreadCount;
            });
            case TdApi.UpdateChatNotificationSettings u -> withChat(u.chatId, c -> c.notificationSettings = u.notificationSettings);
            case TdApi.UpdateUser u -> USERS.put(u.user.id, u.user);
            case TdApi.UpdateFile u -> {
                FILES.put(u.file.id, u.file);
                if (u.file.local.isDownloadingCompleted) {
                    DOWNLOADING.remove(u.file.id);
                }
            }
            case TdApi.UpdateNewMessage u -> onNewMessage(u.message);
            case TdApi.UpdateMessageSendSucceeded u -> replaceMessage(u.message.chatId, u.oldMessageId, u.message);
            case TdApi.UpdateMessageContent u -> {
                ChatMessages m = MESSAGES.get(u.chatId);
                if (m != null) {
                    m.list.stream().filter(x -> x.id == u.messageId).forEach(x -> x.content = u.newContent);
                }
            }
            case TdApi.UpdateDeleteMessages u -> {
                if (u.isPermanent) {
                    ChatMessages m = MESSAGES.get(u.chatId);
                    if (m != null) {
                        Set<Long> ids = new HashSet<>();
                        for (long id : u.messageIds) {
                            ids.add(id);
                        }
                        m.list.removeIf(x -> ids.contains(x.id));
                    }
                }
            }
            default -> {
            }
        }
    }

    private static void onAuth(TdApi.AuthorizationState state) {
        switch (state) {
            case TdApi.AuthorizationStateWaitTdlibParameters s -> sendParameters();
            case TdApi.AuthorizationStateWaitPhoneNumber s -> status = Status.WAIT_PHONE;
            case TdApi.AuthorizationStateWaitCode s -> {
                codePhone = s.codeInfo.phoneNumber;
                status = Status.WAIT_CODE;
            }
            case TdApi.AuthorizationStateWaitPassword s -> {
                passwordHint = s.passwordHint;
                status = Status.WAIT_PASSWORD;
            }
            case TdApi.AuthorizationStateReady s -> {
                status = Status.READY;
                send(new TdApi.GetMe(), r -> {
                    if (r instanceof TdApi.User me) {
                        myId = me.id;
                        USERS.put(me.id, me);
                    }
                });
                TdApi.LoadChats load = new TdApi.LoadChats();
                load.chatList = new TdApi.ChatListMain();
                load.limit = 100;
                send(load, null);
            }
            case TdApi.AuthorizationStateLoggingOut s -> status = Status.STARTING;
            case TdApi.AuthorizationStateClosing s -> status = Status.STARTING;
            case TdApi.AuthorizationStateClosed s -> {
                client = null;
                clearData();
                status = Status.CLOSED;
                if (restartAfterClose) {
                    restartAfterClose = false;
                    start();
                }
            }
            default -> status = Status.WAIT_OTHER;
        }
    }

    private static void sendParameters() {
        Accounts a = Accounts.get();
        Path dir = ClientFiles.root().resolve("telegram");
        TdApi.SetTdlibParameters p = new TdApi.SetTdlibParameters();
        p.databaseDirectory = dir.resolve("db").toAbsolutePath().toString();
        p.filesDirectory = dir.resolve("files").toAbsolutePath().toString();
        p.databaseEncryptionKey = new byte[0];
        p.useFileDatabase = true;
        p.useChatInfoDatabase = true;
        p.useMessageDatabase = true;
        p.useSecretChats = false;
        p.apiId = a.telegramApiId;
        p.apiHash = a.telegramApiHash;
        p.systemLanguageCode = PhoneLang.current().startsWith("ru") ? "ru" : "en";
        p.deviceModel = "Minecraft (BlockPhone)";
        p.systemVersion = System.getProperty("os.name", "Windows");
        p.applicationVersion = BlockPhoneFabric.VERSION;
        send(p, r -> {
            if (r instanceof TdApi.Error error) {
                fatal = error.message;
                status = Status.ERROR;
            }
        });
    }

    private static void withChat(long chatId, Consumer<TdApi.Chat> action) {
        TdApi.Chat chat = CHATS.get(chatId);
        if (chat != null) {
            action.accept(chat);
        }
    }

    private static TdApi.ChatPosition[] merge(TdApi.ChatPosition[] positions, TdApi.ChatPosition position) {
        List<TdApi.ChatPosition> list = new ArrayList<>();
        if (positions != null) {
            for (TdApi.ChatPosition p : positions) {
                if (p.list.getConstructor() != position.list.getConstructor()) {
                    list.add(p);
                }
            }
        }
        if (position.order != 0) {
            list.add(position);
        }
        return list.toArray(TdApi.ChatPosition[]::new);
    }

    private static void onNewMessage(TdApi.Message message) {
        ChatMessages m = MESSAGES.get(message.chatId);
        if (m != null && m.loaded && m.list.stream().noneMatch(x -> x.id == message.id)) {
            m.list.add(message);
        }
        if (message.chatId == openChatId) {
            markRead(message.chatId, message.id);
        } else if (!message.isOutgoing) {
            TdApi.Chat chat = CHATS.get(message.chatId);
            if (chat != null && (chat.notificationSettings == null || chat.notificationSettings.muteFor == 0)) {
                TelegramNotifications.notify(chat, message);
            }
        }
    }

    private static void replaceMessage(long chatId, long oldId, TdApi.Message message) {
        ChatMessages m = MESSAGES.get(chatId);
        if (m != null) {
            for (int i = 0; i < m.list.size(); i++) {
                if (m.list.get(i).id == oldId) {
                    m.list.set(i, message);
                }
            }
        }
    }

    // --- Чаты и сообщения ---

    /** Чаты основного списка, по порядку Telegram (закреплённые сверху). */
    public static List<TdApi.Chat> chats() {
        List<TdApi.Chat> list = new ArrayList<>();
        for (TdApi.Chat chat : CHATS.values()) {
            if (mainOrder(chat) != 0) {
                list.add(chat);
            }
        }
        list.sort(Comparator.comparingLong(Telegram::mainOrder).reversed());
        return list;
    }

    private static long mainOrder(TdApi.Chat chat) {
        if (chat.positions != null) {
            for (TdApi.ChatPosition p : chat.positions) {
                if (p.list instanceof TdApi.ChatListMain) {
                    return p.order;
                }
            }
        }
        return 0;
    }

    public static @Nullable TdApi.Chat chat(long id) {
        return CHATS.get(id);
    }

    public static @Nullable TdApi.User user(long id) {
        return USERS.get(id);
    }

    /** Непрочитанные в чатах без отключённых уведомлений (для значка). */
    public static int unreadTotal() {
        if (status != Status.READY) {
            return 0;
        }
        int total = 0;
        for (TdApi.Chat chat : CHATS.values()) {
            if (mainOrder(chat) != 0 && (chat.notificationSettings == null || chat.notificationSettings.muteFor == 0)) {
                total += chat.unreadCount;
            }
        }
        return total;
    }

    public static ChatMessages messages(long chatId) {
        return MESSAGES.computeIfAbsent(chatId, k -> new ChatMessages());
    }

    /** Открыть чат: история, отметка «прочитано». */
    public static void openChat(long chatId) {
        openChatId = chatId;
        TdApi.OpenChat open = new TdApi.OpenChat();
        open.chatId = chatId;
        send(open, null);
        ChatMessages m = messages(chatId);
        if (!m.loaded) {
            loadHistory(chatId, 0);
        }
        TdApi.Chat chat = CHATS.get(chatId);
        if (chat != null && chat.lastMessage != null) {
            markRead(chatId, chat.lastMessage.id);
        }
    }

    public static void closeChat(long chatId) {
        if (openChatId == chatId) {
            openChatId = 0;
        }
        TdApi.CloseChat close = new TdApi.CloseChat();
        close.chatId = chatId;
        send(close, null);
    }

    /** Подгрузить сообщения старше fromMessageId (0 — последние). TDLib может отдать меньше, чем просили. */
    public static void loadHistory(long chatId, long fromMessageId) {
        ChatMessages m = messages(chatId);
        if (m.loading || (!m.hasMore && fromMessageId != 0)) {
            return;
        }
        m.loading = true;
        TdApi.GetChatHistory request = new TdApi.GetChatHistory();
        request.chatId = chatId;
        request.fromMessageId = fromMessageId;
        request.offset = 0;
        request.limit = 40;
        request.onlyLocal = false;
        send(request, r -> {
            m.loading = false;
            if (r instanceof TdApi.Messages result) {
                List<TdApi.Message> older = new ArrayList<>();
                for (TdApi.Message msg : result.messages) {
                    if (m.list.stream().noneMatch(x -> x.id == msg.id)) {
                        older.add(msg);
                    }
                }
                older.sort(Comparator.comparingLong(x -> x.id));
                if (fromMessageId == 0) {
                    m.list.clear();
                }
                m.list.addAll(0, older);
                m.list.sort(Comparator.comparingLong(x -> x.id));
                m.hasMore = result.messages.length > 0;
                m.loaded = true;
                // Первая порция из локальной базы бывает короткой — догрузить.
                if (fromMessageId == 0 && m.list.size() < 15 && m.hasMore && !m.list.isEmpty()) {
                    loadHistory(chatId, m.list.get(0).id);
                }
            }
        });
    }

    public static void loadOlder(long chatId) {
        ChatMessages m = messages(chatId);
        if (m.loaded && !m.list.isEmpty()) {
            loadHistory(chatId, m.list.get(0).id);
        }
    }

    public static void markRead(long chatId, long messageId) {
        TdApi.ViewMessages view = new TdApi.ViewMessages();
        view.chatId = chatId;
        view.messageIds = new long[]{messageId};
        view.forceRead = true;
        send(view, null);
    }

    public static void sendText(long chatId, String text) {
        TdApi.InputMessageText content = new TdApi.InputMessageText();
        content.text = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
        content.clearDraft = true;
        sendContent(chatId, content);
    }

    /** Отправить «кружочек» (MP4, квадрат length×length). */
    public static void sendVideoNote(long chatId, Path file, int durationSeconds, int length) {
        TdApi.InputVideoNote note = new TdApi.InputVideoNote();
        note.videoNote = new TdApi.InputFileLocal(file.toAbsolutePath().toString());
        note.duration = durationSeconds;
        note.length = length;
        TdApi.InputMessageVideoNote content = new TdApi.InputMessageVideoNote();
        content.videoNote = note;
        sendContent(chatId, content);
    }

    private static void sendContent(long chatId, TdApi.InputMessageContent content) {
        TdApi.SendMessage send = new TdApi.SendMessage();
        send.chatId = chatId;
        send.inputMessageContent = content;
        send(send, null);
    }

    // --- Файлы ---

    /** Файл с учётом последних обновлений; если ещё не скачан — запросить скачивание. null — нет. */
    public static @Nullable Path localFile(@Nullable TdApi.File file) {
        if (file == null) {
            return null;
        }
        TdApi.File current = FILES.getOrDefault(file.id, file);
        if (current.local != null && current.local.isDownloadingCompleted && !current.local.path.isEmpty()) {
            return Path.of(current.local.path);
        }
        if (current.local != null && current.local.canBeDownloaded && DOWNLOADING.add(current.id)) {
            TdApi.DownloadFile download = new TdApi.DownloadFile();
            download.fileId = current.id;
            download.priority = 1;
            send(download, r -> {
                if (r instanceof TdApi.File f) {
                    FILES.put(f.id, f);
                }
            });
        }
        return null;
    }

    // --- Подписи ---

    public static String title(TdApi.Chat chat) {
        if (chat.id == myId || (chat.type instanceof TdApi.ChatTypePrivate p && p.userId == myId)) {
            return PhoneLang.tr("telegram.saved");
        }
        return chat.title;
    }

    public static String senderName(TdApi.Message message) {
        if (message.senderId instanceof TdApi.MessageSenderUser u) {
            TdApi.User user = USERS.get(u.userId);
            if (user != null) {
                return (user.firstName + " " + user.lastName).strip();
            }
        } else if (message.senderId instanceof TdApi.MessageSenderChat c) {
            TdApi.Chat chat = CHATS.get(c.chatId);
            if (chat != null) {
                return chat.title;
            }
        }
        return "?";
    }

    /** Текст сообщения или подпись вложения: «[Фото] подпись», «[Кружочек]»... */
    public static String text(TdApi.Message message) {
        return switch (message.content) {
            case TdApi.MessageText t -> t.text.text;
            case TdApi.MessagePhoto p -> join("telegram.photo", p.caption);
            case TdApi.MessageVideo v -> join("telegram.video", v.caption);
            case TdApi.MessageAnimation a -> join("telegram.gif", a.caption);
            case TdApi.MessageDocument d -> PhoneLang.tr("telegram.file") + " " + d.document.fileName;
            case TdApi.MessageVoiceNote v -> PhoneLang.tr("telegram.voice");
            case TdApi.MessageVideoNote v -> PhoneLang.tr("telegram.video_note");
            case TdApi.MessageSticker s -> s.sticker.emoji + " " + PhoneLang.tr("telegram.sticker");
            case TdApi.MessageAudio a -> PhoneLang.tr("telegram.audio") + " " + a.audio.title;
            case TdApi.MessageContact c -> PhoneLang.tr("telegram.contact");
            case TdApi.MessageLocation l -> PhoneLang.tr("telegram.location");
            case TdApi.MessagePoll p -> PhoneLang.tr("telegram.poll");
            default -> PhoneLang.tr("telegram.service");
        };
    }

    private static String join(String key, @Nullable TdApi.FormattedText caption) {
        String label = PhoneLang.tr(key);
        return caption == null || caption.text.isEmpty() ? label : label + " " + caption.text;
    }

    public static String preview(TdApi.Chat chat) {
        TdApi.Message last = chat.lastMessage;
        if (last == null) {
            return "";
        }
        String text = text(last).replace('\n', ' ');
        if (last.isOutgoing) {
            return PhoneLang.tr("messenger.you") + ": " + text;
        }
        return chat.type instanceof TdApi.ChatTypeBasicGroup || chat.type instanceof TdApi.ChatTypeSupergroup s && !s.isChannel
                ? senderName(last) + ": " + text : text;
    }

    private static void clearData() {
        CHATS.clear();
        USERS.clear();
        FILES.clear();
        DOWNLOADING.clear();
        MESSAGES.clear();
        myId = 0;
        openChatId = 0;
        passwordHint = null;
        codePhone = null;
    }

    private Telegram() {
    }
}
