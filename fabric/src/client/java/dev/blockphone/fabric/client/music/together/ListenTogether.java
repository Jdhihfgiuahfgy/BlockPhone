package dev.blockphone.fabric.client.music.together;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.music.Accounts;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.music.media.WindowsMedia;
import dev.blockphone.fabric.client.music.yandex.YandexApi;
import dev.blockphone.fabric.client.music.yandex.YandexMusic;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Совместное прослушивание: комната на сервере {@code listen-server} (Cloudflare, без плагина на игровом сервере).
 * Ведущий транслирует, что играет — в телефоне ({@link MusicPlayer}: свои файлы, Яндекс) или на ПК ({@link WindowsMedia}:
 * Spotify, Яндекс Музыка…): название, исполнитель, длительность, id трека Яндекса, позиция, пауза. Гость слушает:
 * <ul>
 *   <li>через Яндекс Музыку в телефоне — тот же трек (по id или поиском по названию), позиция и пауза — как у ведущего;</li>
 *   <li>через Spotify на ПК — телефон открывает поиск этой песни в Spotify; когда гость её включил, пауза и перемотка
 *   подстраиваются (системные медиа Windows).</li>
 * </ul>
 * Только поток рендера (сеть — в своих потоках, ответы — через {@code Minecraft.execute}).
 */
public final class ListenTogether {
    public static final String DEFAULT_URL = "https://blockphone-listen.darkminecraft000.workers.dev";
    public static final Pattern CODE = Pattern.compile("[A-HJ-NP-Z2-9]{6}");
    private static final int[] RETRY_SECONDS = {2, 4, 8, 15, 30};
    private static final long HEARTBEAT_MS = 10_000;
    private static final long DRIFT_MS = 2000;

    public enum Role { NONE, HOST, GUEST }

    public enum Link { CONNECTING, ONLINE, OFFLINE }

    /** Что слушает гость: Яндекс Музыка в телефоне или Spotify на ПК. */
    public enum Via { YANDEX, SPOTIFY, NONE }

    /** Состояние гостя для экрана. */
    public enum GuestStatus { WAITING, SEARCHING, PLAYING, NOT_FOUND, NO_SERVICE, SPOTIFY_OPENED, SPOTIFY_SYNCED, OTHER }

    /** Что играет у ведущего. title пустой — ничего. */
    public record Shared(String title, String artist, long durationMs, @Nullable String yandexId, String app, long positionMs,
                         boolean playing) {
        static final Shared NOTHING = new Shared("", "", 0, null, "", 0, false);

        boolean sameTrack(Shared o) {
            return o != null && title.equals(o.title) && artist.equals(o.artist) && java.util.Objects.equals(yandexId, o.yandexId);
        }

        String key() {
            return yandexId != null ? "y:" + yandexId : "t:" + artist + "|" + title;
        }

        public String label() {
            return artist.isEmpty() ? title : artist + " — " + title;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BlockPhone Listen Together");
        t.setDaemon(true);
        return t;
    });

    private static Role role = Role.NONE;
    private static String code = "";
    private static String hostKey = "";
    private static String hostName = "";
    private static List<String> members = List.of();
    private static Link link = Link.OFFLINE;
    private static @Nullable WebSocket socket;
    private static int generation;
    private static boolean connecting;
    private static int failures;
    private static long nextAttempt;
    private static @Nullable String notice;
    private static long noticeAt;

    // Ведущий
    private static @Nullable Shared lastSent;
    private static long lastSentAt;

    // Гость
    private static Shared shared = Shared.NOTHING;
    private static long sharedAt;
    private static long clockOffset;
    private static GuestStatus guestStatus = GuestStatus.WAITING;
    private static @Nullable String resolvedKey;
    private static @Nullable Track resolvedTrack;
    private static long lastSyncAt;
    private static long lastReplayAt;
    private static @Nullable String spotifyOpenedKey;

    // --- Состояние для экрана ---

    public static Role role() {
        return role;
    }

