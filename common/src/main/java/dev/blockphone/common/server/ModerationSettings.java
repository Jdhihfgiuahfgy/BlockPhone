package dev.blockphone.common.server;

/**
 * Настройки модерации (поля открыты — для Gson; Paper заполняет из config.yml, раздел {@code moderation}).
 * Лог личных сообщений в чат игры и команды сервера при бане.
 */
public final class ModerationSettings {
    public ChatLog chatLog = new ChatLog();
    /**
     * Команда консоли при бане в телефоне (пусто — только бан в телефоне). Подстановки: {player}, {uuid}, {reason},
     * {minutes} (-1 — навсегда), {moderator}. Например: "tempban {player} {minutes}m {reason}".
     */
    public String banCommand = "";
    /** Команда консоли при снятии бана (пусто — ничего). Например: "pardon {player}". */
    public String unbanCommand = "";

    /**
     * Дублировать сообщения мессенджера в чат игры тем, у кого право {@code blockphone.chatlog} (по умолчанию —
     * операторы). Цвета — коды &amp;0..&amp;f, &amp;l, &amp;o…; подстановки: {sender}, {receiver}, {group}, {message}.
     */
    public static final class ChatLog {
        public boolean enabled = false;
        /** Сообщения групп тоже (иначе только личные). */
        public boolean groups = true;
        /** Писать и в консоль сервера. */
        public boolean console = false;
        public String format = "&8[ЛС] {sender} > {receiver}: {message}";
        public String groupFormat = "&8[{group}] {sender}: {message}";
        /** Как показывать вложения в {message}. */
        public String photo = "[фото]";
        public String voice = "[голосовое]";
        public String sticker = "[стикер]";
    }

    public ModerationSettings fixNulls() {
        if (chatLog == null) {
            chatLog = new ChatLog();
        }
        ChatLog d = new ChatLog();
        chatLog.format = chatLog.format == null ? d.format : chatLog.format;
        chatLog.groupFormat = chatLog.groupFormat == null ? d.groupFormat : chatLog.groupFormat;
        chatLog.photo = chatLog.photo == null ? d.photo : chatLog.photo;
        chatLog.voice = chatLog.voice == null ? d.voice : chatLog.voice;
        chatLog.sticker = chatLog.sticker == null ? d.sticker : chatLog.sticker;
        banCommand = banCommand == null ? "" : banCommand;
        unbanCommand = unbanCommand == null ? "" : unbanCommand;
        return this;
    }

    /** Коды &amp;x → §x (цвета чата Minecraft); «&amp;&amp;» — сам символ &amp;. */
    public static String colors(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '&' && i + 1 < text.length()) {
                char next = Character.toLowerCase(text.charAt(i + 1));
                if (next == '&') {
                    sb.append('&');
                    i++;
                    continue;
                }
                if ("0123456789abcdefklmnor".indexOf(next) >= 0) {
                    sb.append('§').append(next);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
