package dev.blockphone.common.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.blockphone.common.apps.AppResult;
import dev.blockphone.common.apps.AppText;
import dev.blockphone.common.apps.AppsSettings;
import dev.blockphone.common.apps.BlockPhoneApps;
import dev.blockphone.common.apps.InputRules;
import dev.blockphone.common.apps.ServerPart;
import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.packet.AppPackets;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Серверные части клиентских приложений и политика приложений сервера.
 * Серверные части — файлы папки {@code apps} (Paper — plugins/BlockPhone/apps, Fabric — config/blockphone/apps,
 * Velocity/BungeeCord — папка плагина/apps) и Java-обработчики плагинов ({@link BlockPhoneApps}). Папка проверяется
 * каждые 5 секунд. Переменные и функции выполняются в главном потоке сервера (команды, PlaceholderAPI).
 * Запрещённое политикой приложение сервер не обслуживает, даже если телефон всё же к нему обратится.
 */
public final class AppService {
    private static final String README = """
            BlockPhone: серверные части приложений / server parts of phone apps

            Приложения ставит себе игрок (папка игры blockphone/apps). Если приложению нужны данные или действия сервера
            (баланс, перевод денег…), положите сюда его серверную часть: <id приложения>.json или папку <id>/server.json.
            Перезапуск не нужен — файлы перечитываются каждые 5 секунд, ошибки пишутся в лог.
            Какие приложения игрокам можно использовать на этом сервере — раздел apps в настройках BlockPhone.

            Инструкция: CUSTOM_APPS.md рядом с этим файлом.
            """;

