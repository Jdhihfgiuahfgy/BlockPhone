package dev.blockphone.common.server;

/**
 * Настройки модерации (поля открыты — для Gson; Paper заполняет из config.yml, раздел {@code moderation}).
 * Лог личных сообщений в чат игры и команды сервера при бане.
 */
public final class ModerationSettings {
    public ChatLog chatLog = new ChatLog();
    /** Объявления о наказаниях из панели модерации в чат игры (как у плагинов на баны). */
    public Announce announce = new Announce();
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

    /**
     * Объявления о наказаниях в чат игры. Подстановки: {player} — кого, {moderator} — кто, {duration} — срок,
     * {reason} — « (причина)» или пусто. Цвета — &-коды.
     */
    public static final class Announce {
        public boolean enabled = true;
        /** all — всем игрокам; staff — только модераторам (у кого есть модераторские права). */
        public String to = "all";
        /** Наказанному — отдельное сообщение в чат (даже если объявления выключены). */
        public boolean notifyPlayer = true;
        public String mute = "&c[Телефон] &f{moderator} &7выдал мут &f{player} &7на {duration}{reason}";
        public String unmute = "&a[Телефон] &f{moderator} &7снял мут с &f{player}{reason}";
        public String ban = "&4[Телефон] &f{moderator} &7заблокировал телефон &f{player} &7на {duration}{reason}";
        public String unban = "&a[Телефон] &f{moderator} &7разблокировал телефон &f{player}{reason}";
        public String warn = "&e[Телефон] &f{moderator} &7выдал предупреждение &f{player}{reason}";
        public String playerMute = "&cВам выдан мут в телефоне на {duration}{reason}";
        public String playerBan = "&4Ваш телефон заблокирован на этом сервере на {duration}{reason}";
        public String playerWarn = "&eВам выдано предупреждение{reason}";
        /** Слова для {duration}. */
        public String forever = "навсегда";
        public String minutes = "мин";
        public String hours = "ч";
        public String days = "д";
    }

    public ModerationSettings fixNulls() {
        if (chatLog == null) {
            chatLog = new ChatLog();
        }
        Announce a = new Announce();
        if (announce == null) {
            announce = a;
        }
        announce.to = announce.to == null ? a.to : announce.to;
        announce.mute = announce.mute == null ? a.mute : announce.mute;
        announce.unmute = announce.unmute == null ? a.unmute : announce.unmute;
        announce.ban = announce.ban == null ? a.ban : announce.ban;
        announce.unban = announce.unban == null ? a.unban : announce.unban;
        announce.warn = announce.warn == null ? a.warn : announce.warn;
        announce.playerMute = announce.playerMute == null ? a.playerMute : announce.playerMute;
        announce.playerBan = announce.playerBan == null ? a.playerBan : announce.playerBan;
        announce.playerWarn = announce.playerWarn == null ? a.playerWarn : announce.playerWarn;
        announce.forever = announce.forever == null ? a.forever : announce.forever;
        announce.minutes = announce.minutes == null ? a.minutes : announce.minutes;
        announce.hours = announce.hours == null ? a.hours : announce.hours;
        announce.days = announce.days == null ? a.days : announce.days;
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
