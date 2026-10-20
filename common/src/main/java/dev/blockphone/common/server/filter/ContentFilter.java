package dev.blockphone.common.server.filter;



import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Автомодерация текста: слова (чёрный/белый список, устойчиво к обходам) и ссылки (домены, форматы, свои форматы).
 * Без Minecraft и без базы — чистая логика; права, логи и мьюты — в {@code Backend}.
 */
public final class ContentFilter {
    /** Где написан текст. */
    public enum Context { MESSAGE, GROUP_NAME, POST, COMMENT, BIO, PUBLIC }

    /**
     * Итог проверки.
     *
     * @param text    текст для сохранения (с звёздочками, если что-то заменено)
     * @param blocked не сохранять: игроку — ошибка {@code reason}
     * @param reason  «filter_words» или «filter_links» (ключ ошибки без префикса), null — нарушений нет
     * @param found   что нашлось (для лога)
     */
    public record Verdict(String text, boolean blocked, String reason, List<String> found) {
        public boolean violation() {
            return reason != null;
        }
    }

    /** Найденная ссылка: где в тексте, адрес (хост + путь, может быть null у своих форматов) и её форматы. */
    record Link(int start, int end, String host, String path, Set<String> formats) {
    }

    private record Range(int start, int end) {
        boolean inside(List<Range> outer) {
            for (Range r : outer) {
                if (r.start <= start && end <= r.end) {
                    return true;
                }
            }
            return false;
        }

        boolean overlaps(int s, int e) {
            return start < e && s < end;
        }
    }

    /** Запись списка слов: либо регулярное выражение по исходному тексту, либо выражение по «скелету». */
    private record WordRule(String source, Pattern pattern, boolean regex) {
    }

    private final FilterSettings settings;
    private final List<WordRule> blacklist;
    private final List<WordRule> whitelist;
    private final Map<String, Pattern> customFormats = new java.util.LinkedHashMap<>();
    private final Map<UUID, List<Long>> violations = new ConcurrentHashMap<>();
    private final Map<UUID, Long> mutedUntil = new ConcurrentHashMap<>();