    private final ServerPlatform platform;
    private final AppsSettings policy;
    private final Map<UUID, String> players;
    private final RateLimiter callLimit = new RateLimiter(12, 6_000);
    /** Сообщения между приложениями игроков: не больше 40 за 5 с от одного игрока (игры шлют ходы часто). */
    private final RateLimiter relayLimit = new RateLimiter(40, 5_000);
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BlockPhone Apps");
        t.setDaemon(true);
        return t;
    });
    private volatile Map<String, ServerPart> parts = Map.of();
    private Path dir;
    private String fingerprint = "";
    private java.util.Set<String> lastSent = java.util.Set.of();

    AppService(ServerPlatform platform, AppsSettings policy, Map<UUID, String> players) {
        this.platform = platform;
        this.policy = policy.fixNulls();
        this.players = players;
    }

    void start(Path directory) {
        this.dir = directory;
        if (directory != null) {
            createReadme(directory);
        }
        BlockPhoneApps.onChange(() -> watcher.execute(this::reload));
        watcher.execute(this::reload);
        watcher.scheduleWithFixedDelay(() -> {
            if (!fingerprint().equals(fingerprint)) {
                reload();
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    void stop() {
        BlockPhoneApps.onChange(null);
        watcher.shutdownNow();
    }

    public Map<String, ServerPart> parts() {
        return parts;
    }

    synchronized void reload() {
        fingerprint = fingerprint();
        Map<String, ServerPart> next = new LinkedHashMap<>();
        if (dir != null) {
            ServerPart.Loaded loaded = ServerPart.loadDirectory(dir);
            for (String error : loaded.errors()) {
                platform.warn("BlockPhone app server part not loaded: " + error, null);
            }
            for (ServerPart part : loaded.parts()) {
                next.put(part.id(), part);
            }
        }
        for (ServerPart part : BlockPhoneApps.registered()) {
            next.putIfAbsent(part.id(), part);
        }
        java.util.Set<String> before = withServer();
        parts = Map.copyOf(next);
        java.util.Set<String> after = withServer();
        if (!after.equals(before) || !after.equals(lastSent)) {
            lastSent = after;
            if (!next.isEmpty()) {
                platform.info("BlockPhone app server parts: " + String.join(", ", next.keySet()));
            }
            for (UUID player : players.keySet()) {
                sendPolicy(player);
            }
        }
    }

    /** Приложения с серверной частью: файлы и Java-обработчики. */
    private java.util.Set<String> withServer() {
        TreeSet<String> ids = new TreeSet<>(parts.keySet());
        ids.addAll(BlockPhoneApps.handlerIds());
        return ids;
    }

    /** Политика и список приложений с серверной частью. */
    void sendPolicy(UUID player) {
        send(player, new AppPackets.Policy(policy.mode, List.copyOf(policy.blacklist), List.copyOf(policy.whitelist),
                new ArrayList<>(withServer())));
    }

    void handle(UUID player, Packet packet) {
        switch (packet) {
            case AppPackets.RequestVariables p -> {
                if (!serves(player, p.appId())) {
                    return;
                }
                platform.runOnMain(() -> send(player, new AppPackets.Variables(p.appId(), p.page(), variables(player, p.appId(), p.page()))));
            }
            case AppPackets.Call p -> {
                if (!serves(player, p.appId())) {
                    send(player, new AppPackets.CallResult(p.appId(), "@app.error.no_server", true, "", Map.of()));
                    return;
                }
                if (!callLimit.allow(player)) {
                    send(player, new AppPackets.CallResult(p.appId(), "@app.error.rate_limit", true, "", Map.of()));
                    return;
                }
                platform.runOnMain(() -> {
                    AppResult result;
                    try {
                        result = call(player, p);
                    } catch (RuntimeException e) {
                        platform.warn("BlockPhone app " + p.appId() + " function " + p.function() + " failed", e);
                        result = AppResult.error("@app.error.internal");
                    }
                    send(player, new AppPackets.CallResult(p.appId(), result.toast(), result.error(), result.navigate(),
                            result.error() ? Map.of() : variables(player, p.appId(), p.page())));
                });
            }
            case AppPackets.Relay p -> relay(player, p);
            case AppPackets.RequestPlayers p -> {
                List<String> names = new ArrayList<>();
                players.forEach((uuid, name) -> {
                    if (!uuid.equals(player)) {
                        names.add(name);
                    }
                });
                names.sort(String.CASE_INSENSITIVE_ORDER);
                send(player, new AppPackets.Players(p.appId(), names));
            }
            default -> {
            }
        }
    }

    /**
     * Переслать сообщение приложения: адресату по нику или всем с BlockPhone. Приложение, запрещённое на сервере по id,
     * не пересылается; содержимое сервер не разбирает (у получателя его читает только то же приложение).
     */
    private void relay(UUID player, AppPackets.Relay p) {
        String fromName = players.get(player);
        if (fromName == null || !AppsSettings.allowed(policy.mode, policy.blacklist, policy.whitelist, p.appId(), null)
                || !relayLimit.allow(player)) {
            return;
        }
        AppPackets.Relayed out = new AppPackets.Relayed(p.appId(), player, fromName, p.payload());
        for (Map.Entry<UUID, String> e : players.entrySet()) {
            if (e.getKey().equals(player)) {
                continue;
            }
            if (p.target().isEmpty() || e.getValue().equalsIgnoreCase(p.target())) {
                send(e.getKey(), out);
            }
        }
    }

    /**
     * Обслуживать ли приложение для игрока: есть серверная часть или обработчик, есть право. Блокировку по id сервер
     * проверяет сам; по хэшу — на телефоне (сервер содержимого app.json не знает).
     */
    private boolean serves(UUID player, String appId) {
        ServerPart part = parts.get(appId);
        if (part == null && BlockPhoneApps.handler(appId) == null) {
            return false;
        }
        if (!AppsSettings.allowed(policy.mode, policy.blacklist, policy.whitelist, appId, null)) {
            return false;
        }
        return part == null || part.permission() == null || platform.hasPermissionNode(player, part.permission(), false);
    }

    private Map<String, String> builtins(UUID player) {
        Map<String, String> vars = new HashMap<>();
        vars.put("player", players.getOrDefault(player, ""));
        vars.put("uuid", player.toString());
        vars.put("online", String.valueOf(players.size()));
        return vars;
    }

    private Map<String, String> variables(UUID player, String appId, String page) {
        Map<String, String> builtins = builtins(player);
        Map<String, String> result = new LinkedHashMap<>();
        ServerPart part = parts.get(appId);
        if (part != null) {
            for (Map.Entry<String, String> v : part.variables().entrySet()) {
                result.put(v.getKey(), limit(platform.placeholders(player, AppText.substitute(v.getValue(), builtins))));
            }
        }
        BlockPhoneApps.AppHandler handler = BlockPhoneApps.handler(appId);
        if (handler != null) {
            try {
                handler.variables(player, page).forEach((k, v) -> result.put(k, limit(v)));
            } catch (RuntimeException e) {
                platform.warn("BlockPhone app " + appId + " variables failed", e);
            }
        }
        return result;
    }

    private AppResult call(UUID player, AppPackets.Call p) {
        ServerPart part = parts.get(p.appId());
        JsonObject function = part != null ? part.functions().get(p.function()) : null;
        Map<String, String> inputs = new LinkedHashMap<>();
        if (function != null && function.has("inputs")) {
            // Сервер проверяет поля сам — телефону не доверяем.
            for (Map.Entry<String, JsonElement> def : function.getAsJsonObject("inputs").entrySet()) {
                JsonObject rules = def.getValue().getAsJsonObject();
                String raw = p.inputs().getOrDefault(def.getKey(), "");
                String error = InputRules.validate(rules, raw);
                if (error != null) {
                    return AppResult.error(error + "|" + def.getKey());
                }
                inputs.put(def.getKey(), InputRules.normalize(rules, raw));
            }
        } else {
            p.inputs().forEach((k, v) -> inputs.put(k, InputRules.clean(v)));
        }
        AppResult result = AppResult.ok();
        if (function != null) {
            Map<String, String> builtins = builtins(player);
            String name = builtins.get("player");
            boolean console = function.has("as") && "console".equalsIgnoreCase(function.get("as").getAsString());
            List<String> commands = new ArrayList<>();
            if (function.has("command")) {
                commands.add(function.get("command").getAsString());
            }
            if (function.has("commands")) {
                for (JsonElement c : function.getAsJsonArray("commands")) {
                    commands.add(c.getAsString());
                }
            }
            for (String template : commands) {
                // Сначала переменные админа и PlaceholderAPI, потом поля — текст игрока не раскрывает %плейсхолдеры%.
                String command = AppText.substitute(platform.placeholders(player, AppText.substitute(template, builtins)), inputs).strip();
                if (command.startsWith("/")) {
                    command = command.substring(1);
                }
                platform.info("[apps] " + name + " (" + p.appId() + "." + p.function() + "): " + (console ? "console" : "player") + " /" + command);
                if (command.isEmpty() || !platform.runCommand(player, command, console)) {
                    return AppResult.error(function.has("error") ? text(function.get("error"), builtins, inputs) : "@app.error.command");
                }
            }
            if (function.has("toast")) {
                result = result.withToast(text(function.get("toast"), builtins, inputs));
            }
            if (function.has("then")) {
                result = result.then(function.get("then").getAsString());
            }
        }
        BlockPhoneApps.AppHandler handler = BlockPhoneApps.handler(p.appId());
        if (handler != null) {
            AppResult called = handler.call(player, p.function(), inputs);
            if (called != null) {
                if (called.error() || function == null) {
                    return called;
                }
                result = new AppResult(called.toast().isEmpty() ? result.toast() : called.toast(), false,
                        called.navigate().isEmpty() ? result.navigate() : called.navigate());
            } else if (function == null) {
                return AppResult.error("@app.error.no_function");
            }
        } else if (function == null) {
            return AppResult.error("@app.error.no_function");
        }
        return result;
    }

    /** Текст ответа: перевод сохраняется как JSON-объект строкой, телефон выберет язык. */
    private static String text(JsonElement e, Map<String, String> builtins, Map<String, String> inputs) {
        JsonElement mapped = AppText.map(e, s -> AppText.substitute(AppText.substitute(s, builtins), inputs));
        return mapped.isJsonPrimitive() ? mapped.getAsString() : mapped.toString();
    }

    private static String limit(String value) {
        return value == null ? "" : value.length() > 2000 ? value.substring(0, 2000) : value;
    }

    private String fingerprint() {
        if (dir == null || !Files.isDirectory(dir)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(dir, 2)) {
            for (Path f : files.sorted().toList()) {
                sb.append(f).append(Files.getLastModifiedTime(f).toMillis()).append(';');
            }
        } catch (IOException e) {
            return fingerprint;
        }
        return sb.toString();
    }

    private void createReadme(Path directory) {
        try {
            Files.createDirectories(directory);
            Path readme = directory.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, README, StandardCharsets.UTF_8);
            }
            try (var in = AppService.class.getResourceAsStream("/blockphone/apps/CUSTOM_APPS.md")) {
                if (in != null) {
                    Files.write(directory.resolve("CUSTOM_APPS.md"), in.readAllBytes());
                }
            }
        } catch (IOException e) {
            platform.warn("BlockPhone apps folder " + directory + " not created", e);
        }
    }

    private void send(UUID player, Packet packet) {
        platform.send(player, Packets.encode(packet));
    }
}