    public static String code() {
        return code;
    }

    public static String hostName() {
        return hostName;
    }

    public static List<String> members() {
        return members;
    }

    public static Link link() {
        return link;
    }

    public static Shared shared() {
        return role == Role.HOST ? hostState() : shared;
    }

    public static GuestStatus guestStatus() {
        return guestStatus;
    }

    /** Сообщение для экрана (ошибка, «комната закрыта») не старше 8 с. */
    public static @Nullable String notice() {
        return notice != null && System.currentTimeMillis() - noticeAt < 8000 ? notice : null;
    }

    static String url() {
        String env = System.getenv("BLOCKPHONE_LISTEN_URL");
        return env != null && !env.isBlank() ? env.strip() : DEFAULT_URL;
    }

    /** Через что слушает гость: по настройке togetherVia (auto — Яндекс, если вошли, иначе Spotify на ПК). */
    public static Via via() {
        String setting = PhoneSettings.get().togetherVia;
        if ("yandex".equals(setting)) {
            return Via.YANDEX;
        }
        if ("spotify".equals(setting)) {
            return WindowsMedia.supported() ? Via.SPOTIFY : Via.NONE;
        }
        if (YandexMusic.status() == YandexMusic.Status.READY) {
            return Via.YANDEX;
        }
        return WindowsMedia.supported() ? Via.SPOTIFY : Via.NONE;
    }

    public static void setVia(String value) {
        PhoneSettings.get().togetherVia = value;
        PhoneSettings.get().save();
        // Заново найти и включить песню новым способом.
        resolvedKey = null;
        spotifyOpenedKey = null;
    }

    // --- Комната ---

