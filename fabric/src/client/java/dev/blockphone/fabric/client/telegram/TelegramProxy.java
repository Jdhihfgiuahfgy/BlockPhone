package dev.blockphone.fabric.client.telegram;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.storage.ClientFiles;
import it.tdlight.jni.TdApi;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Прокси для Telegram (там, где Telegram без VPN не подключается). TDLib сам ходит через прокси MTProto, SOCKS5 или HTTP —
 * все запросы, вход и файлы. Режимы ({@link PhoneSettings#telegramProxy}):
 * <ul>
 *   <li>auto — сначала напрямую; если за {@link #AUTO_WAIT_MS} не подключились — проверяем свои прокси и общий список
 *       ({@link #LIST_URL}, его обновляет владелец каталога) и включаем самый быстрый;</li>
 *   <li>custom — всегда через свои прокси (самый быстрый из рабочих);</li>
 *   <li>off — напрямую.</li>
 * </ul>
 * Свои прокси — ссылки {@code tg://proxy?server=…&port=…&secret=…}, {@code https://t.me/proxy?…}, {@code tg://socks?…},
 * {@code socks5://user:pass@host:port}, {@code http://host:port} ({@link PhoneSettings#telegramProxies}).
 * Запросы о прокси в TDLib разных версий отличаются (поля proxy/server…), поэтому они собираются через отражение по именам полей.
 */
public final class TelegramProxy {
    /** Общий список прокси (обновляется без новой версии мода): {"proxies": ["tg://proxy?...", ...]}. */
    public static final String LIST_URL = "https://raw.githubusercontent.com/Jdhihfgiuahfgy/blockphone-apps/main/proxies.json";
    public static final List<String> MODES = List.of("auto", "custom", "off");
    /** Сколько ждать прямого подключения в режиме auto, прежде чем искать прокси. */
    static final long AUTO_WAIT_MS = 12_000;
    private static final long PING_TIMEOUT_MS = 10_000;
    private static final int MAX_CANDIDATES = 24;
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8)).build();

    /** Прокси: type — mtproto | socks5 | http. */
    public record Proxy(String type, String server, int port, String secret, String user, String password) {
        public String label() {
            return server + ":" + port + " (" + (type.equals("mtproto") ? "MTProto" : type.toUpperCase(Locale.ROOT)) + ")";
        }
    }

    public enum State { DIRECT, WAITING, SEARCHING, PROXY, NONE_WORKING, OFF }

    private static State state = State.DIRECT;
    private static @Nullable String activeLabel;
    private static int activePing;
    private static boolean connected;
    private static long connectingSince;
    /** Номер попытки — ответы старой проверки после новой игнорируются. */
    private static int generation;
    private static List<String> remote = List.of();
    private static boolean remoteLoaded;

    // --- Ссылки ---

    /** Разобрать ссылку на прокси; null — не прокси. */
    public static @Nullable Proxy parse(String link) {
        String s = link == null ? "" : link.strip();
        if (s.isEmpty()) {
            return null;
        }
        try {
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.startsWith("tg://proxy") || lower.startsWith("https://t.me/proxy") || lower.startsWith("http://t.me/proxy")
                    || lower.startsWith("t.me/proxy")) {
                Map<String, String> q = query(s);
                return valid(new Proxy("mtproto", q.getOrDefault("server", ""), port(q.get("port")), q.getOrDefault("secret", ""), "", ""));
            }
            if (lower.startsWith("tg://socks") || lower.startsWith("https://t.me/socks") || lower.startsWith("t.me/socks")) {
                Map<String, String> q = query(s);
                return valid(new Proxy("socks5", q.getOrDefault("server", ""), port(q.get("port")), "", q.getOrDefault("user", ""),
                        q.getOrDefault("pass", "")));
            }
            if (lower.startsWith("socks5://") || lower.startsWith("socks://") || lower.startsWith("http://")
                    || lower.startsWith("mtproto://")) {
                URI uri = URI.create(s);
                String type = lower.startsWith("http://") ? "http" : lower.startsWith("mtproto://") ? "mtproto" : "socks5";
                String user = "";
                String pass = "";
                if (uri.getRawUserInfo() != null) {
                    String[] parts = uri.getRawUserInfo().split(":", 2);
                    user = decode(parts[0]);
                    pass = parts.length > 1 ? decode(parts[1]) : "";
                }
                if (type.equals("mtproto")) {
                    return valid(new Proxy(type, uri.getHost(), uri.getPort(), user, "", ""));
                }
                return valid(new Proxy(type, uri.getHost(), uri.getPort(), "", user, pass));
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static @Nullable Proxy valid(Proxy p) {
        if (p.server() == null || p.server().isBlank() || p.port() <= 0 || p.port() > 65535) {
            return null;
        }
        if (p.type().equals("mtproto") && !p.secret().matches("(?i)[0-9a-f]{32,}|[A-Za-z0-9_\\-+/=]{16,}")) {
            return null;
        }
        return p;
    }

    private static int port(@Nullable String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Map<String, String> query(String link) {
        Map<String, String> result = new HashMap<>();
        int q = link.indexOf('?');
        if (q < 0) {
            return result;
        }
        for (String pair : link.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(pair.substring(0, eq).toLowerCase(Locale.ROOT), decode(pair.substring(eq + 1)));
            }
        }
        return result;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    // --- Свои прокси ---

    public static List<String> custom() {
        return PhoneSettings.get().telegramProxies;
    }

    /** Добавить ссылку; false — это не прокси. */
    public static boolean addCustom(String link) {
        Proxy p = parse(link);
        if (p == null) {
            return false;
        }
        String clean = link.strip();
        if (!custom().contains(clean)) {
            custom().add(clean);
            PhoneSettings.get().save();
        }
        apply();
        return true;
    }

    public static void removeCustom(String link) {
        custom().remove(link);
        PhoneSettings.get().save();
        apply();
    }

    public static void setMode(String mode) {
        PhoneSettings.get().telegramProxy = MODES.contains(mode) ? mode : "auto";
        PhoneSettings.get().save();
        apply();
    }

    public static String mode() {
        String m = PhoneSettings.get().telegramProxy;
        return MODES.contains(m) ? m : "auto";
    }

    // --- Состояние ---

    public static State state() {
        return state;
    }

    /** Строка для экрана: «Напрямую», «Через 1.2.3.4:443 (MTProto) · 120 мс», «Ищем рабочий прокси…». */
    public static String statusText() {
        return switch (state) {
            case DIRECT -> PhoneLang.tr(connected ? "telegram.proxy.state.direct_ok" : "telegram.proxy.state.direct");
            case WAITING -> PhoneLang.tr("telegram.proxy.state.waiting");
            case SEARCHING -> PhoneLang.tr("telegram.proxy.state.searching");
            case PROXY -> PhoneLang.tr(connected ? "telegram.proxy.state.proxy_ok" : "telegram.proxy.state.proxy",
                    activeLabel == null ? "?" : activeLabel, activePing);
            case NONE_WORKING -> PhoneLang.tr("telegram.proxy.state.none");
            case OFF -> PhoneLang.tr("telegram.proxy.state.off");
        };
    }

    /** Клиент TDLib создан — применить настройки (поток рендера). */
    static void onClientStarted() {
        connected = false;
        connectingSince = System.currentTimeMillis();
        apply();
    }

    /** Клиент закрыт. */
    static void onClientClosed() {
        generation++;
        connected = false;
        state = State.DIRECT;
    }

    /** UpdateConnectionState: в режиме auto долгое «подключение» запускает поиск прокси. */
    static void onConnectionState(TdApi.ConnectionState s) {
        boolean ready = s instanceof TdApi.ConnectionStateReady || s instanceof TdApi.ConnectionStateUpdating;
        if (ready) {
            connected = true;
            if (state == State.WAITING) {
                state = State.DIRECT;
            }
        } else {
            if (connected) {
                connectingSince = System.currentTimeMillis();
            }
            connected = false;
        }
    }

    /** Раз в тик (из Telegram.tick или приложения): режим auto — пора ли искать прокси. */
    public static void tick() {
        if (!Telegram.running() || connected || !mode().equals("auto")) {
            return;
        }
        if (state == State.DIRECT || state == State.WAITING) {
            state = State.WAITING;
            if (System.currentTimeMillis() - connectingSince > AUTO_WAIT_MS) {
                search(true);
            }
        } else if (state == State.PROXY && System.currentTimeMillis() - connectingSince > AUTO_WAIT_MS * 2) {
            // Выбранный прокси перестал работать — ищем заново.
            connectingSince = System.currentTimeMillis();
            search(true);
        }
    }

    /** Применить режим: убрать прошлые прокси из TDLib и включить нужный. */
    public static void apply() {
        if (!Telegram.running()) {
            return;
        }
        String mode = mode();
        if (mode.equals("off")) {
            generation++;
            clearProxies(() -> Telegram.sendQuiet(new TdApi.DisableProxy(), r -> { }));
            state = State.OFF;
            return;
        }
        if (mode.equals("custom")) {
            search(false);
            return;
        }
        // auto: напрямую, пока подключение не затянется.
        generation++;
        clearProxies(() -> Telegram.sendQuiet(new TdApi.DisableProxy(), r -> { }));
        state = connected ? State.DIRECT : State.WAITING;
        connectingSince = System.currentTimeMillis();
    }

    /** Найти рабочий прокси: свои (+ общий список в auto), добавить в TDLib, проверить ping, включить самый быстрый. */
    private static void search(boolean withPublic) {
        int gen = ++generation;
        state = State.SEARCHING;
        connectingSince = System.currentTimeMillis();
        if (withPublic && !remoteLoaded) {
            loadRemote(() -> {
                if (gen == generation) {
                    searchNow(gen, true);
                }
            });
            return;
        }
        searchNow(gen, withPublic);
    }

    private static void searchNow(int gen, boolean withPublic) {
        Map<String, Proxy> candidates = new LinkedHashMap<>();
        for (String link : custom()) {
            Proxy p = parse(link);
            if (p != null) {
                candidates.putIfAbsent(p.server() + ":" + p.port(), p);
            }
        }
        if (withPublic) {
            for (String link : remote) {
                Proxy p = parse(link);
                if (p != null && candidates.size() < MAX_CANDIDATES) {
                    candidates.putIfAbsent(p.server() + ":" + p.port(), p);
                }
            }
        }
        if (candidates.isEmpty()) {
            state = State.NONE_WORKING;
            return;
        }
        clearProxies(() -> {
            if (gen != generation) {
                return;
            }
            List<Proxy> list = new ArrayList<>(candidates.values());
            Map<Integer, Proxy> added = new HashMap<>();
            Map<Integer, Double> pings = new HashMap<>();
            int[] pending = {list.size()};
            long started = System.currentTimeMillis();
            Runnable finish = () -> {
                if (gen != generation) {
                    return;
                }
                int best = -1;
                double bestPing = Double.MAX_VALUE;
                for (Map.Entry<Integer, Double> e : pings.entrySet()) {
                    if (e.getValue() < bestPing) {
                        bestPing = e.getValue();
                        best = e.getKey();
                    }
                }
                if (best < 0) {
                    state = State.NONE_WORKING;
                    BlockPhoneFabric.LOGGER.info("Telegram proxy: none of {} proxies answered", list.size());
                    return;
                }
                Proxy chosen = added.get(best);
                activeLabel = chosen.label();
                activePing = (int) Math.round(bestPing * 1000);
                state = State.PROXY;
                connectingSince = System.currentTimeMillis();
                BlockPhoneFabric.LOGGER.info("Telegram proxy: using {} ({} ms)", chosen.label(), activePing);
                Telegram.sendQuiet(new TdApi.EnableProxy(best), r -> { });
            };
            for (Proxy p : list) {
                TdApi.Function<?> add = addProxy(p);
                if (add == null) {
                    if (--pending[0] == 0) {
                        finish.run();
                    }
                    continue;
                }
                Telegram.sendQuiet(add, r -> {
                    int id = r instanceof TdApi.Error ? -1 : intField(r, "id");
                    if (id <= 0 || gen != generation) {
                        if (--pending[0] == 0) {
                            finish.run();
                        }
                        return;
                    }
                    added.put(id, p);
                    TdApi.Function<?> ping = pingProxy(id, p);
                    if (ping == null) {
                        if (--pending[0] == 0) {
                            finish.run();
                        }
                        return;
                    }
                    Telegram.sendQuiet(ping, pr -> {
                        if (!(pr instanceof TdApi.Error) && System.currentTimeMillis() - started < PING_TIMEOUT_MS * 2) {
                            double seconds = doubleField(pr, "seconds");
                            if (seconds > 0) {
                                pings.put(id, seconds);
                            }
                        }
                        if (--pending[0] == 0) {
                            finish.run();
                        }
                    });
                });
            }
        });
    }

    /** Удалить из TDLib все прокси (их список хранится в базе TDLib между запусками). */
    private static void clearProxies(Runnable then) {
        Telegram.sendQuiet(new TdApi.GetProxies(), r -> {
            Object[] proxies = r instanceof TdApi.Error ? null : arrayField(r, "proxies");
            if (proxies == null || proxies.length == 0) {
                then.run();
                return;
            }
            int[] left = {proxies.length};
            for (Object p : proxies) {
                int id = intField(p, "id");
                Telegram.sendQuiet(new TdApi.RemoveProxy(id), rr -> {
                    if (--left[0] == 0) {
                        then.run();
                    }
                });
            }
        });
    }

    /** Общий список прокси (с кэшем на диске на случай, если GitHub недоступен). */
    private static void loadRemote(Runnable then) {
        Path cache = ClientFiles.root().resolve("cache").resolve("telegram_proxies.json");
        ClientFiles.IO.execute(() -> {
            String text = null;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(LIST_URL)).timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "BlockPhone/" + BlockPhoneFabric.VERSION).build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().length() < 256 * 1024) {
                    text = response.body();
                    Files.createDirectories(cache.getParent());
                    Files.writeString(cache, text, StandardCharsets.UTF_8);
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.info("Telegram proxy list unavailable: {}", e.toString());
            }
            if (text == null) {
                try {
                    text = Files.exists(cache) ? Files.readString(cache, StandardCharsets.UTF_8) : null;
                } catch (Exception ignored) {
                    // нет кэша
                }
            }
            List<String> links = new ArrayList<>();
            if (text != null) {
                try {
                    JsonElement root = JsonParser.parseString(text);
                    JsonArray array = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("proxies");
                    for (JsonElement e : array) {
                        links.add(e.isJsonObject() && e.getAsJsonObject().has("link") ? e.getAsJsonObject().get("link").getAsString()
                                : e.getAsString());
                    }
                } catch (RuntimeException e) {
                    BlockPhoneFabric.LOGGER.warn("Telegram proxy list is broken: {}", e.toString());
                }
            }
            Minecraft.getInstance().execute(() -> {
                remote = List.copyOf(links);
                remoteLoaded = true;
                then.run();
            });
        });
    }

    // --- Запросы TDLib через отражение (имена полей одинаковы, а состав в разных версиях TDLib — нет) ---

    private static @Nullable TdApi.Function<?> addProxy(Proxy p) {
        try {
            Object type = proxyType(p);
            Object add = newObject("AddProxy");
            if (hasField(add, "proxy")) {
                // Новые TDLib: addProxy proxy:proxy enable:Bool.
                Field f = add.getClass().getField("proxy");
                Object proxy = f.getType().getConstructor().newInstance();
                set(proxy, "server", p.server());
                set(proxy, "port", p.port());
                set(proxy, "type", type);
                f.set(add, proxy);
            } else {
                set(add, "server", p.server());
                set(add, "port", p.port());
                set(add, "type", type);
            }
            set(add, "enable", false);
            return (TdApi.Function<?>) add;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockPhoneFabric.LOGGER.warn("Telegram proxy: cannot build AddProxy", e);
            return null;
        }
    }

    private static @Nullable TdApi.Function<?> pingProxy(int id, Proxy p) {
        try {
            Object ping = newObject("PingProxy");
            if (hasField(ping, "proxyId")) {
                set(ping, "proxyId", id);
            } else if (hasField(ping, "proxy")) {
                Field f = ping.getClass().getField("proxy");
                Object proxy = f.getType().getConstructor().newInstance();
                set(proxy, "server", p.server());
                set(proxy, "port", p.port());
                set(proxy, "type", proxyType(p));
                f.set(ping, proxy);
            }
            return (TdApi.Function<?>) ping;
        } catch (ReflectiveOperationException | RuntimeException e) {
            BlockPhoneFabric.LOGGER.warn("Telegram proxy: cannot build PingProxy", e);
            return null;
        }
    }

    private static Object proxyType(Proxy p) throws ReflectiveOperationException {
        return switch (p.type()) {
            case "socks5" -> {
                Object t = newObject("ProxyTypeSocks5");
                set(t, "username", p.user());
                set(t, "password", p.password());
                yield t;
            }
            case "http" -> {
                Object t = newObject("ProxyTypeHttp");
                set(t, "username", p.user());
                set(t, "password", p.password());
                set(t, "httpOnly", false);
                yield t;
            }
            default -> {
                Object t = newObject("ProxyTypeMtproto");
                set(t, "secret", p.secret());
                yield t;
            }
        };
    }

    private static Object newObject(String name) throws ReflectiveOperationException {
        return Class.forName("it.tdlight.jni.TdApi$" + name).getConstructor().newInstance();
    }

    private static boolean hasField(Object o, String name) {
        try {
            o.getClass().getField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static void set(Object o, String name, Object value) throws ReflectiveOperationException {
        if (hasField(o, name)) {
            o.getClass().getField(name).set(o, value);
        }
    }

    private static int intField(Object o, String name) {
        try {
            return ((Number) o.getClass().getField(name).get(o)).intValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    private static double doubleField(Object o, String name) {
        try {
            return ((Number) o.getClass().getField(name).get(o)).doubleValue();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    private static Object @Nullable [] arrayField(Object o, String name) {
        try {
            Object value = o.getClass().getField(name).get(o);
            return value instanceof Object[] array ? array : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private TelegramProxy() {
    }
}
