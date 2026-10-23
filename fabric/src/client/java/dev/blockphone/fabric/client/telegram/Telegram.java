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
            TelegramProxy.onClientStarted();
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
            if (result instanceof TdApi.Error error && !(function instanceof TdApi.GetMessage)) {
                showError(error);
            }
            if (onResult != null) {
                onResult.accept(result);
            }
        }), error -> BlockPhoneFabric.LOGGER.warn("Telegram request failed", error));
    }

    /** Клиент TDLib запущен (можно слать запросы). */
    public static boolean running() {
        return client != null;
    }

    /** Запрос без показа ошибок игроку (проверка прокси и т. п.); ответ — на потоке рендера. */
    static void sendQuiet(TdApi.Function<?> function, Consumer<TdApi.Object> onResult) {
        it.tdlight.TelegramClient c = client;
        if (c == null) {
            return;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        TdApi.Function<TdApi.Object> f = (TdApi.Function) function;
        c.send(f, result -> Minecraft.getInstance().execute(() -> onResult.accept(result)),
                error -> Minecraft.getInstance().execute(() -> onResult.accept(new TdApi.Error(500, String.valueOf(error)))));
    }

    private static void showError(TdApi.Error error) {
        BlockPhoneFabric.LOGGER.info("Telegram error {}: {}", error.code, error.message);
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
            case TdApi.UpdateConnectionState u -> TelegramProxy.onConnectionState(u.state);
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
            case TdApi.UpdateUserStatus u -> {
                TdApi.User user = USERS.get(u.userId);
                if (user != null) {
                    user.status = u.status;
                }
            }
            case TdApi.UpdateSupergroup u -> SUPERGROUPS.put(u.supergroup.id, u.supergroup);
            case TdApi.UpdateBasicGroup u -> BASIC_GROUPS.put(u.basicGroup.id, u.basicGroup);
            case TdApi.UpdateChatReadOutbox u -> withChat(u.chatId, c -> c.lastReadOutboxMessageId = u.lastReadOutboxMessageId);
            case TdApi.UpdateScopeNotificationSettings u -> SCOPES.put(u.scope.getClass(), u.notificationSettings);
            case TdApi.UpdateChatAction u -> {
                long sender = u.senderId instanceof TdApi.MessageSenderUser s ? s.userId : 0;
                java.util.Map<Long, Object[]> chatTyping = TYPING.computeIfAbsent(u.chatId, k -> new HashMap<>());
                if (u.action instanceof TdApi.ChatActionCancel) {
                    chatTyping.remove(sender);
                } else {
                    chatTyping.put(sender, new Object[]{u.action, System.currentTimeMillis() + 6000});
                }
            }
            case TdApi.UpdateFile u -> {
                FILES.put(u.file.id, u.file);
                if (u.file.local.isDownloadingCompleted) {
                    DOWNLOADING.remove(u.file.id);
                }
            }
            case TdApi.UpdateNewMessage u -> onNewMessage(u.message);
            case TdApi.UpdateMessageSendSucceeded u -> replaceMessage(u.message.chatId, u.oldMessageId, u.message);
            case TdApi.UpdateMessageSendFailed u -> {
                // Например, Telegram не принял файл: сообщение остаётся с «!», причина — в журнал и в приложение.
                BlockPhoneFabric.LOGGER.warn("Telegram: message not sent ({}): {} {}", u.message.content.getClass().getSimpleName(),
                        u.error.code, u.error.message);
                showError(u.error);
                replaceMessage(u.message.chatId, u.oldMessageId, u.message);
            }
            case TdApi.UpdateMessageContent u -> {
                ChatMessages m = MESSAGES.get(u.chatId);
                if (m != null) {
                    m.list.stream().filter(x -> x.id == u.messageId).forEach(x -> x.content = u.newContent);
                }
            }
            case TdApi.UpdateMessageInteractionInfo u -> {
                ChatMessages m = MESSAGES.get(u.chatId);
                if (m != null) {
                    m.list.stream().filter(x -> x.id == u.messageId).forEach(x -> x.interactionInfo = u.interactionInfo);
                }
            }
            case TdApi.UpdateMessageEdited u -> {
                ChatMessages m = MESSAGES.get(u.chatId);
                if (m != null) {
                    m.list.stream().filter(x -> x.id == u.messageId).forEach(x -> x.editDate = u.editDate);
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
                TelegramProxy.onClientClosed();
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
        dev.blockphone.fabric.client.phone.PhoneNotifications.remove("telegram:" + chatId);
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
        sendText(chatId, text, 0);
    }

    /** Текст; replyTo > 0 — ответ на это сообщение. */
    public static void sendText(long chatId, String text, long replyTo) {
        TdApi.InputMessageText content = new TdApi.InputMessageText();
        content.text = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
        content.clearDraft = true;
        sendContent(chatId, content, replyTo);
    }

    /**
     * Фото из галереи: одно — обычным сообщением, несколько — одним альбомом (по 10 в альбоме, как в Telegram).
     * Подпись — у первого фото.
     */
    public static void sendPhotos(long chatId, List<Path> photos, String caption, long replyTo) {
        for (int start = 0; start < photos.size(); start += 10) {
            List<Path> part = photos.subList(start, Math.min(photos.size(), start + 10));
            TdApi.InputMessageContent[] contents = new TdApi.InputMessageContent[part.size()];
            for (int i = 0; i < part.size(); i++) {
                TdApi.InputPhoto photo = new TdApi.InputPhoto();
                photo.photo = new TdApi.InputFileLocal(part.get(i).toAbsolutePath().toString());
                photo.addedStickerFileIds = new int[0];
                TdApi.InputMessagePhoto content = new TdApi.InputMessagePhoto();
                content.photo = photo;
                content.caption = new TdApi.FormattedText(start == 0 && i == 0 ? caption : "", new TdApi.TextEntity[0]);
                contents[i] = content;
            }
            long reply = start == 0 ? replyTo : 0;
            if (contents.length == 1) {
                sendContent(chatId, contents[0], reply);
            } else {
                TdApi.SendMessageAlbum album = new TdApi.SendMessageAlbum();
                album.chatId = chatId;
                album.replyTo = replyTo(reply);
                album.inputMessageContents = contents;
                send(album, null);
            }
        }
    }

    /** Файлы документами (как «Файл» в Telegram); подпись — у первого. */
    public static void sendDocuments(long chatId, List<Path> files, String caption, long replyTo) {
        for (int i = 0; i < files.size(); i++) {
            TdApi.InputMessageDocument content = new TdApi.InputMessageDocument();
            content.document = new TdApi.InputDocument(new TdApi.InputFileLocal(files.get(i).toAbsolutePath().toString()), null, false);
            content.caption = new TdApi.FormattedText(i == 0 ? caption : "", new TdApi.TextEntity[0]);
            sendContent(chatId, content, i == 0 ? replyTo : 0);
        }
    }

    /** Изменить текст своего сообщения. */
    public static void editText(long chatId, long messageId, String text) {
        TdApi.InputMessageText content = new TdApi.InputMessageText();
        content.text = new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
        send(new TdApi.EditMessageText(chatId, messageId, null, content), null);
    }

    /** Удалить сообщения; revoke — у всех (свои), иначе только у себя. */
    public static void deleteMessages(long chatId, long[] ids, boolean revoke) {
        send(new TdApi.DeleteMessages(chatId, ids, revoke), null);
    }

    /** Поставить/снять реакцию-эмодзи. */
    public static void toggleReaction(long chatId, long messageId, String emoji, boolean chosen) {
        TdApi.ReactionType type = new TdApi.ReactionTypeEmoji(emoji);
        if (chosen) {
            send(new TdApi.RemoveMessageReaction(chatId, messageId, type), null);
        } else {
            send(new TdApi.AddMessageReaction(chatId, messageId, type, false, true), null);
        }
    }

    private static final Map<String, TdApi.Message> EXTRA = new HashMap<>();
    private static final Set<String> EXTRA_LOADING = new HashSet<>();

    /** Сообщение по id: из загруженной истории, иначе — запросить (вернётся при следующей отрисовке). */
    public static @Nullable TdApi.Message message(long chatId, long messageId) {
        ChatMessages m = MESSAGES.get(chatId);
        if (m != null) {
            for (TdApi.Message x : m.list) {
                if (x.id == messageId) {
                    return x;
                }
            }
        }
        String key = chatId + ":" + messageId;
        TdApi.Message cached = EXTRA.get(key);
        if (cached == null && EXTRA_LOADING.add(key)) {
            send(new TdApi.GetMessage(chatId, messageId), result -> {
                if (result instanceof TdApi.Message found) {
                    EXTRA.put(key, found);
                }
            });
        }
        return cached;
    }

    /**
     * Для автоскриншотов: поддельный групповой чат (альбом, ответ, пересланное, реакции, правка) без TDLib.
     * Запросы при этом никуда не уходят (клиента нет).
     */
    public static long debugInject(List<Path> pictures) {
        return debugInject(pictures, null);
    }

    /** То же + стикер, голосовое, GIF (video — любой MP4, например записанный «кружочек») и данные панели стикеров. */
    public static long debugInject(List<Path> pictures, @Nullable Path video) {
        status = Status.READY;
        myId = 1;
        USERS.put(1L, debugUser(1, "Я"));
        USERS.put(2L, debugUser(2, "Алекс"));
        USERS.put(3L, debugUser(3, "Стив"));
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = 100;
        chat.title = "Строители";
        chat.type = new TdApi.ChatTypeBasicGroup();
        chat.positions = new TdApi.ChatPosition[]{new TdApi.ChatPosition(new TdApi.ChatListMain(), 2, false, null)};
        chat.unreadCount = 2;
        TdApi.BasicGroup group = new TdApi.BasicGroup();
        group.memberCount = 3;
        BASIC_GROUPS.put(0L, group);
        USERS.get(2L).status = new TdApi.UserStatusOnline();
        // Второй чат — личный, с Алексом (в сети, без звука).
        TdApi.Chat alex = new TdApi.Chat();
        alex.id = 101;
        alex.title = "Алекс";
        alex.type = new TdApi.ChatTypePrivate(2);
        alex.positions = new TdApi.ChatPosition[]{new TdApi.ChatPosition(new TdApi.ChatListMain(), 1, false, null)};
        alex.notificationSettings = new TdApi.ChatNotificationSettings();
        alex.notificationSettings.muteFor = 100000;
        alex.unreadCount = 5;
        CHATS.put(alex.id, alex);
        CHATS.put(chat.id, chat);
        ChatMessages m = new ChatMessages();
        m.loaded = true;
        m.hasMore = false;
        MESSAGES.put(chat.id, m);
        int t = (int) (System.currentTimeMillis() / 1000) - 3600;
        m.list.add(debugMessage(1, 2, t, debugText("Привет! Смотрите, что мы построили за вечер")));
        for (int i = 0; i < Math.min(3, pictures.size()); i++) {
            TdApi.LocalFile local = new TdApi.LocalFile();
            local.path = pictures.get(i).toAbsolutePath().toString();
            local.isDownloadingCompleted = true;
            TdApi.File file = new TdApi.File();
            file.id = -1 - i;
            file.local = local;
            TdApi.MessagePhoto photo = new TdApi.MessagePhoto();
            photo.photo = new TdApi.Photo();
            photo.photo.sizes = new TdApi.PhotoSize[] {new TdApi.PhotoSize("x", file, 1280, 720, new int[0])};
            photo.caption = new TdApi.FormattedText(i == 0 ? "Замок у реки" : "", new TdApi.TextEntity[0]);
            TdApi.Message msg = debugMessage(2 + i, 3, t + 60, photo);
            msg.mediaAlbumId = 77;
            m.list.add(msg);
        }
        TdApi.MessageReactions reactions = new TdApi.MessageReactions();
        reactions.reactions = new TdApi.MessageReaction[] {
                new TdApi.MessageReaction(new TdApi.ReactionTypeEmoji("🔥"), 3, true, null, new TdApi.MessageSender[0]),
                new TdApi.MessageReaction(new TdApi.ReactionTypeEmoji("👍"), 1, false, null, new TdApi.MessageSender[0])};
        TdApi.MessageInteractionInfo info = new TdApi.MessageInteractionInfo();
        info.reactions = reactions;
        m.list.get(m.list.size() - 1).interactionInfo = info;
        m.list.stream().filter(x -> !bodyEmpty(x)).findFirst().ifPresent(x -> x.interactionInfo = info);
        TdApi.Message reply = debugMessage(10, 1, t + 120, debugText("Очень круто! Завтра доделаем башню"));
        TdApi.MessageReplyToMessage to = new TdApi.MessageReplyToMessage();
        to.chatId = chat.id;
        to.messageId = 1;
        reply.replyTo = to;
        reply.isOutgoing = true;
        m.list.add(reply);
        TdApi.Message forwarded = debugMessage(11, 2, t + 180, debugText("Сбор у спавна в 20:00"));
        forwarded.forwardInfo = new TdApi.MessageForwardInfo();
        forwarded.forwardInfo.origin = new TdApi.MessageOriginHiddenUser("Новости сервера");
        m.list.add(forwarded);
        TdApi.Message edited = debugMessage(12, 1, t + 240, debugText("Буду!"));
        edited.isOutgoing = true;
        edited.editDate = t + 250;
        m.list.add(edited);
        List<TdApi.Sticker> stickers = new ArrayList<>();
        for (int i = 0; i < Math.min(6, pictures.size()); i++) {
            stickers.add(new TdApi.Sticker(500 + i, 9, 512, 512, "😀", new TdApi.StickerFormatWebp(),
                    new TdApi.StickerFullTypeRegular(), null, debugFile(-100 - i, pictures.get(i))));
        }
        if (!stickers.isEmpty()) {
            TdApi.MessageSticker sticker = new TdApi.MessageSticker();
            sticker.sticker = stickers.get(0);
            m.list.add(debugMessage(13, 2, t + 260, sticker));
        }
        short[] tone = new short[16_000 * 3];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) (Math.sin(i / 7.0) * 9000 * Math.abs(Math.sin(i / 4000.0)));
        }
        TdApi.MessageVoiceNote voice = new TdApi.MessageVoiceNote();
        voice.voiceNote = new TdApi.VoiceNote(7, OggOpus.waveform(tone), "audio/ogg", null, new TdApi.File());
        voice.voiceNote.voice.id = -200;
        voice.caption = debugFormatted("");
        TdApi.Message voiceMessage = debugMessage(14, 1, t + 270, voice);
        voiceMessage.isOutgoing = true;
        m.list.add(voiceMessage);
        TdApi.Animation gif = null;
        if (video != null) {
            gif = new TdApi.Animation(3, 320, 320, "gif.mp4", "video/mp4", false, null, null, debugFile(-300, video));
            TdApi.MessageAnimation animation = new TdApi.MessageAnimation();
            animation.animation = gif;
            animation.caption = debugFormatted("");
            m.list.add(debugMessage(15, 3, t + 280, animation));
        }
        recentStickers = List.copyOf(stickers);
        savedAnimations = gif != null ? List.of(gif, gif, gif) : List.of();
        if (!stickers.isEmpty()) {
            TdApi.StickerSetInfo set = new TdApi.StickerSetInfo();
            set.id = 9;
            set.title = "Minecraft";
            set.covers = new TdApi.Sticker[] {stickers.get(0)};
            stickerSets = List.of(set);
            SET_STICKERS.put(9L, List.copyOf(stickers));
        }
        chat.lastMessage = m.list.get(m.list.size() - 1);
        CHATS.get(101L).lastMessage = m.list.get(0);
        panelLoadedAt = Long.MAX_VALUE / 2;
        return chat.id;
    }

    private static TdApi.File debugFile(int id, Path path) {
        TdApi.LocalFile local = new TdApi.LocalFile();
        local.path = path.toAbsolutePath().toString();
        local.isDownloadingCompleted = true;
        TdApi.File file = new TdApi.File();
        file.id = id;
        file.local = local;
        return file;
    }

    private static boolean bodyEmpty(TdApi.Message m) {
        return !(m.content instanceof TdApi.MessagePhoto p) || p.caption.text.isEmpty();
    }

    private static TdApi.User debugUser(long id, String name) {
        TdApi.User user = new TdApi.User();
        user.id = id;
        user.firstName = name;
        user.lastName = "";
        return user;
    }

    private static TdApi.FormattedText debugFormatted(String text) {
        return new TdApi.FormattedText(text, new TdApi.TextEntity[0]);
    }

    private static TdApi.MessageText debugText(String text) {
        TdApi.MessageText content = new TdApi.MessageText();
        content.text = debugFormatted(text);
        return content;
    }

    private static TdApi.Message debugMessage(long id, long sender, int date, TdApi.MessageContent content) {
        TdApi.Message msg = new TdApi.Message();
        msg.id = id;
        msg.chatId = 100;
        msg.senderId = new TdApi.MessageSenderUser(sender);
        msg.date = date;
        msg.content = content;
        return msg;
    }

    // --- Стикеры и GIF ---

    private static List<TdApi.StickerSetInfo> stickerSets = List.of();
    private static List<TdApi.Sticker> recentStickers = List.of();
    private static List<TdApi.Animation> savedAnimations = List.of();
    private static final Map<Long, List<TdApi.Sticker>> SET_STICKERS = new HashMap<>();
    private static final Set<Long> SET_LOADING = new HashSet<>();
    private static long panelLoadedAt;

    /** Загрузить (не чаще раза в минуту) наборы стикеров, недавние стикеры и сохранённые GIF — для панели в чате. */
    public static void loadStickerPanel() {
        if (System.currentTimeMillis() - panelLoadedAt < 60_000) {
            return;
        }
        panelLoadedAt = System.currentTimeMillis();
        send(new TdApi.GetInstalledStickerSets(new TdApi.StickerTypeRegular()), r -> {
            if (r instanceof TdApi.StickerSets sets) {
                stickerSets = List.of(sets.sets);
            }
        });
        send(new TdApi.GetRecentStickers(false), r -> {
            if (r instanceof TdApi.Stickers stickers) {
                recentStickers = List.of(stickers.stickers);
            }
        });
        send(new TdApi.GetSavedAnimations(), r -> {
            if (r instanceof TdApi.Animations animations) {
                savedAnimations = List.of(animations.animations);
            }
        });
    }

    public static List<TdApi.StickerSetInfo> stickerSets() {
        return stickerSets;
    }

    public static List<TdApi.Sticker> recentStickers() {
        return recentStickers;
    }

    public static List<TdApi.Animation> savedAnimations() {
        return savedAnimations;
    }

    /** Стикеры набора (пустой список, пока загружается). */
    public static List<TdApi.Sticker> stickerSet(long setId) {
        List<TdApi.Sticker> list = SET_STICKERS.get(setId);
        if (list == null && SET_LOADING.add(setId)) {
            send(new TdApi.GetStickerSet(setId), r -> {
                SET_LOADING.remove(setId);
                if (r instanceof TdApi.StickerSet set) {
                    SET_STICKERS.put(setId, List.of(set.stickers));
                }
            });
        }
        return list != null ? list : List.of();
    }

    /**
     * Картинка стикера, которую можно показать: сам файл, если он WEBP (обычный стикер), иначе обложка WEBP/JPEG/PNG
     * (анимированные TGS и видео-стикеры WEBM показываются обложкой).
     */
    public static @Nullable TdApi.File stickerImage(TdApi.Sticker sticker) {
        if (sticker.format instanceof TdApi.StickerFormatWebp) {
            return sticker.sticker;
        }
        TdApi.Thumbnail t = sticker.thumbnail;
        return t != null && (t.format instanceof TdApi.ThumbnailFormatWebp || t.format instanceof TdApi.ThumbnailFormatJpeg
                || t.format instanceof TdApi.ThumbnailFormatPng) ? t.file : null;
    }

    public static void sendSticker(long chatId, TdApi.Sticker sticker, long replyTo) {
        TdApi.InputSticker input = new TdApi.InputSticker(new TdApi.InputFileId(sticker.sticker.id), null, sticker.width, sticker.height);
        sendContent(chatId, new TdApi.InputMessageSticker(input, sticker.emoji), replyTo);
    }

    public static void sendAnimation(long chatId, TdApi.Animation animation, long replyTo) {
        TdApi.InputAnimation input = new TdApi.InputAnimation(new TdApi.InputFileId(animation.animation.id), null, new int[0],
                animation.duration, animation.width, animation.height);
        sendContent(chatId, new TdApi.InputMessageAnimation(input, new TdApi.FormattedText("", new TdApi.TextEntity[0]), false, false), replyTo);
    }

    /** Голосовое (OGG Opus): длительность в секундах и «волна» по 5 бит. */
    public static void sendVoice(long chatId, Path file, int durationSec, byte[] waveform, long replyTo) {
        TdApi.InputVoiceNote voice = new TdApi.InputVoiceNote(new TdApi.InputFileLocal(file.toAbsolutePath().toString()),
                durationSec, waveform);
        sendContent(chatId, new TdApi.InputMessageVoiceNote(voice, new TdApi.FormattedText("", new TdApi.TextEntity[0]), null), replyTo);
    }

    // --- Статусы, группы, уведомления, реакции ---

    private static final Map<Long, TdApi.Supergroup> SUPERGROUPS = new HashMap<>();
    private static final Map<Long, TdApi.BasicGroup> BASIC_GROUPS = new HashMap<>();
    private static final Map<Class<?>, TdApi.ScopeNotificationSettings> SCOPES = new HashMap<>();
    private static final Map<Long, Map<Long, Object[]>> TYPING = new HashMap<>();
    private static final Map<String, TdApi.Sticker> REACTION_ICONS = new HashMap<>();
    private static final Set<String> REACTION_LOADING = new HashSet<>();
    private static final Map<Long, TdApi.Object> FULL_INFO = new HashMap<>();
    private static final Set<Long> FULL_LOADING = new HashSet<>();

    /** Собеседник личного чата или null. */
    public static @Nullable TdApi.User peer(TdApi.Chat chat) {
        return chat.type instanceof TdApi.ChatTypePrivate p ? USERS.get(p.userId) : null;
    }

    public static @Nullable TdApi.Supergroup supergroup(TdApi.Chat chat) {
        return chat.type instanceof TdApi.ChatTypeSupergroup s ? SUPERGROUPS.get(s.supergroupId) : null;
    }

    /** Звук уведомлений чата выключен (свой выбор или по умолчанию для личных/групп/каналов) — как в самом Telegram. */
    public static boolean muted(TdApi.Chat chat) {
        TdApi.ChatNotificationSettings n = chat.notificationSettings;
        if (n != null && !n.useDefaultMuteFor) {
            return n.muteFor > 0;
        }
        Class<?> scope = chat.type instanceof TdApi.ChatTypePrivate || chat.type instanceof TdApi.ChatTypeSecret
                ? TdApi.NotificationSettingsScopePrivateChats.class
                : chat.type instanceof TdApi.ChatTypeSupergroup s && s.isChannel
                ? TdApi.NotificationSettingsScopeChannelChats.class : TdApi.NotificationSettingsScopeGroupChats.class;
        TdApi.ScopeNotificationSettings def = SCOPES.get(scope);
        return def != null && def.muteFor > 0;
    }

    /** Чат в основном списке (не только в архиве). */
    public static boolean inMain(TdApi.Chat chat) {
        return mainOrder(chat) != 0;
    }

    /** Кто печатает сейчас (только что) или null. */
    public static @Nullable String typing(long chatId) {
        Map<Long, Object[]> map = TYPING.get(chatId);
        if (map == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        map.values().removeIf(v -> (long) v[1] < now);
        for (Map.Entry<Long, Object[]> e : map.entrySet()) {
            String what = switch ((TdApi.ChatAction) e.getValue()[0]) {
                case TdApi.ChatActionRecordingVoiceNote a -> PhoneLang.tr("telegram.action.voice");
                case TdApi.ChatActionRecordingVideoNote a -> PhoneLang.tr("telegram.action.video_note");
                case TdApi.ChatActionUploadingPhoto a -> PhoneLang.tr("telegram.action.photo");
                case TdApi.ChatActionChoosingSticker a -> PhoneLang.tr("telegram.action.sticker");
                default -> PhoneLang.tr("telegram.action.typing");
            };
            TdApi.Chat chat = CHATS.get(chatId);
            TdApi.User u = USERS.get(e.getKey());
            if (chat != null && !(chat.type instanceof TdApi.ChatTypePrivate) && u != null) {
                return u.firstName + " " + what;
            }
            return what;
        }
        return null;
    }

    /** Строка под названием чата: «печатает…», «в сети», «был(а) в 12:30», «5 участников», «1,2K подписчиков». */
    public static String status(TdApi.Chat chat) {
        String typing = typing(chat.id);
        if (typing != null) {
            return typing;
        }
        TdApi.User user = peer(chat);
        if (user != null) {
            if (user.id == myId) {
                return "";
            }
            if (user.type instanceof TdApi.UserTypeBot) {
                return PhoneLang.tr("telegram.status.bot");
            }
            return userStatus(user);
        }
        int members = 0;
        boolean channel = false;
        TdApi.Supergroup sg = supergroup(chat);
        if (sg != null) {
            members = sg.memberCount;
            channel = sg.isChannel;
        } else if (chat.type instanceof TdApi.ChatTypeBasicGroup b && BASIC_GROUPS.get(b.basicGroupId) != null) {
            members = BASIC_GROUPS.get(b.basicGroupId).memberCount;
        }
        if (members <= 0) {
            return "";
        }
        return PhoneLang.tr(channel ? "telegram.status.subscribers" : "telegram.status.members", members);
    }

    public static String userStatus(TdApi.User user) {
        return switch (user.status) {
            case TdApi.UserStatusOnline o -> PhoneLang.tr("telegram.status.online");
            case TdApi.UserStatusOffline o -> {
                java.time.ZonedDateTime t = java.time.Instant.ofEpochSecond(o.wasOnline).atZone(java.time.ZoneId.systemDefault());
                boolean today = t.toLocalDate().equals(java.time.LocalDate.now());
                yield PhoneLang.tr(today ? "telegram.status.seen_at" : "telegram.status.seen_on",
                        t.format(java.time.format.DateTimeFormatter.ofPattern(today ? "HH:mm" : "dd.MM HH:mm")));
            }
            case TdApi.UserStatusRecently r -> PhoneLang.tr("telegram.status.recently");
            case TdApi.UserStatusLastWeek r -> PhoneLang.tr("telegram.status.week");
            case TdApi.UserStatusLastMonth r -> PhoneLang.tr("telegram.status.month");
            default -> PhoneLang.tr("telegram.status.long_ago");
        };
    }

    public static boolean online(TdApi.Chat chat) {
        TdApi.User user = peer(chat);
        return user != null && user.id != myId && user.status instanceof TdApi.UserStatusOnline;
    }

    /** Своё сообщение прочитано собеседником. */
    public static boolean read(TdApi.Message message) {
        TdApi.Chat chat = CHATS.get(message.chatId);
        return chat != null && message.isOutgoing && message.id <= chat.lastReadOutboxMessageId;
    }

    /** Значок реакции-эмодзи из самого Telegram (статичная картинка) или null, пока грузится / нет такой. */
    public static @Nullable TdApi.File reactionIcon(String emoji) {
        TdApi.Sticker icon = REACTION_ICONS.get(emoji);
        if (icon == null && client != null && REACTION_LOADING.add(emoji)) {
            send(new TdApi.GetEmojiReaction(emoji), r -> {
                if (r instanceof TdApi.EmojiReaction er && er.staticIcon != null) {
                    REACTION_ICONS.put(emoji, er.staticIcon);
                }
            });
        }
        return icon != null ? stickerImage(icon) : null;
    }

    /** Реакции, доступные для сообщения (как в меню Telegram); onReady — список эмодзи. */
    public static void availableReactions(long chatId, long messageId, Consumer<List<String>> onReady) {
        send(new TdApi.GetMessageAvailableReactions(chatId, messageId, 8), r -> {
            List<String> list = new ArrayList<>();
            if (r instanceof TdApi.AvailableReactions ar) {
                for (TdApi.AvailableReaction[] group : new TdApi.AvailableReaction[][]{ar.topReactions, ar.popularReactions}) {
                    for (TdApi.AvailableReaction a : group) {
                        if (a.type instanceof TdApi.ReactionTypeEmoji e && !a.needsPremium && !list.contains(e.emoji)) {
                            list.add(e.emoji);
                        }
                    }
                }
            }
            onReady.accept(list);
        });
    }

    /** Полные сведения о собеседнике или группе (о себе / описание): UserFullInfo, SupergroupFullInfo или BasicGroupFullInfo. */
    public static @Nullable TdApi.Object fullInfo(TdApi.Chat chat) {
        TdApi.Object cached = FULL_INFO.get(chat.id);
        if (cached == null && client != null && FULL_LOADING.add(chat.id)) {
            TdApi.Function<?> request = switch (chat.type) {
                case TdApi.ChatTypePrivate p -> new TdApi.GetUserFullInfo(p.userId);
                case TdApi.ChatTypeSupergroup s -> new TdApi.GetSupergroupFullInfo(s.supergroupId);
                case TdApi.ChatTypeBasicGroup b -> new TdApi.GetBasicGroupFullInfo(b.basicGroupId);
                default -> null;
            };
            if (request != null) {
                send(request, r -> {
                    if (!(r instanceof TdApi.Error)) {
                        FULL_INFO.put(chat.id, r);
                    }
                });
            }
        }
        return cached;
    }

    /** Включить/выключить уведомления чата (как «Без звука» в Telegram). */
    public static void setMuted(TdApi.Chat chat, boolean mute) {
        TdApi.ChatNotificationSettings n = chat.notificationSettings != null ? chat.notificationSettings : new TdApi.ChatNotificationSettings();
        n.useDefaultMuteFor = false;
        n.muteFor = mute ? Integer.MAX_VALUE : 0;
        send(new TdApi.SetChatNotificationSettings(chat.id, n), null);
    }

    /** Сколько файла скачано (0..1). */
    public static float progress(@Nullable TdApi.File file) {
        if (file == null) {
            return 0;
        }
        TdApi.File current = FILES.getOrDefault(file.id, file);
        long size = current.size > 0 ? current.size : current.expectedSize;
        return size > 0 && current.local != null ? Math.min(1, current.local.downloadedSize / (float) size) : 0;
    }

    /** Кто автор пересланного сообщения. */
    public static String originName(TdApi.MessageOrigin origin) {
        return switch (origin) {
            case TdApi.MessageOriginUser u -> {
                TdApi.User user = USERS.get(u.senderUserId);
                yield user != null ? (user.firstName + " " + user.lastName).strip() : "?";
            }
            case TdApi.MessageOriginHiddenUser h -> h.senderName;
            case TdApi.MessageOriginChat c -> {
                TdApi.Chat chat = CHATS.get(c.senderChatId);
                yield chat != null ? chat.title : "?";
            }
            case TdApi.MessageOriginChannel c -> {
                TdApi.Chat chat = CHATS.get(c.chatId);
                yield chat != null ? chat.title : "?";
            }
            default -> "?";
        };
    }

    private static @Nullable TdApi.InputMessageReplyTo replyTo(long messageId) {
        return messageId > 0 ? new TdApi.InputMessageReplyToMessage(messageId, null, 0, null) : null;
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
        sendContent(chatId, content, 0);
    }

    private static void sendContent(long chatId, TdApi.InputMessageContent content, long replyTo) {
        TdApi.SendMessage send = new TdApi.SendMessage();
        send.chatId = chatId;
        send.replyTo = replyTo(replyTo);
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
        return text(message.content);
    }

    public static String text(@Nullable TdApi.MessageContent content) {
        if (content == null) {
            return "";
        }
        return switch (content) {
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