    public ContentFilter(FilterSettings settings) {
        this.settings = settings.fixNulls();
        this.blacklist = rules(settings.words.blacklist);
        this.whitelist = rules(settings.words.whitelist);
        settings.links.customFormats.forEach((name, regex) -> {
            try {
                customFormats.put(name.toLowerCase(Locale.ROOT), Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (RuntimeException ignored) {
                // Плохое выражение в настройках — пропускаем (сообщение пишет Backend при запуске).
            }
        });
    }

    public FilterSettings settings() {
        return settings;
    }

    /** Проверяется ли этот вид текста. */
    public boolean applies(Context context) {
        FilterSettings.ApplyTo a = settings.applyTo;
        return settings.enabled && switch (context) {
            case MESSAGE -> a.messages;
            case GROUP_NAME -> a.groupNames;
            case POST -> a.posts;
            case COMMENT -> a.comments;
            case BIO -> a.profiles;
            case PUBLIC -> a.publics;
        };
    }

    /** Записи списков, которые не удалось разобрать (для предупреждения в логе). */
    public List<String> badEntries() {
        List<String> bad = new ArrayList<>();
        for (String entry : settings.words.blacklist) {
            if (rule(entry) == null && !entry.isBlank()) {
                bad.add(entry);
            }
        }
        for (Map.Entry<String, String> e : settings.links.customFormats.entrySet()) {
            if (!customFormats.containsKey(e.getKey().toLowerCase(Locale.ROOT))) {
                bad.add(e.getKey() + ": " + e.getValue());
            }
        }
        return bad;
    }

    public Verdict check(String text) {
        List<Range> wordHits = new ArrayList<>();
        List<String> found = new ArrayList<>();
        if (!blacklist.isEmpty()) {
            Skeleton sk = Skeleton.of(text);
            List<Range> allowed = new ArrayList<>();
            for (WordRule rule : whitelist) {
                allowed.addAll(matches(rule, text, sk));
            }
            for (WordRule rule : blacklist) {
                for (Range r : matches(rule, text, sk)) {
                    if (!r.inside(allowed)) {
                        wordHits.add(r);
                        found.add("word " + text.substring(r.start, r.end));
                    }
                }
            }
        }
        List<Range> linkHits = new ArrayList<>();
        for (Link link : findLinks(text)) {
            if (!allowed(link)) {
                linkHits.add(new Range(link.start, link.end));
                found.add("link " + text.substring(link.start, link.end) + " " + link.formats);
            }
        }
        if (wordHits.isEmpty() && linkHits.isEmpty()) {
            return new Verdict(text, false, null, List.of());
        }
        String reason = !wordHits.isEmpty() ? "filter_words" : "filter_links";
        boolean block = (!wordHits.isEmpty() && "block".equalsIgnoreCase(settings.words.action))
                || (!linkHits.isEmpty() && "block".equalsIgnoreCase(settings.links.action));
        if (block) {
            return new Verdict(text, true, !wordHits.isEmpty() && "block".equalsIgnoreCase(settings.words.action)
                    ? "filter_words" : "filter_links", found);
        }
        char[] chars = text.toCharArray();
        for (List<Range> hits : List.of(wordHits, linkHits)) {
            for (Range r : hits) {
                for (int i = r.start; i < r.end; i++) {
                    if (!Character.isWhitespace(chars[i])) {
                        chars[i] = '*';
                    }
                }
            }
        }
        return new Verdict(new String(chars), false, reason, found);
    }

    // --- Нарушения и автомьют ---

    /** Учесть нарушение; true — пора выдать автомьют (счётчик сбрасывается). */
    public boolean recordViolation(UUID player, long now) {
        FilterSettings.AutoMute m = settings.autoMute;
        if (m.violations <= 0 || m.muteMinutes <= 0) {
            return false;
        }
        List<Long> list = violations.computeIfAbsent(player, k -> new ArrayList<>());
        synchronized (list) {
            long window = Math.max(1, m.windowMinutes) * 60_000L;
            list.removeIf(t -> t < now - window);
            list.add(now);
            if (list.size() >= m.violations) {
                list.clear();
                mutedUntil.put(player, now + m.muteMinutes * 60_000L);
                return true;
            }
        }
        return false;
    }

    /** До какого времени игрок не может писать из-за автомьюта (0 — может). */
    public long mutedUntil(UUID player, long now) {
        Long until = mutedUntil.get(player);
        if (until == null || until <= now) {
            mutedUntil.remove(player);
            return 0;
        }
        return until;
    }

    // --- Слова ---

    private static List<WordRule> rules(List<String> entries) {
        List<WordRule> result = new ArrayList<>();
        for (String entry : entries) {
            WordRule rule = rule(entry);
            if (rule != null) {
                result.add(rule);
            }
        }
        return result;
    }

    private static WordRule rule(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        entry = entry.strip();
        try {
            if (entry.regionMatches(true, 0, "regex:", 0, 6)) {
                return new WordRule(entry, Pattern.compile(entry.substring(6), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), true);
            }
        } catch (RuntimeException e) {
            return null;
        }
        boolean anyStart = entry.startsWith("*");
        boolean anyEnd = entry.endsWith("*") && entry.length() > 1;
        String core = entry.substring(anyStart ? 1 : 0, entry.length() - (anyEnd ? 1 : 0));
        String skeleton = Skeleton.of(core).joined.strip();
        if (skeleton.isEmpty()) {
            return null;
        }
        String regex = (anyStart ? "[^ ]*" : "(?<= )") + Pattern.quote(skeleton) + (anyEnd ? "[^ ]*" : "(?= )");
        return new WordRule(entry, Pattern.compile(regex), false);
    }

    private static List<Range> matches(WordRule rule, String text, Skeleton sk) {
        List<Range> result = new ArrayList<>();
        Matcher m = rule.pattern.matcher(rule.regex ? text : sk.joined);
        while (m.find()) {
            if (m.end() <= m.start()) {
                continue;
            }
            if (rule.regex) {
                result.add(new Range(m.start(), m.end()));
            } else {
                int s = m.start();
                int e = m.end() - 1;
                while (s <= e && sk.from[s] < 0) {
                    s++;
                }
                while (e >= s && sk.to[e] < 0) {
                    e--;
                }
                if (s <= e) {
                    result.add(new Range(sk.from[s], sk.to[e]));
                }
            }
        }
        return result;
    }

    /**
     * «Скелет» текста для сравнения слов: строчные, похожие буквы и цифры сведены к одной, повторы букв схлопнуты,
     * буквы через разделители («п.л.о.х.о») склеены; слова разделены одним пробелом, по краям — пробел.
     * {@code from/to} — для каждого символа скелета начало и конец (не включая) в исходном тексте; у пробелов −1.
     */
    static final class Skeleton {
        final String joined;
        final int[] from;
        final int[] to;

        private Skeleton(String joined, int[] from, int[] to) {
            this.joined = joined;
            this.from = from;
            this.to = to;
        }

        private record Ch(char c, int from, int to) {
        }

        static Skeleton of(String text) {
            List<List<Ch>> tokens = new ArrayList<>();
            List<Ch> current = new ArrayList<>();
            for (int i = 0; i < text.length(); i++) {
                char c = canonical(Character.toLowerCase(text.charAt(i)));
                if (c == 0) {
                    if (!current.isEmpty()) {
                        tokens.add(current);
                        current = new ArrayList<>();
                    }
                    continue;
                }
                if (!current.isEmpty() && current.get(current.size() - 1).c == c) {
                    Ch last = current.remove(current.size() - 1);
                    current.add(new Ch(c, last.from, i + 1)); // повтор буквы
                } else {
                    current.add(new Ch(c, i, i + 1));
                }
            }
            if (!current.isEmpty()) {
                tokens.add(current);
            }
            // Склеить подряд идущие однобуквенные «слова» (3+ штук): «п.л.о.х.о», «п л о х о».
            List<List<Ch>> merged = new ArrayList<>();
            for (int i = 0; i < tokens.size(); ) {
                int j = i;
                while (j < tokens.size() && tokens.get(j).size() == 1) {
                    j++;
                }
                if (j - i >= 3) {
                    List<Ch> glued = new ArrayList<>();
                    for (int k = i; k < j; k++) {
                        Ch ch = tokens.get(k).get(0);
                        if (!glued.isEmpty() && glued.get(glued.size() - 1).c == ch.c) {
                            Ch last = glued.remove(glued.size() - 1);
                            glued.add(new Ch(ch.c, last.from, ch.to));
                        } else {
                            glued.add(ch);
                        }
                    }
                    merged.add(glued);
                    i = j;
                } else {
                    merged.add(tokens.get(i));
                    i++;
                }
            }
            StringBuilder sb = new StringBuilder(" ");
            List<Integer> from = new ArrayList<>(List.of(-1));
            List<Integer> to = new ArrayList<>(List.of(-1));
            for (List<Ch> token : merged) {
                for (Ch ch : token) {
                    sb.append(ch.c);
                    from.add(ch.from);
                    to.add(ch.to);
                }
                sb.append(' ');
                from.add(-1);
                to.add(-1);
            }
            return new Skeleton(sb.toString(), from.stream().mapToInt(Integer::intValue).toArray(),
                    to.stream().mapToInt(Integer::intValue).toArray());
        }

        /** Одна буква на группу похожих; 0 — разделитель. */
        private static char canonical(char c) {
            return switch (c) {
                case 'а', '@', 'α' -> 'a';
                case 'в', 'ß' -> 'b';
                case 'е', 'ё', 'є', 'ε' -> 'e';
                case 'к', 'κ' -> 'k';
                case 'м' -> 'm';
                case 'н' -> 'h';
                case 'о', '0', 'ο' -> 'o';
                case 'р', 'ρ' -> 'p';
                case 'с' -> 'c';
                case 'т', '7' -> 't';
                case 'у' -> 'y';
                case 'х' -> 'x';
                case 'і', 'ї', '1' -> 'i';
                case '$', 'ѕ', '5' -> 's';
                case 'з', '3' -> '3';
                case 'б', '6' -> '6';
                case 'ч', '4' -> '4';
                case 'п' -> 'n';
                case 'и' -> 'u';
                case 'г' -> 'r';
                case 'ъ', 'ь' -> 'ь';
                default -> Character.isLetterOrDigit(c) ? c : 0;
            };
        }
    }

    // --- Ссылки ---

    private static final Set<String> TLDS = Set.of("com", "net", "org", "info", "biz", "io", "gg", "me", "tv", "co", "xyz", "app",
            "dev", "site", "online", "store", "shop", "pro", "club", "fun", "top", "live", "space", "tech", "link", "click", "world",
            "one", "cc", "ws", "ly", "to", "pw", "su", "ru", "ua", "by", "kz", "eu", "us", "uk", "de", "gov", "edu", "mobi", "name",
            "host", "website", "network", "games", "game", "server", "art", "blog", "page", "today", "icu", "vip", "win", "bet",
            "рф", "рус", "бел", "укр", "срб", "қаз", "онлайн", "сайт", "орг", "ком", "москва", "дети");
    private static final List<String> INVITES = List.of("discord.gg/", "discord.com/invite", "discordapp.com/invite", "t.me/",
            "telegram.me/", "telegram.dog/", "chat.whatsapp.com/", "vk.me/join", "vk.cc/");
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern OBFUSCATED_DOT = Pattern.compile(
            "\\s*[\\[({]\\s*(?:\\.|dot|точка)\\s*[\\])}]\\s*|\\s+(?:dot|точка)\\s+|\\s+\\.\\s+", FLAGS);
    private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+-]+@((?:[\\p{L}\\p{N}-]+\\.)+[\\p{L}]{2,24})", FLAGS);
    private static final Pattern URL = Pattern.compile("\\b([a-z][a-z0-9+.\\-]{1,15})://([^\\s/?#]+)([^\\s]*)", FLAGS);
    private static final Pattern IP = Pattern.compile(
            "(?<![\\d.])((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})(?::\\d{1,5})?(?![\\d]|\\.\\d)(/[^\\s]*)?");
    private static final Pattern DOMAIN = Pattern.compile(
            "(?<![\\p{L}\\p{N}@.\\-/])((?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?\\.)+(\\p{L}{2,24}))(?::\\d{1,5})?(/[^\\s]*)?",
            FLAGS);

    /** Все ссылки в тексте (в координатах исходного текста). */
    List<Link> findLinks(String text) {
        // Снимаем маскировку точек, помня, откуда каждый символ.
        StringBuilder plain = new StringBuilder();
        List<Integer> from = new ArrayList<>();
        List<Integer> to = new ArrayList<>();
        List<Boolean> masked = new ArrayList<>();
        Matcher ob = OBFUSCATED_DOT.matcher(text);
        int pos = 0;
        while (ob.find()) {
            // «слово . слово» засчитываем, только если слева и справа буквы/цифры (а не конец предложения).
            if (ob.start() == 0 || ob.end() >= text.length() || !Character.isLetterOrDigit(text.charAt(ob.start() - 1))
                    || !Character.isLetterOrDigit(text.charAt(ob.end()))) {
                continue;
            }
            for (int i = pos; i < ob.start(); i++) {
                plain.append(text.charAt(i));
                from.add(i);
                to.add(i + 1);
                masked.add(false);
            }
            plain.append('.');
            from.add(ob.start());
            to.add(ob.end());
            masked.add(true);
            pos = ob.end();
        }
        for (int i = pos; i < text.length(); i++) {
            plain.append(text.charAt(i));
            from.add(i);
            to.add(i + 1);
            masked.add(false);
        }
        String s = plain.toString();
        List<Link> links = new ArrayList<>();
        List<Range> taken = new ArrayList<>();

        Matcher m = EMAIL.matcher(s);
        while (m.find()) {
            add(s, links, taken, m.start(), m.end(), m.group(1), "", masked, from, to, "email");
        }
        m = URL.matcher(s);
        while (m.find()) {
            String host = m.group(2);
            host = host.substring(host.indexOf('@') + 1);
            int colon = host.lastIndexOf(':');
            if (colon > 0 && !host.endsWith("]")) {
                host = host.substring(0, colon);
            }
            String scheme = m.group(1).toLowerCase(Locale.ROOT);
            add(s, links, taken, m.start(), m.end(), host, m.group(3), masked, from, to, "url", "scheme:" + scheme,
                    IP.matcher(host).matches() ? "ip" : host.toLowerCase(Locale.ROOT).startsWith("www.") ? "www" : null);
        }
        m = IP.matcher(s);
        while (m.find()) {
            add(s, links, taken, m.start(), m.end(), m.group(1), m.group(2) == null ? "" : m.group(2), masked, from, to, "ip");
        }
        m = DOMAIN.matcher(s);
        while (m.find()) {
            String tld = m.group(2).toLowerCase(Locale.ROOT);
            boolean latinCountry = tld.length() == 2 && tld.chars().allMatch(c -> c >= 'a' && c <= 'z');
            if (!TLDS.contains(tld) && !latinCountry) {
                continue;
            }
            String host = m.group(1);
            add(s, links, taken, m.start(), m.end(), host, m.group(3) == null ? "" : m.group(3), masked, from, to,
                    host.toLowerCase(Locale.ROOT).startsWith("www.") ? "www" : "domain");
        }
        // Свои форматы — по исходному тексту; совпадение внутри найденной ссылки добавляет ей формат.
        for (Map.Entry<String, Pattern> format : customFormats.entrySet()) {
            Matcher cm = format.getValue().matcher(text);
            while (cm.find()) {
                if (cm.end() <= cm.start()) {
                    continue;
                }
                boolean merged = false;
                for (Link link : links) {
                    if (new Range(link.start, link.end).overlaps(cm.start(), cm.end())) {
                        link.formats.add(format.getKey());
                        merged = true;
                    }
                }
                if (!merged) {
                    links.add(new Link(cm.start(), cm.end(), null, "", new LinkedHashSet<>(Set.of(format.getKey()))));
                }
            }
        }
        return links;
    }

    private static void add(String s, List<Link> links, List<Range> taken, int start, int end, String host, String path, List<Boolean> masked,
                            List<Integer> from, List<Integer> to, String... formats) {
        for (Range r : taken) {
            if (r.overlaps(start, end)) {
                return;
            }
        }
        taken.add(new Range(start, end));
        // Точка/запятая/скобка в конце — скорее знак препинания, чем часть адреса.
        while (end > start && ".,;:!?)]}\"'»".indexOf(s.charAt(end - 1)) >= 0 && path.length() > 0) {
            end--;
            path = path.substring(0, path.length() - 1);
        }
        Set<String> set = new LinkedHashSet<>();
        for (String f : formats) {
            if (f != null) {
                set.add(f);
            }
        }
        boolean hidden = false;
        for (int i = start; i < end; i++) {
            hidden |= masked.get(i);
        }
        if (hidden) {
            set.add("obfuscated");
        }
        String address = (host + path).toLowerCase(Locale.ROOT);
        for (String invite : INVITES) {
            if (address.startsWith(invite) || address.startsWith("www." + invite)) {
                set.add("invite");
            }
        }
        links.add(new Link(from.get(start), to.get(end - 1), host.toLowerCase(Locale.ROOT), path, set));
    }

    /** Решение по ссылке (см. {@link FilterSettings.Links}). */
    boolean allowed(Link link) {
        FilterSettings.Links l = settings.links;
        if (link.host != null) {
            if (domainListed(link, l.domainWhitelist)) {
                return true;
            }
            if (domainListed(link, l.domainBlacklist)) {
                return false;
            }
        }
        for (String f : l.formatBlacklist) {
            if (link.formats.contains(f.toLowerCase(Locale.ROOT).strip())) {
                return false;
            }
        }
        for (String f : l.formatWhitelist) {
            if (link.formats.contains(f.toLowerCase(Locale.ROOT).strip())) {
                return true;
            }
        }
        return !"block".equalsIgnoreCase(l.mode);
    }

    private static boolean domainListed(Link link, List<String> list) {
        String host = link.host;
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        String path = link.path.toLowerCase(Locale.ROOT);
        for (String raw : list) {
            String entry = raw.toLowerCase(Locale.ROOT).strip().replaceFirst("^[a-z][a-z0-9+.\\-]*://", "").replaceFirst("^\\*\\.", "")
                    .replaceFirst("^www\\.", "");
            if (entry.isEmpty()) {
                continue;
            }
            int slash = entry.indexOf('/');
            String entryHost = slash >= 0 ? entry.substring(0, slash) : entry;
            String entryPath = slash >= 0 ? entry.substring(slash) : "";
            if ((host.equals(entryHost) || host.endsWith("." + entryHost)) && path.startsWith(entryPath)) {
                return true;
            }
        }
        return false;
    }
}