    /** Создать комнату (ведущий). done(null) — готово, иначе ключ ошибки music.together.error.*. */
    public static void create(Consumer<String> done) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject body = new JsonObject();
        body.addProperty("name", mc.getUser().getName());
        HttpRequest request = HttpRequest.newBuilder(URI.create(url() + "/create")).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).whenComplete((response, error) -> mc.execute(() -> {
            if (error != null) {
                done.accept("music.together.error.network");
                return;
            }
            try {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (response.statusCode() != 200 || !json.has("code")) {
                    done.accept("music.together.error." + (json.has("error") ? json.get("error").getAsString() : "server"));
                    return;
                }
                close();
                role = Role.HOST;
                code = json.get("code").getAsString();
                hostKey = json.get("key").getAsString();
                hostName = mc.getUser().getName();
                members = List.of(hostName);
                lastSent = null;
                startConnecting();
                done.accept(null);
            } catch (RuntimeException e) {
                done.accept("music.together.error.server");
            }
        }));
    }

    /** Войти гостем по коду. false — код не похож на код комнаты. */
    public static boolean join(String raw) {
        String c = raw.strip().toUpperCase(Locale.ROOT).replace("BP-", "");
        if (!CODE.matcher(c).matches()) {
            return false;
        }
        close();
        role = Role.GUEST;
        code = c;
        hostKey = "";
        hostName = "";
        members = List.of();
        shared = Shared.NOTHING;
        resolvedKey = null;
        resolvedTrack = null;
        spotifyOpenedKey = null;
        guestStatus = GuestStatus.WAITING;
        startConnecting();
        TogetherInvites.forget(c);
        return true;
    }

    /** Выйти из комнаты (ведущий — закрыть её: гости получат «закрыта» через 90 с без ведущего). */
    public static void leave() {
        if (role == Role.GUEST && resolvedTrack != null && MusicPlayer.current() != null
                && resolvedTrack.key().equals(MusicPlayer.current().key())) {
            MusicPlayer.stop();
        }
        if (role == Role.HOST) {
            send("{\"t\":\"close\"}"); // гостям сразу «комната закрыта»
        }
        close();
        role = Role.NONE;
        code = "";
        hostKey = "";
        members = List.of();
        shared = Shared.NOTHING;
        resolvedTrack = null;
        resolvedKey = null;
    }

    private static void close() {
        generation++;
        WebSocket ws = socket;
        socket = null;
        connecting = false;
        link = Link.OFFLINE;
        if (ws != null) {
            // После уже поставленных в очередь сообщений (например, «закрыть комнату»).
            synchronized (ListenTogether.class) {
                sendChain = sendChain.handle((r, e) -> null).thenCompose(x -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"))
                        .whenComplete((r, e) -> ws.abort());
            }
        }
    }

    private static void startConnecting() {
        failures = 0;
        nextAttempt = 0;
        link = Link.CONNECTING;
    }

    private static void connect() {
        connecting = true;
        link = Link.CONNECTING;
        int gen = ++generation;
        Minecraft mc = Minecraft.getInstance();
        String name = URLEncoder.encode(mc.getUser().getName(), StandardCharsets.UTF_8);
        String uri = url().replaceFirst("^http", "ws") + "/ws/" + code + "?name=" + name
                + (role == Role.HOST ? "&key=" + hostKey : "");
        HTTP.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(15)).buildAsync(URI.create(uri), new Listener(gen))
                .whenComplete((ws, error) -> mc.execute(() -> {
                    if (gen != generation) {
                        if (ws != null) {
                            ws.abort();
                        }
                        return;
                    }
                    connecting = false;
                    if (error != null) {
                        int status = error.getCause() instanceof WebSocketHandshakeException h ? h.getResponse().statusCode() : -1;
                        if (status == 404 || status == 403) {
                            // Комнаты нет (закрыта) или она полна — дальше не пытаемся.
                            String key = status == 404 ? "music.together.error.no_room" : "music.together.error.full";
                            leave();
                            setNotice(PhoneLang.tr(key));
                            return;
                        }
                        disconnected();
                    } else {
                        socket = ws;
                    }
                }));
    }

    private static void disconnected() {
        socket = null;
        connecting = false;
        link = Link.OFFLINE;
        nextAttempt = System.currentTimeMillis() + RETRY_SECONDS[Math.min(failures, RETRY_SECONDS.length - 1)] * 1000L;
        failures++;
    }

    private static void setNotice(String text) {
        notice = text;
        noticeAt = System.currentTimeMillis();
    }

    private static void send(String text) {
        WebSocket ws = socket;
        if (ws != null) {
            synchronized (ListenTogether.class) {
                sendChain = sendChain.handle((r, e) -> null).thenCompose(x -> ws.sendText(text, true));
            }
        }
    }

    private static java.util.concurrent.CompletableFuture<?> sendChain = java.util.concurrent.CompletableFuture.completedFuture(null);
    private static long lastPing;

    // --- Такт ---

    /** Каждый такт клиента. */
    public static void tick() {
        TogetherInvites.tick();
        if (role == Role.NONE) {
            return;
        }
        long now = System.currentTimeMillis();
        if (socket == null && !connecting && now >= nextAttempt) {
            connect();
        }
        WebSocket ws = socket;
        if (ws != null && now - lastPing > 25_000) {
            lastPing = now;
            synchronized (ListenTogether.class) {
                sendChain = sendChain.handle((r, e) -> null).thenCompose(x -> ws.sendPing(java.nio.ByteBuffer.allocate(0)));
            }
        }
        if (role == Role.HOST) {
            tickHost(now);
        } else {
            tickGuest(now);
        }
    }

    /** Что сейчас играет у ведущего: телефон важнее (если в нём что-то включено), иначе плеер ПК. */
    private static Shared hostState() {
        Track t = MusicPlayer.current();
        MusicPlayer.State st = MusicPlayer.state();
        if (t != null && st != MusicPlayer.State.STOPPED) {
            return new Shared(t.title(), t.artist(), Math.max(t.durationMs(), MusicPlayer.durationMs()),
                    t.source() == Track.Source.YANDEX ? t.remoteId() : null, "phone", MusicPlayer.positionMs(),
                    st == MusicPlayer.State.PLAYING || st == MusicPlayer.State.LOADING);
        }
        WindowsMedia.watch();
        WindowsMedia.Playing p = WindowsMedia.playing();
        if (p != null && !p.title().isEmpty()) {
            return new Shared(p.title(), p.artist(), p.durationMs(), null, p.appName(), p.position(), p.playing());
        }
        return Shared.NOTHING;
    }

    private static void tickHost(long now) {
        if (link != Link.ONLINE) {
            return;
        }
        Shared s = hostState();
        boolean changed = lastSent == null || !s.sameTrack(lastSent) || s.playing() != lastSent.playing();
        if (!changed && lastSent != null && s.playing()) {
            long expected = lastSent.positionMs() + (now - lastSentAt);
            changed = Math.abs(expected - s.positionMs()) > 1500; // ведущий перемотал
        } else if (!changed && lastSent != null) {
            changed = Math.abs(lastSent.positionMs() - s.positionMs()) > 1500;
        }
        if (changed || now - lastSentAt > HEARTBEAT_MS) {
            JsonObject st = new JsonObject();
            st.addProperty("title", s.title());
            st.addProperty("artist", s.artist());
            st.addProperty("durationMs", s.durationMs());
            if (s.yandexId() != null) {
                st.addProperty("yandexId", s.yandexId());
            }
            st.addProperty("app", s.app());
            st.addProperty("positionMs", s.positionMs());
            st.addProperty("playing", s.playing());
            JsonObject msg = new JsonObject();
            msg.addProperty("t", "state");
            msg.add("s", st);
            send(msg.toString());
            lastSent = s;
            lastSentAt = now;
        }
    }

    /** Где сейчас должен быть трек у гостя (мс). */
    private static long target(long now) {
        long serverNow = now + clockOffset;
        long pos = shared.positionMs() + (shared.playing() ? Math.max(0, serverNow - sharedAt) : 0);
        return shared.durationMs() > 0 ? Math.min(pos, shared.durationMs()) : pos;
    }

    private static void tickGuest(long now) {
        if (shared.title().isEmpty()) {
            guestStatus = GuestStatus.WAITING;
            pauseOwn();
            return;
        }
        switch (via()) {
            case YANDEX -> syncYandex(now);
            case SPOTIFY -> syncSpotify(now);
            case NONE -> guestStatus = GuestStatus.NO_SERVICE;
        }
    }

    private static void pauseOwn() {
        Track current = MusicPlayer.current();
        if (resolvedTrack != null && current != null && current.key().equals(resolvedTrack.key())
                && MusicPlayer.state() == MusicPlayer.State.PLAYING) {
            MusicPlayer.pause();
        }
    }

    // --- Гость: Яндекс Музыка в телефоне ---

    private static void syncYandex(long now) {
        if (YandexMusic.status() != YandexMusic.Status.READY) {
            guestStatus = GuestStatus.NO_SERVICE;
            return;
        }
        String key = shared.key();
        if (!key.equals(resolvedKey)) {
            resolvedKey = key;
            resolvedTrack = null;
            guestStatus = GuestStatus.SEARCHING;
            Shared wanted = shared;
            String token = Accounts.get().yandexToken;
            WORKER.execute(() -> {
                Track found = null;
                try {
                    found = resolve(new YandexApi(token), wanted);
                } catch (Exception e) {
                    BlockPhoneFabric.LOGGER.info("Listen together: Yandex search failed: {}", e.toString());
                }
                Track result = found;
                Minecraft.getInstance().execute(() -> {
                    if (role != Role.GUEST || !key.equals(resolvedKey)) {
                        return;
                    }
                    if (result == null) {
                        guestStatus = GuestStatus.NOT_FOUND;
                        return;
                    }
                    resolvedTrack = result;
                    guestStatus = GuestStatus.PLAYING;
                    playResolved();
                });
            });
            return;
        }
        Track mine = resolvedTrack;
        if (mine == null) {
            return;
        }
        Track current = MusicPlayer.current();
        if (current == null || !current.key().equals(mine.key())) {
            if (current == null && shared.playing() && now - lastReplayAt > 5000) {
                playResolved(); // плеер остановился (вышли из мира и т. п.) — включить снова
            } else if (current != null) {
                guestStatus = GuestStatus.OTHER; // игрок сам включил другое — не мешаем
            }
            return;
        }
        guestStatus = GuestStatus.PLAYING;
        MusicPlayer.State st = MusicPlayer.state();
        if (st == MusicPlayer.State.LOADING || now - lastSyncAt < 1500) {
            return;
        }
        if (shared.playing() && st == MusicPlayer.State.PAUSED) {
            MusicPlayer.toggle();
            lastSyncAt = now;
        } else if (!shared.playing() && st == MusicPlayer.State.PLAYING) {
            MusicPlayer.pause();
            lastSyncAt = now;
        }
        long target = target(now);
        if (Math.abs(MusicPlayer.positionMs() - target) > DRIFT_MS && now - lastSyncAt > 3000) {
            MusicPlayer.seek(target);
            lastSyncAt = now;
        }
    }

    private static void playResolved() {
        if (resolvedTrack == null) {
            return;
        }
        lastReplayAt = System.currentTimeMillis();
        MusicPlayer.play(List.of(resolvedTrack), 0, null, PhoneLang.tr("music.together.queue"));
        lastSyncAt = 0;
    }

    /** Трек в Яндекс Музыке: по id, иначе поиском «исполнитель название» (похожая длительность и название). */
    static @Nullable Track resolve(YandexApi api, Shared wanted) throws Exception {
        if (wanted.yandexId() != null) {
            List<Track> list = api.tracks(List.of(wanted.yandexId()));
            if (!list.isEmpty()) {
                return list.get(0);
            }
        }
        List<Track> results = api.search((wanted.artist() + " " + wanted.title()).strip());
        return best(results, wanted);
    }

    static @Nullable Track best(List<Track> results, Shared wanted) {
        Track best = null;
        int bestScore = Integer.MIN_VALUE;
        String title = norm(wanted.title());
        String artist = norm(wanted.artist());
        for (int i = 0; i < Math.min(results.size(), 10); i++) {
            Track t = results.get(i);
            int score = -i;
            String tt = norm(t.title());
            if (tt.equals(title)) {
                score += 50;
            } else if (tt.contains(title) || title.contains(tt)) {
                score += 25;
            }
            if (!artist.isEmpty() && (norm(t.artist()).contains(artist) || artist.contains(norm(t.artist())))) {
                score += 30;
            }
            if (wanted.durationMs() > 0 && t.durationMs() > 0) {
                long diff = Math.abs(wanted.durationMs() - t.durationMs());
                score += diff < 3000 ? 20 : diff < 10_000 ? 5 : -20;
            }
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        // Совсем не похоже — лучше «не нашлось», чем чужая песня.
        return bestScore >= 20 ? best : null;
    }

    /** Для сравнения названий: нижний регистр, без «(feat. …)», «[…]» и знаков. */
    static String norm(String s) {
        String r = s.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)|\\[.*?]", " ");
        r = r.replaceAll("(?i)\\b(feat|ft)\\..*$", " ");
        return r.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    // --- Гость: Spotify на ПК ---

    private static void syncSpotify(long now) {
        WindowsMedia.watch();
        String key = shared.key();
        if (!key.equals(spotifyOpenedKey)) {
            spotifyOpenedKey = key;
            openSpotifySearch(shared);
            guestStatus = GuestStatus.SPOTIFY_OPENED;
            lastSyncAt = now;
            return;
        }
        WindowsMedia.Playing p = WindowsMedia.playing();
        if (p == null || !"Spotify".equals(p.appName()) || !sameSong(p.title(), shared.title())) {
            guestStatus = GuestStatus.SPOTIFY_OPENED;
            return;
        }
        guestStatus = GuestStatus.SPOTIFY_SYNCED;
        if (now - lastSyncAt < 2000) {
            return;
        }
        if (p.playing() != shared.playing()) {
            WindowsMedia.togglePlay();
            lastSyncAt = now;
            return;
        }
        long target = target(now);
        if (p.canSeek() && Math.abs(p.position() - target) > 2500 && now - lastSyncAt > 4000) {
            WindowsMedia.seek(target);
            lastSyncAt = now;
        }
    }

    static boolean sameSong(String a, String b) {
        String x = norm(a);
        String y = norm(b);
        return !x.isEmpty() && !y.isEmpty() && (x.equals(y) || x.contains(y) || y.contains(x));
    }

    /** Открыть поиск песни в приложении Spotify (spotify:search:…); без Spotify Windows сама ничего не сделает. */
    static void openSpotifySearch(Shared s) {
        String query = (s.artist() + " " + s.title()).strip();
        if (query.isEmpty() || !WindowsMedia.supported()) {
            return;
        }
        String uri = "spotify:search:" + URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri).start();
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.info("Listen together: Spotify not opened: {}", e.toString());
        }
    }

    // --- Сообщения сервера ---

    private static void onMessage(String text) {
        JsonObject msg;
        try {
            msg = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String t = msg.has("t") ? msg.get("t").getAsString() : "";
        long local = System.currentTimeMillis();
        if (msg.has("now")) {
            clockOffset = msg.get("now").getAsLong() - local;
        }
        switch (t) {
            case "hello" -> {
                link = Link.ONLINE;
                failures = 0;
                hostName = str(msg, "host");
                members = names(msg.get("members"));
                applyState(msg.get("state"), msg.has("at") ? msg.get("at").getAsLong() : 0);
                if (role == Role.HOST) {
                    lastSent = null; // после переподключения — сразу отправить своё состояние
                }
            }
            case "members" -> members = names(msg.get("members"));
            case "state" -> applyState(msg.get("s"), msg.get("at").getAsLong());
            case "closed" -> {
                leave();
                setNotice(PhoneLang.tr("music.together.closed"));
            }
            default -> {
            }
        }
    }

    private static void applyState(@Nullable JsonElement element, long at) {
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject s = element.getAsJsonObject();
        shared = new Shared(str(s, "title"), str(s, "artist"), s.has("durationMs") ? s.get("durationMs").getAsLong() : 0,
                s.has("yandexId") && !s.get("yandexId").isJsonNull() ? s.get("yandexId").getAsString() : null, str(s, "app"),
                s.has("positionMs") ? s.get("positionMs").getAsLong() : 0, s.has("playing") && s.get("playing").getAsBoolean());
        sharedAt = at;
        lastSyncAt = 0; // новое состояние — подстроиться сразу
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static List<String> names(@Nullable JsonElement element) {
        List<String> list = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            element.getAsJsonArray().forEach(e -> list.add(e.getAsString()));
        }
        return List.copyOf(list);
    }

    /** Закрытие игры. */
    public static void shutdown() {
        close();
    }

    private static final class Listener implements WebSocket.Listener {
        private final int gen;
        private final StringBuilder buffer = new StringBuilder();

        Listener(int gen) {
            this.gen = gen;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (buffer.length() > 64 * 1024) {
                ws.abort();
                return null;
            }
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                Minecraft.getInstance().execute(() -> {
                    if (gen == generation) {
                        onMessage(text);
                    }
                });
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            Minecraft.getInstance().execute(() -> {
                if (gen != generation) {
                    return;
                }
                if (status == 4001) {
                    leave();
                    setNotice(PhoneLang.tr("music.together.closed"));
                } else if (status == 4000) {
                    leave();
                    setNotice(PhoneLang.tr("music.together.replaced"));
                } else {
                    disconnected();
                }
            });
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            Minecraft.getInstance().execute(() -> {
                if (gen == generation) {
                    disconnected();
                }
            });
        }
    }

    private ListenTogether() {
    }
}
