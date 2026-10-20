package dev.blockphone.fabric.client.music.spotify;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.music.Accounts;
import dev.blockphone.fabric.client.music.Http;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Пульт Spotify: вход через браузер (OAuth PKCE — без секрета, нужен только Client ID приложения игрока),
 * текущий трек, пауза/вперёд/назад, перемотка, громкость, перемешивание, повтор. Звук играет сам Spotify
 * (на компьютере или телефоне); управление через Web API требует Spotify Premium.
 * Состояние — на потоке рендера, сеть — в {@link ClientFiles#IO}.
 */
public final class Spotify {
    public static final int PORT = 43219;
    public static final String REDIRECT_URI = "http://127.0.0.1:" + PORT + "/callback";
    private static final String SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing";
    private static final String API = "https://api.spotify.com/v1";
    private static final long POLL_MS = 2500;
    private static final long WATCH_MS = 5000;

    public enum Status { DISCONNECTED, AUTHORIZING, CONNECTED }

    /** Что сейчас играет в Spotify. */
    public record Playing(String title, String artist, @Nullable String coverUrl, long durationMs, long progressMs, boolean playing,
                          String device, int volume, boolean shuffle, String repeat, long fetchedAt) {
        /** Позиция с учётом времени, прошедшего после запроса. */
        public long position() {
            if (!playing) {
                return progressMs;
            }
            return Math.min(durationMs, progressMs + System.currentTimeMillis() - fetchedAt);
        }
    }

    private static Status status;
    private static @Nullable Playing playing;
    private static boolean nothingPlaying;
    private static @Nullable String error;
    private static long errorAt;
    private static long lastPoll;
    private static long watchedAt;
    private static boolean polling;

    public static Status status() {
        if (status == null) {
            status = Accounts.get().spotifyRefreshToken.isEmpty() ? Status.DISCONNECTED : Status.CONNECTED;
        }
        return status;
    }

    public static @Nullable Playing playing() {
        return playing;
    }

    /** Spotify подключён, но ничего не играет (или нет активного устройства). */
    public static boolean nothingPlaying() {
        return nothingPlaying;
    }

    public static @Nullable String recentError() {
        return error != null && System.currentTimeMillis() - errorAt < 6000 ? error : null;
    }

    /** UI или HUD показывает Spotify — нужно обновлять состояние. */
    public static void watch() {
        watchedAt = System.currentTimeMillis();
    }

    // --- Вход ---

    /** Открыть браузер для входа и ждать ответа на 127.0.0.1:43219 (до 3 минут). */
    public static void connect(String clientId) {
        String id = clientId.strip();
        if (id.isEmpty() || status() == Status.AUTHORIZING) {
            return;
        }
        Accounts.get().spotifyClientId = id;
        Accounts.save();
        String verifier = randomString(64);
        String state = randomString(16);
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", id);
        query.put("response_type", "code");
        query.put("redirect_uri", REDIRECT_URI);
        query.put("code_challenge_method", "S256");
        query.put("code_challenge", challenge);
        query.put("state", state);
        query.put("scope", SCOPES);
        String url = "https://accounts.spotify.com/authorize?" + Http.form(query);
        status = Status.AUTHORIZING;
        Thread thread = new Thread(() -> awaitCallback(id, verifier, state), "BlockPhone Spotify login");
        thread.setDaemon(true);
        thread.start();
        Util.getPlatform().openUri(url);
    }

    public static void disconnect() {
        Accounts a = Accounts.get();
        a.spotifyAccessToken = "";
        a.spotifyRefreshToken = "";
        a.spotifyExpiresAt = 0;
        Accounts.save();
        TOKEN.set(null);
        status = Status.DISCONNECTED;
        playing = null;
    }

    private static void awaitCallback(String clientId, String verifier, String state) {
        String result = "music.error.spotify_login";
        try (ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(180_000);
            while (true) {
                try (Socket socket = server.accept()) {
                    String line = readRequestLine(socket.getInputStream());
                    Map<String, String> params = queryOf(line);
                    if (!line.contains("/callback")) {
                        respond(socket.getOutputStream(), 404, "Not found");
                        continue;
                    }
                    String code = params.get("code");
                    boolean ok = code != null && state.equals(params.get("state"));
                    respond(socket.getOutputStream(), 200, PhoneLang.tr(ok ? "music.spotify_browser_ok" : "music.spotify_browser_fail"));
                    if (ok) {
                        exchange(clientId, Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", REDIRECT_URI,
                                "client_id", clientId, "code_verifier", verifier));
                        result = null;
                    }
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            result = "music.error.spotify_timeout";
        } catch (IOException e) {
            BlockPhoneFabric.LOGGER.warn("Spotify login failed", e);
        }
        String failure = result;
        Minecraft.getInstance().execute(() -> {
            if (failure == null) {
                status = Status.CONNECTED;
                lastPoll = 0;
            } else {
                status = Accounts.get().spotifyRefreshToken.isEmpty() ? Status.DISCONNECTED : Status.CONNECTED;
                showError(failure);
            }
        });
    }

    private static String readRequestLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n' && sb.length() < 8192) {
            sb.append((char) c);
        }
        return sb.toString().strip();
    }

    /** Параметры из «GET /callback?code=..&state=.. HTTP/1.1». */
    static Map<String, String> queryOf(String requestLine) {
        Map<String, String> result = new LinkedHashMap<>();
        int q = requestLine.indexOf('?');
        int end = requestLine.indexOf(' ', Math.max(q, 0));
        if (q < 0) {
            return result;
        }
        String query = requestLine.substring(q + 1, end > q ? end : requestLine.length());
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return result;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static void respond(OutputStream out, int code, String text) throws IOException {
        String html = "<!doctype html><meta charset=utf-8><title>BlockPhone</title>"
                + "<body style=\"font-family:sans-serif;background:#111725;color:#e6ecf5;text-align:center;padding-top:15vh\">"
                + "<h2>BlockPhone</h2><p>" + text.replace("<", "&lt;") + "</p></body>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + body.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /** Обмен кода или обновление токена. Пишет токены в Accounts. */
    private static void exchange(String clientId, Map<String, String> form) throws IOException {
        Http.Response response = Http.request("POST", "https://accounts.spotify.com/api/token", Map.of(), Http.form(form));
        if (!response.ok()) {
            throw new IOException("Spotify token error " + response.code() + ": " + response.body());
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String access = json.get("access_token").getAsString();
        String refresh = json.has("refresh_token") ? json.get("refresh_token").getAsString() : null;
        long expires = System.currentTimeMillis() + json.get("expires_in").getAsLong() * 1000L;
        Minecraft.getInstance().execute(() -> {
            Accounts a = Accounts.get();
            a.spotifyClientId = clientId;
            a.spotifyAccessToken = access;
            if (refresh != null) {
                a.spotifyRefreshToken = refresh;
            }
            a.spotifyExpiresAt = expires;
            Accounts.save();
        });
        // Токен нужен сразу же в этом потоке — не ждём потока рендера.
        TOKEN.set(access);
        EXPIRES.set(expires);
    }

    private static final java.util.concurrent.atomic.AtomicReference<String> TOKEN = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicLong EXPIRES = new java.util.concurrent.atomic.AtomicLong();

    /** Действующий токен (обновляется при необходимости). Только из фонового потока. */
    private static String token(Accounts snapshot) throws IOException {
        String access = TOKEN.get() != null ? TOKEN.get() : snapshot.spotifyAccessToken;
        long expires = TOKEN.get() != null ? EXPIRES.get() : snapshot.spotifyExpiresAt;
        if (access == null || access.isEmpty() || expires - System.currentTimeMillis() < 60_000) {
            if (snapshot.spotifyRefreshToken.isEmpty()) {
                throw new IOException("Not connected");
            }
            exchange(snapshot.spotifyClientId, Map.of("grant_type", "refresh_token", "refresh_token", snapshot.spotifyRefreshToken,
                    "client_id", snapshot.spotifyClientId));
            access = TOKEN.get();
        }
        return access;
    }

    // --- Состояние и управление ---

    public static void tick() {
        if (status() != Status.CONNECTED || polling) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - watchedAt < WATCH_MS && now - lastPoll > POLL_MS) {
            poll();
        }
    }

    private static void poll() {
        polling = true;
        lastPoll = System.currentTimeMillis();
        Accounts snapshot = copy();
        ClientFiles.IO.execute(() -> {
            Playing result = null;
            boolean empty = false;
            String failure = null;
            try {
                Http.Response r = Http.request("GET", API + "/me/player?additional_types=episode", auth(snapshot), null);
                if (r.code() == 204 || r.body().isBlank()) {
                    empty = true;
                } else if (r.code() == 401) {
                    failure = "music.error.spotify_login";
                } else if (r.ok()) {
                    result = parsePlaying(JsonParser.parseString(r.body()).getAsJsonObject());
                    empty = result == null;
                }
            } catch (IOException | RuntimeException e) {
                failure = "music.error.network";
            }
            Playing value = result;
            boolean nothing = empty;
            String err = failure;
            Minecraft.getInstance().execute(() -> {
                polling = false;
                lastPoll = System.currentTimeMillis();
                if (err != null) {
                    showError(err);
                    return;
                }
                playing = value;
                nothingPlaying = nothing;
            });
        });
    }

    static @Nullable Playing parsePlaying(JsonObject json) {
        JsonElement itemElement = json.get("item");
        if (itemElement == null || itemElement.isJsonNull()) {
            return null;
        }
        JsonObject item = itemElement.getAsJsonObject();
        StringBuilder artists = new StringBuilder();
        if (item.has("artists")) {
            for (JsonElement a : item.getAsJsonArray("artists")) {
                if (!artists.isEmpty()) {
                    artists.append(", ");
                }
                artists.append(a.getAsJsonObject().get("name").getAsString());
            }
        } else if (item.has("show")) {
            artists.append(item.getAsJsonObject("show").get("name").getAsString());
        }
        String cover = null;
        JsonObject images = item.has("album") ? item.getAsJsonObject("album") : item.has("show") ? item.getAsJsonObject("show") : null;
        if (images != null && images.has("images") && !images.getAsJsonArray("images").isEmpty()) {
            // Последняя картинка — самая маленькая, предпоследняя — средняя (300 px).
            var list = images.getAsJsonArray("images");
            cover = list.get(Math.max(0, list.size() - 2)).getAsJsonObject().get("url").getAsString();
        }
        JsonObject device = json.has("device") && json.get("device").isJsonObject() ? json.getAsJsonObject("device") : null;
        return new Playing(item.get("name").getAsString(), artists.toString(), cover, item.get("duration_ms").getAsLong(),
                json.has("progress_ms") && !json.get("progress_ms").isJsonNull() ? json.get("progress_ms").getAsLong() : 0,
                json.get("is_playing").getAsBoolean(), device != null ? device.get("name").getAsString() : "",
                device != null && device.has("volume_percent") && !device.get("volume_percent").isJsonNull()
                        ? device.get("volume_percent").getAsInt() : -1,
                json.has("shuffle_state") && json.get("shuffle_state").getAsBoolean(),
                json.has("repeat_state") ? json.get("repeat_state").getAsString() : "off", System.currentTimeMillis());
    }

    public static void togglePlay() {
        Playing p = playing;
        boolean play = p == null || !p.playing();
        if (p != null) {
            playing = new Playing(p.title(), p.artist(), p.coverUrl(), p.durationMs(), p.position(), play, p.device(), p.volume(),
                    p.shuffle(), p.repeat(), System.currentTimeMillis());
        }
        command("PUT", play ? "/me/player/play" : "/me/player/pause");
    }

    public static void next() {
        command("POST", "/me/player/next");
    }

    public static void previous() {
        command("POST", "/me/player/previous");
    }

    public static void seek(long ms) {
        Playing p = playing;
        if (p != null) {
            playing = new Playing(p.title(), p.artist(), p.coverUrl(), p.durationMs(), ms, p.playing(), p.device(), p.volume(),
                    p.shuffle(), p.repeat(), System.currentTimeMillis());
        }
        command("PUT", "/me/player/seek?position_ms=" + Math.max(0, ms));
    }

    public static void setVolume(int percent) {
        command("PUT", "/me/player/volume?volume_percent=" + Math.max(0, Math.min(100, percent)));
    }

    public static void toggleShuffle() {
        Playing p = playing;
        command("PUT", "/me/player/shuffle?state=" + (p == null || !p.shuffle()));
    }

    public static void cycleRepeat() {
        Playing p = playing;
        String next = p == null ? "context" : switch (p.repeat()) {
            case "off" -> "context";
            case "context" -> "track";
            default -> "off";
        };
        command("PUT", "/me/player/repeat?state=" + next);
    }

    private static void command(String method, String path) {
        if (status() != Status.CONNECTED) {
            return;
        }
        Accounts snapshot = copy();
        ClientFiles.IO.execute(() -> {
            String failure = null;
            try {
                Http.Response r = Http.request(method, API + path, auth(snapshot), null);
                if (r.code() == 403) {
                    failure = "music.error.spotify_premium";
                } else if (r.code() == 404) {
                    failure = "music.error.spotify_no_device";
                } else if (!r.ok()) {
                    failure = "music.error.network";
                }
            } catch (IOException e) {
                failure = "music.error.network";
            }
            String err = failure;
            Minecraft.getInstance().execute(() -> {
                if (err != null) {
                    showError(err);
                }
                lastPoll = System.currentTimeMillis() - POLL_MS + 400; // Обновить состояние почти сразу.
            });
        });
    }

    private static Map<String, String> auth(Accounts snapshot) throws IOException {
        return Map.of("Authorization", "Bearer " + token(snapshot));
    }

    private static Accounts copy() {
        Accounts a = Accounts.get();
        Accounts c = new Accounts();
        c.spotifyClientId = a.spotifyClientId;
        c.spotifyAccessToken = a.spotifyAccessToken;
        c.spotifyRefreshToken = a.spotifyRefreshToken;
        c.spotifyExpiresAt = a.spotifyExpiresAt;
        return c;
    }

    private static void showError(String key) {
        error = PhoneLang.tr(key);
        errorAt = System.currentTimeMillis();
    }

    private static String randomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Spotify() {
    }
}
