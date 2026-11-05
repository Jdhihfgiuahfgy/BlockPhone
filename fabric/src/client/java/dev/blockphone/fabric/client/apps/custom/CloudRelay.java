package dev.blockphone.fabric.client.apps.custom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.common.apps.AppDefinition;
import dev.blockphone.common.apps.AppPermission;
import dev.blockphone.common.protocol.packet.AppPackets;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.network.ClientNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Сетевые игры своих приложений без плагина на сервере: телефон подключается по WebSocket к комнате в облаке
 * ({@code relay-server/}). Комната — «этот сервер Minecraft» (хэш адреса, как его ввёл игрок) или комната по коду.
 * Сервер комнат — общий BlockPhone ({@link #DEFAULT_URL}) или свой у приложения (app.json {@code "relay"}): по одному
 * подключению на каждый адрес, который нужен установленным приложениям. Подключается, только если плагина нет (иначе
 * {@code net.*} идёт через него) или выбрана комната по коду; Lua-API {@code net.*} тот же — {@link ScriptHost}
 * выбирает дорогу сам. Только поток рендера.
 */
public final class CloudRelay {
    public static final String DEFAULT_URL = "https://blockphone-relay.darkminecraft000.workers.dev";
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int[] RETRY_SECONDS = {2, 5, 10, 20, 40, 60};
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    /** Ключ сессии телефона: с ним можно переподключиться под своим ником, чужой с другим ключом его не займёт. */
    private static final String SESSION_KEY = randomKey();
    /** Подключения по адресу сервера комнат. */
    private static final Map<String, CloudRelay> RELAYS = new LinkedHashMap<>();
    private static long lastCheck;

    public enum Status { OFF, CONNECTING, ONLINE, NAME_TAKEN }

    private final String url;
    private @Nullable String code;
    private @Nullable String room;
    private @Nullable WebSocket socket;
    private boolean connecting;
    private int generation;
    private int failures;
    private long nextAttempt;
    private long lastPing;
    private Status status = Status.OFF;
    private List<String> members = List.of();
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    private CloudRelay(String url) {
        this.url = url;
    }

    /** Подключение к общему серверу комнат BlockPhone (экраны и колонки в мире). */
    public static CloudRelay system() {
        return RELAYS.computeIfAbsent(defaultUrl(), CloudRelay::new);
    }

    private static String defaultUrl() {
        String env = System.getenv("BLOCKPHONE_RELAY_URL");
        return env != null && !env.isBlank() ? env.strip() : DEFAULT_URL;
    }

    /** Подключение для приложения (его "relay" или общий сервер BlockPhone). */
    public static CloudRelay forApp(AppDefinition app) {
        return RELAYS.computeIfAbsent(urlOf(app), CloudRelay::new);
    }

    /** Адрес сервера комнат приложения. Переменная BLOCKPHONE_RELAY_URL подменяет только общий (для проверки). */
    static String urlOf(AppDefinition app) {
        String own = app.script() != null ? app.script().relay() : null;
        if (own != null) {
            return own;
        }
        return defaultUrl();
    }

    // --- Для ScriptHost ---

    /** Комната на связи — {@code net.*} может идти сюда. */
    public boolean online() {
        return status == Status.ONLINE && socket != null;
    }

    public Status status() {
        return status;
    }

    /** Код комнаты (или null — комната «этот сервер»). */
    public @Nullable String code() {
        return code;
    }

    /** Игроки в комнате, кроме себя. */
    public List<String> players() {
        String self = Minecraft.getInstance().getUser().getName();
        List<String> out = new ArrayList<>();
        for (String n : members) {
            if (!n.equalsIgnoreCase(self)) {
                out.add(n);
            }
        }
        return out;
    }

    public void send(String appId, String target, String payload) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "send");
        o.addProperty("a", appId);
        o.addProperty("to", target);
        o.addProperty("d", payload);
        sendText(o.toString());
    }

    /** Войти в комнату по коду (6 символов без похожих букв); null или пусто — назад в комнату «этот сервер». */
    public boolean joinCode(@Nullable String value) {
        String next = value == null ? "" : value.strip().toUpperCase(Locale.ROOT).replaceFirst("^BP-", "");
        if (!next.isEmpty() && !next.matches("[" + CODE_ALPHABET + "]{6}")) {
            return false;
        }
        code = next.isEmpty() ? null : next;
        lastCheck = 0;
        return true;
    }

    public static String newCode() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    // --- Такт ---

    public static void tick() {
        long now = System.currentTimeMillis();
        if (now - lastCheck > 1000) {
            lastCheck = now;
            // Какие серверы комнат нужны установленным приложениям с разрешением multiplayer.
            Set<String> needed = new LinkedHashSet<>();
            for (AppDefinition def : CustomApps.all()) {
                if (def.script() != null && def.has(AppPermission.MULTIPLAYER)) {
                    needed.add(urlOf(def));
                }
            }
            if (dev.blockphone.fabric.client.screens.ScreenSync.wantsCloud()) {
                needed.add(defaultUrl()); // экраны и колонки других игроков на сервере без плагина
            }
            for (String u : needed) {
                RELAYS.computeIfAbsent(u, CloudRelay::new);
            }
            for (CloudRelay r : RELAYS.values()) {
                r.update(needed.contains(r.url) ? r.wantedRoom() : null);
            }
        }
        for (CloudRelay r : RELAYS.values()) {
            r.tickConnection(now);
        }
    }

    private void update(@Nullable String want) {
        if (!Objects.equals(want, room)) {
            BlockPhoneFabric.LOGGER.info("[apps] cloud room {}: {} -> {}", url, room, want);
            close();
            room = want;
            failures = 0;
            nextAttempt = 0;
            status = want == null ? Status.OFF : Status.CONNECTING;
        }
    }

    private void tickConnection(long now) {
        if (room == null || status == Status.NAME_TAKEN) {
            return;
        }
        if (socket == null && !connecting && now >= nextAttempt) {
            connect();
        }
        if (socket != null && now - lastPing > 30_000) {
            lastPing = now;
            sendText("ping"); // сервер отвечает «pong» сам, не будя комнату
        }
    }

    /** Какая комната нужна сейчас: по коду, «этот сервер» (сетевая игра без плагина) или никакая. */
    private @Nullable String wantedRoom() {
        if (code != null) {
            return "c" + code;
        }
        Minecraft mc = Minecraft.getInstance();
        if (ClientNetwork.serverHello() != null || mc.getConnection() == null || mc.hasSingleplayerServer()) {
            return null; // плагин есть (пересылает он) или одиночная игра
        }
        ServerData server = mc.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) {
            return null;
        }
        return "s" + serverHash(server.ip);
    }

    /** Хэш адреса сервера: регистр и порт по умолчанию не важны. */
    public static String serverHash(String address) {
        String a = address.strip().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) {
            a = a.substring(0, a.length() - 6);
        }
        if (a.endsWith(".")) {
            a = a.substring(0, a.length() - 1);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(("blockphone:" + a).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void connect() {
        connecting = true;
        status = Status.CONNECTING;
        int gen = ++generation;
        Minecraft mc = Minecraft.getInstance();
        String name = URLEncoder.encode(mc.getUser().getName(), StandardCharsets.UTF_8);
        String uri = url.replaceFirst("/+$", "").replaceFirst("^http", "ws") + "/ws/" + room + "?name=" + name + "&k=" + SESSION_KEY;
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
                        int code = error.getCause() instanceof WebSocketHandshakeException h ? h.getResponse().statusCode() : -1;
                        if (code == 409) {
                            status = Status.NAME_TAKEN; // этим ником в комнате уже кто-то (другой телефон)
                            return;
                        }
                        disconnected();
                    } else {
                        BlockPhoneFabric.LOGGER.info("[apps] cloud room {} connected ({})", room, url);
                        socket = ws;
                        failures = 0;
                    }
                }));
    }

    private void disconnected() {
        socket = null;
        connecting = false;
        members = List.of();
        status = room == null ? Status.OFF : Status.CONNECTING;
        nextAttempt = System.currentTimeMillis() + RETRY_SECONDS[Math.min(failures, RETRY_SECONDS.length - 1)] * 1000L;
        failures++;
    }

    private void close() {
        generation++;
        WebSocket ws = socket;
        socket = null;
        connecting = false;
        members = List.of();
        status = Status.OFF;
        if (ws != null) {
            synchronized (this) {
                sendChain = sendChain.handle((r, e) -> null).thenCompose(x -> ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye"))
                        .whenComplete((r, e) -> ws.abort());
            }
        }
    }

    private void sendText(String text) {
        WebSocket ws = socket;
        if (ws != null) {
            synchronized (this) {
                sendChain = sendChain.handle((r, e) -> null).thenCompose(x -> ws.sendText(text, true));
            }
        }
    }

    public static void shutdown() {
        for (CloudRelay r : RELAYS.values()) {
            r.close();
            r.room = null;
        }
    }

    private void onMessage(String text) {
        if (text.equals("pong")) {
            return;
        }
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(text);
            if (!e.isJsonObject()) {
                return;
            }
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String t = o.has("t") ? o.get("t").getAsString() : "";
        switch (t) {
            case "hello" -> {
                status = Status.ONLINE;
                members = names(o.getAsJsonArray("members"));
            }
            case "members" -> members = names(o.getAsJsonArray("members"));
            case "msg" -> {
                String app = o.get("a").getAsString();
                if (app.equals(dev.blockphone.fabric.client.screens.ScreenSync.APP)) {
                    if (url.equals(defaultUrl())) {
                        dev.blockphone.fabric.client.screens.ScreenSync.onMessage(o.get("f").getAsString(), o.get("d").getAsString());
                    }
                    return;
                }
                // Сообщение только тому приложению, которое ходит на этот сервер комнат.
                AppDefinition def = CustomApps.get(app);
                if (def == null || !urlOf(def).equals(url)) {
                    return;
                }
                String from = o.get("f").getAsString();
                String data = o.get("d").getAsString();
                UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + from).getBytes(StandardCharsets.UTF_8));
                CustomApps.onRelayed(new AppPackets.Relayed(app, uuid, from, data));
            }
            case "err" -> BlockPhoneFabric.LOGGER.debug("[apps] relay {}: {}", url, o);
            default -> {
            }
        }
    }

    private static List<String> names(@Nullable JsonArray array) {
        List<String> out = new ArrayList<>();
        if (array != null) {
            for (JsonElement e : array) {
                if (out.size() < 500) {
                    out.add(e.getAsString());
                }
            }
        }
        return List.copyOf(out);
    }

    private static String randomKey() {
        SecureRandom random = new SecureRandom();
        String abc = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            sb.append(abc.charAt(random.nextInt(abc.length())));
        }
        return sb.toString();
    }

    private final class Listener implements WebSocket.Listener {
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
            BlockPhoneFabric.LOGGER.info("[apps] cloud room closed: {} {} ({})", status, reason, url);
            Minecraft.getInstance().execute(() -> {
                if (gen == generation) {
                    disconnected();
                }
            });
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            BlockPhoneFabric.LOGGER.info("[apps] cloud room error: {} ({})", error.toString(), url);
            Minecraft.getInstance().execute(() -> {
                if (gen == generation) {
                    disconnected();
                }
            });
        }
    }
}
