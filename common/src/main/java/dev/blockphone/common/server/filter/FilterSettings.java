package dev.blockphone.common.server.filter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Настройки автомодерации (поля открыты — так их читает Gson на Fabric/Velocity/BungeeCord; Paper заполняет из config.yml).
 * Подробное описание — в config.yml Paper и в CLAUDE.md.
 */
public final class FilterSettings {
    /** Включить автомодерацию. */
    public boolean enabled = true;
    /** Игроки с правом blockphone.filter.bypass (по умолчанию — операторы) не проверяются. */
    public boolean bypassForModerators = true;
    /** Писать нарушения в лог сервера. */
    public boolean log = true;
    /** Где проверять текст. */
    public ApplyTo applyTo = new ApplyTo();
    public Words words = new Words();
    public Links links = new Links();
    public AutoMute autoMute = new AutoMute();
    /**
     * Дополнительные списки слов, каждый — со своими местами и действием. Например, маты — только в сообщениях,
     * реклама серверов — в постах и названиях пабликов.
     */
    public List<Rule> rules = new ArrayList<>();

    /**
     * Места проверки: messages (личные сообщения), group_messages (сообщения в группах), group_names (названия групп и
     * наборов стикеров), posts, comments, profiles («о себе»), publics (название и описание паблика); «all» — везде.
     */
    public static final List<String> PLACES = List.of("messages", "group_messages", "group_names", "posts", "comments", "profiles",
            "publics");

    public static final class Rule {
        /** Имя для лога. */
        public String name = "";
        public List<String> words = new ArrayList<>();
        public List<String> where = new ArrayList<>(List.of("all"));
        public String action = "censor";
    }

    public static final class ApplyTo {
        public boolean messages = true;
        public boolean groupNames = true;
        public boolean posts = true;
        public boolean comments = true;
        public boolean profiles = true;
        public boolean publics = true;
    }

    /**
     * Слова. Запись списка: «слово» — целое слово (или фраза из нескольких слов); «слово*» — слова, начинающиеся так;
     * «*слово» — заканчивающиеся; «*слово*» — где угодно внутри; «regex:…» — регулярное выражение (без учёта регистра).
     * Сравнение не зависит от регистра и обходов: похожие латинские/русские буквы, цифры вместо букв (0→o, 3→e…),
     * повторы букв («плоооохо»), буквы через точки/пробелы («п.л.о.х.о»).
     */
    public static final class Words {
        public List<String> blacklist = new ArrayList<>();
        /** Слова, которые никогда не считаются нарушением (например, «класс», если в чёрном списке «*ас*»). */
        public List<String> whitelist = new ArrayList<>();
        /** Где действует общий чёрный список (см. {@link #PLACES}). */
        public List<String> where = new ArrayList<>(List.of("all"));
        /** censor — заменить звёздочками; block — не отправлять (игрок видит причину). */
        public String action = "censor";
    }

    /**
     * Ссылки. Порядок решения для каждой найденной ссылки: белый список доменов → разрешена; чёрный список доменов → нарушение;
     * формат в чёрном списке форматов → нарушение; формат в белом списке форматов → разрешена; иначе — по {@link #mode}.
     * Домен в списке подходит и для поддоменов («example.com» = «www.example.com», «play.example.com»); с «/» — начало адреса
     * («youtube.com/watch»).
     * Форматы: url (со схемой «что-то://»), scheme:&lt;имя&gt; (scheme:https, scheme:ftp…), www, domain (просто «сайт.ru»),
     * ip (IPv4, с портом или без), email, invite (приглашения Discord/Telegram/WhatsApp/VK), obfuscated (замаскированные:
     * «site[.]ru», «site (dot) ru», «site точка ru», «site . ru») и свои форматы из {@link #customFormats}.
     */
    public static final class Links {
        /** allow — разрешены все, кроме запрещённых списками; block — запрещены все, кроме разрешённых списками. */
        public String mode = "allow";
        public List<String> domainWhitelist = new ArrayList<>();
        public List<String> domainBlacklist = new ArrayList<>();
        public List<String> formatWhitelist = new ArrayList<>();
        public List<String> formatBlacklist = new ArrayList<>(List.of("ip", "obfuscated"));
        /** Свои форматы: имя → регулярное выражение (без учёта регистра). Имя можно указывать в списках форматов. */
        public Map<String, String> customFormats = new LinkedHashMap<>();
        /** censor — заменить ссылку звёздочками; block — не отправлять. */
        public String action = "block";
        /** Где проверяются ссылки (см. {@link #PLACES}). */
        public List<String> where = new ArrayList<>(List.of("all"));
    }

    /** Автомьют: столько нарушений за окно → игрок не может писать столько минут (0 — выключено). */
    public static final class AutoMute {
        public int violations = 5;
        public int windowMinutes = 10;
        public int muteMinutes = 15;
    }

    /** Заполнить пропуски (поле отсутствует в старом файле настроек → null после Gson). */
    public FilterSettings fixNulls() {
        if (applyTo == null) {
            applyTo = new ApplyTo();
        }
        if (words == null) {
            words = new Words();
        }
        if (links == null) {
            links = new Links();
        }
        if (autoMute == null) {
            autoMute = new AutoMute();
        }
        words.blacklist = words.blacklist == null ? new ArrayList<>() : words.blacklist;
        words.whitelist = words.whitelist == null ? new ArrayList<>() : words.whitelist;
        words.action = words.action == null ? "censor" : words.action;
        links.mode = links.mode == null ? "allow" : links.mode;
        links.domainWhitelist = links.domainWhitelist == null ? new ArrayList<>() : links.domainWhitelist;
        links.domainBlacklist = links.domainBlacklist == null ? new ArrayList<>() : links.domainBlacklist;
        links.formatWhitelist = links.formatWhitelist == null ? new ArrayList<>() : links.formatWhitelist;
        links.formatBlacklist = links.formatBlacklist == null ? new ArrayList<>() : links.formatBlacklist;
        links.customFormats = links.customFormats == null ? new LinkedHashMap<>() : links.customFormats;
        links.action = links.action == null ? "block" : links.action;
        words.where = words.where == null ? new ArrayList<>(List.of("all")) : words.where;
        links.where = links.where == null ? new ArrayList<>(List.of("all")) : links.where;
        rules = rules == null ? new ArrayList<>() : rules;
        for (Rule r : rules) {
            r.name = r.name == null ? "" : r.name;
            r.words = r.words == null ? new ArrayList<>() : r.words;
            r.where = r.where == null ? new ArrayList<>(List.of("all")) : r.where;
            r.action = r.action == null ? "censor" : r.action;
        }
        return this;
    }
}
