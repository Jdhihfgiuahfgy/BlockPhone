package dev.blockphone.fabric.client.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.common.apps.AppDefinition;
import dev.blockphone.common.apps.AppLoader;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.apps.custom.CustomApps;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Магазин приложений: каталоги («источники» — ссылки на catalog.json, например на GitHub), скачивание архива приложения,
 * проверка хэша, безопасная распаковка (только файлы приложения, без выхода из своей папки), проверка app.json и
 * установка в blockphone/apps/&lt;id&gt;. Формат каталога и как выложить приложения — docs/APP_STORE.md.
 */
public final class AppStore {
    /** Приложение в каталоге. */
    public record Entry(String source, String catalog, String id, String name, String version, String author, String description,
                        @Nullable String icon, String download, @Nullable String sha256, long size, List<String> permissions) {
    }

    public enum State { IDLE, LOADING, READY }

    /** Официальный каталог (репозиторий владельца мода) — источник у всех игроков, его нельзя убрать. */
    public static final String OFFICIAL = "https://raw.githubusercontent.com/Jdhihfgiuahfgy/blockphone-apps/main/catalog.json";
    /**
     * «Приёмник» публикаций (Cloudflare Worker, store-repo/worker.js): принимает архив и создаёт заявку (Pull Request)
     * в официальном репозитории. Ключ GitHub хранится только в приёмнике. Пусто — публикация ещё не настроена.
     */
    public static final String PUBLISH_URL = "https://blockphone-store.darkminecraft000.workers.dev";
    private static final long MAX_PUBLISH = 10L << 20;

    private static final long MAX_CATALOG = 2L << 20;
    private static final long MAX_ZIP = 30L << 20;
    private static final long MAX_UNPACKED = 40L << 20;
    private static final int MAX_FILES = 400;
    private static final Set<String> ALLOWED = Set.of(".json", ".lua", ".png", ".obj", ".txt", ".md");
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10)).build();

    private static List<Entry> entries = List.of();
    private static List<String> errors = List.of();
    private static State state = State.IDLE;
    private static final java.util.Map<String, String> BUSY = new java.util.concurrent.ConcurrentHashMap<>();
    /** Ход установки: 0..1 — скачано, 1 — распаковка. */
    private static final java.util.Map<String, Float> PROGRESS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Отзыв в официальном каталоге (reviews/&lt;id&gt;.json репозитория). */
    public record Review(String uuid, String player, int stars, String text, String version, long time) {
    }

    /** Средняя оценка и число отзывов (reviews/summary.json — собирает GitHub Actions). */
    public record Rating(double average, int count) {
    }

    private static java.util.Map<String, Rating> ratings = java.util.Map.of();
    private static final java.util.Map<String, List<Review>> REVIEWS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, String> REVIEW_ERRORS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Только для автоснимков: разрешить file: ссылки (локальный каталог). */
    public static boolean allowFiles;

    public static List<Entry> entries() {
        return entries;
    }

    public static List<String> errors() {
        return errors;
    }

    public static State state() {
        return state;
    }

    /** Свои источники игрока (без официального). */
    public static List<String> sources() {
        return PhoneSettings.get().storeSources;
    }

    /** Все каталоги: официальный + свои. */
    public static List<String> allSources() {
        List<String> all = new ArrayList<>();
        if (!allowFiles) {
            all.add(OFFICIAL);
        }
        for (String s : sources()) {
            if (!all.contains(s)) {
                all.add(s);
            }
        }
        return all;
    }

    public static void addSource(String url) {
        String u = url.strip();
        if (!u.isEmpty() && !sources().contains(u)) {
            sources().add(u);
            PhoneSettings.get().save();
            refresh();
        }
    }

    public static void removeSource(String url) {
        sources().remove(url);
        PhoneSettings.get().save();
        refresh();
    }

    /** Загрузить все каталоги заново (в фоне). */
    public static void refresh() {
        state = State.LOADING;
        List<String> list = allSources();
        ClientFiles.IO.execute(() -> {
            List<Entry> found = new ArrayList<>();
            List<String> problems = new ArrayList<>();
            for (String source : list) {
                try {
                    found.addAll(parseCatalog(source, new String(fetch(source, MAX_CATALOG), StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    problems.add(source + ": " + e.getMessage());
                }
            }
            found.sort(Comparator.comparing(e -> e.name().toLowerCase(Locale.ROOT)));
            java.util.Map<String, Rating> loaded = new java.util.HashMap<>();
            try {
                JsonObject summary = JsonParser.parseString(new String(fetch(reviewsUrl("summary.json"), 1L << 20),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : summary.entrySet()) {
                    JsonObject r = e.getValue().getAsJsonObject();
                    loaded.put(e.getKey(), new Rating(r.get("avg").getAsDouble(), r.get("count").getAsInt()));
                }
            } catch (Exception ignored) {
                // отзывов ещё нет
            }
            Minecraft.getInstance().execute(() -> {
                ratings = loaded;
                REVIEWS.clear();
                entries = found;
                if (debugReviews) {
                    debugFakeReviews();
                }
                errors = problems;
                state = State.READY;
            });
        });
    }

    static List<Entry> parseCatalog(String source, String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String catalog = root.has("name") ? root.get("name").getAsString() : URI.create(source).getHost();
        List<Entry> list = new ArrayList<>();
        for (JsonElement el : root.getAsJsonArray("apps")) {
            JsonObject a = el.getAsJsonObject();
            String id = a.get("id").getAsString().toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_-]{1,32}")) {
                continue;
            }
            List<String> perms = new ArrayList<>();
            if (a.has("permissions")) {
                for (JsonElement p : a.getAsJsonArray("permissions")) {
                    perms.add(p.getAsString());
                }
            }
            list.add(new Entry(source, catalog, id, str(a, "name", id), str(a, "version", "1.0"), str(a, "author", ""),
                    str(a, "description", ""), a.has("icon") ? resolve(source, a.get("icon").getAsString()) : null,
                    resolve(source, a.get("download").getAsString()), a.has("sha256") ? a.get("sha256").getAsString() : null,
                    a.has("size") ? a.get("size").getAsLong() : 0, List.copyOf(perms)));
        }
        return list;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static String resolve(String base, String url) {
        return URI.create(base).resolve(url.strip()).toString();
    }

    /** Скачать (https; в автоснимках — и file:), не больше max байт. */
    static byte[] fetch(String url, long max) throws IOException, InterruptedException {
        return fetch(url, max, 0, null);
    }

    /** То же с ходом скачивания: progress(0..1) по Content-Length или по ожидаемому размеру expected. */
    static byte[] fetch(String url, long max, long expected, java.util.function.@Nullable DoubleConsumer progress)
            throws IOException, InterruptedException {
        URI uri = URI.create(url);
        if ("file".equals(uri.getScheme()) && allowFiles) {
            return Files.readAllBytes(Path.of(uri));
        }
        if (!"https".equals(uri.getScheme())) {
            throw new IOException("only https:// links");
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30))
                .header("User-Agent", "BlockPhone/" + BlockPhoneFabric.VERSION).build();
        HttpResponse<InputStream> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        long total = response.headers().firstValueAsLong("Content-Length").orElse(expected);
        try (InputStream in = response.body()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) Math.min(Math.max(total, 8192), max));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) {
                    throw new IOException("file is too big");
                }
                if (progress != null && total > 0) {
                    progress.accept(Math.min(1.0, out.size() / (double) total));
                }
            }
            return out.toByteArray();
        }
    }

    // --- Установка ---

    /** Установленная версия или null. */
    public static @Nullable String installed(String id) {
        AppDefinition app = CustomApps.get(id);
        return app != null && Files.isDirectory(CustomApps.folder().resolve(id)) ? app.version() : null;
    }

    /** Ход установки 0..1 (скачивание; 1 — распаковка) или −1, если не устанавливается. */
    public static float progress(String id) {
        Float p = PROGRESS.get(id);
        return p == null ? -1 : p;
    }

    /** Размер «1.2 MB» / «340 KB». */
    public static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        if (bytes < 1024 * 1024) {
            return Math.max(1, (bytes + 1023) / 1024) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    /** Что сейчас происходит с приложением (скачивание…) или null. */
    public static @Nullable String busy(String id) {
        return BUSY.get(id);
    }

    /** Скачать и установить (обновить); done(null) — успех, иначе текст ошибки. На потоке рендера. */
    public static void install(Entry entry, Consumer<String> done) {
        if (BUSY.putIfAbsent(entry.id(), "download") != null) {
            return;
        }
        PROGRESS.put(entry.id(), 0f);
        ClientFiles.IO.execute(() -> {
            String error = null;
            try {
                byte[] zip = fetch(entry.download(), MAX_ZIP, entry.size(), p -> PROGRESS.put(entry.id(), (float) Math.min(0.99, p)));
                PROGRESS.put(entry.id(), 1f);
                if (entry.sha256() != null && !entry.sha256().isBlank()) {
                    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip));
                    if (!hash.equalsIgnoreCase(entry.sha256().strip())) {
                        throw new IOException("checksum does not match (the file was changed)");
                    }
                }
                installZip(entry.id(), zip);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("App store: installing {} failed", entry.id(), e);
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String result = error;
            Minecraft.getInstance().execute(() -> {
                BUSY.remove(entry.id());
                PROGRESS.remove(entry.id());
                CustomApps.scan(true);
                done.accept(result);
            });
        });
    }

    /** Распаковать архив во временную папку, проверить app.json и заменить папку приложения. */
    public static void installZip(String id, byte[] zip) throws IOException {
        Path apps = CustomApps.folder();
        Files.createDirectories(apps);
        Path tmp = apps.resolve(".install-" + id);
        deleteTree(tmp);
        Files.createDirectories(tmp);
        try {
            unzip(zip, tmp);
            Path root = tmp;
            if (!Files.exists(root.resolve("app.json"))) {
                // Архив с одной папкой внутри — приложение в ней.
                try (Stream<Path> s = Files.list(tmp)) {
                    List<Path> dirs = s.filter(Files::isDirectory).toList();
                    if (dirs.size() == 1 && Files.exists(dirs.get(0).resolve("app.json"))) {
                        root = dirs.get(0);
                    }
                }
            }
            Path json = root.resolve("app.json");
            if (!Files.exists(json)) {
                throw new IOException("no app.json in the archive");
            }
            byte[] icon = Files.exists(root.resolve("icon.png")) ? Files.readAllBytes(root.resolve("icon.png")) : null;
            AppDefinition app;
            try {
                app = AppLoader.parse(Files.readString(json, StandardCharsets.UTF_8), icon, id, root);
            } catch (AppLoader.AppException e) {
                throw new IOException("app.json: " + e.getMessage());
            }
            if (!app.id().equals(id)) {
                throw new IOException("app id is \"" + app.id() + "\", catalog says \"" + id + "\"");
            }
            Path target = apps.resolve(id);
            deleteTree(target);
            Files.move(root, target, StandardCopyOption.ATOMIC_MOVE);
            // Метка: скачано из магазина (такое приложение нельзя опубликовать как своё).
            Files.writeString(target.resolve(".store.json"), "{\"id\": \"" + id + "\"}");
        } finally {
            deleteTree(tmp);
        }
    }

    private static void unzip(byte[] zip, Path dir) throws IOException {
        long total = 0;
        int files = 0;
        Path base = dir.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = base.resolve(e.getName()).normalize();
                if (!out.startsWith(base)) {
                    throw new IOException("bad path in archive: " + e.getName());
                }
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                    continue;
                }
                String name = out.getFileName().toString().toLowerCase(Locale.ROOT);
                int dot = name.lastIndexOf('.');
                if (dot < 0 || !ALLOWED.contains(name.substring(dot))) {
                    continue; // чужие файлы (exe, jar…) не распаковываются
                }
                if (++files > MAX_FILES) {
                    throw new IOException("too many files in archive");
                }
                Files.createDirectories(out.getParent());
                byte[] data = in.readNBytes((int) Math.min(MAX_UNPACKED - total + 1, Integer.MAX_VALUE - 8));
                total += data.length;
                if (total > MAX_UNPACKED) {
                    throw new IOException("archive is too big when unpacked");
                }
                Files.write(out, data);
            }
        }
    }

    /**
     * Опубликовать своё приложение (папку blockphone/apps/&lt;id&gt;) в официальный каталог: архив только из файлов
     * приложения уходит в «приёмник», тот создаёт заявку; после одобрения владельцем каталога приложение появится у всех.
     * done(null) — заявка отправлена, иначе текст ошибки. На потоке рендера.
     */
    public static void publish(String id, Consumer<String> done) {
        if (PUBLISH_URL.isEmpty()) {
            done.accept("not_configured");
            return;
        }
        AppDefinition app = CustomApps.get(id);
        Path folder = CustomApps.folder().resolve(id);
        if (app == null || !Files.isDirectory(folder) || BUSY.putIfAbsent(id, "publish") != null) {
            done.accept("no_folder");
            return;
        }
        String key = authorKey(id);
        if (key == null) {
            BUSY.remove(id);
            done.accept("downloaded");
            return;
        }
        String player = Minecraft.getInstance().getUser().getName();
        ClientFiles.IO.execute(() -> {
            String error = null;
            try {
                byte[] zip = zipFolder(folder);
                HttpRequest request = HttpRequest.newBuilder(URI.create(PUBLISH_URL)).timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/zip").header("X-App-Id", id)
                        .header("X-App-Version", app.version()).header("X-Player", player).header("X-Author-Key", key)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(zip)).build();
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    String body = response.body();
                    try {
                        body = JsonParser.parseString(body).getAsJsonObject().get("error").getAsString();
                    } catch (RuntimeException ignored) {
                        // как есть
                    }
                    error = "HTTP " + response.statusCode() + ": " + body;
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String result = error;
            Minecraft.getInstance().execute(() -> {
                BUSY.remove(id);
                done.accept(result);
            });
        });
    }

    /**
     * Ключ автора приложения (config/blockphone/publish_keys.json; секрет — никуда, кроме приёмника, не уходит).
     * Нет ключа, а приложение скачано из магазина — null (это чужое приложение). Нет ключа у своего — создаётся.
     */
    public static synchronized @Nullable String authorKey(String id) {
        Path file = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("blockphone").resolve("publish_keys.json");
        JsonObject keys = new JsonObject();
        try {
            if (Files.exists(file)) {
                keys = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("App store: cannot read publish keys", e);
        }
        if (keys.has(id)) {
            return keys.get(id).getAsString();
        }
        if (Files.exists(CustomApps.folder().resolve(id).resolve(".store.json"))) {
            return null;
        }
        byte[] random = new byte[32];
        new java.security.SecureRandom().nextBytes(random);
        String key = HexFormat.of().formatHex(random);
        keys.addProperty(id, key);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, keys.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            BlockPhoneFabric.LOGGER.warn("App store: cannot save publish key", e);
        }
        return key;
    }

    /** Архив папки приложения: только разрешённые файлы, пути через «/». */
    static byte[] zipFolder(Path folder) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bytes); Stream<Path> walk = Files.walk(folder, 6)) {
            for (Path file : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = folder.relativize(file).toString().replace('\\', '/');
                String lower = rel.toLowerCase(Locale.ROOT);
                int dot = lower.lastIndexOf('.');
                if (dot < 0 || !ALLOWED.contains(lower.substring(dot)) || rel.startsWith(".")) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(rel));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        if (bytes.size() > MAX_PUBLISH) {
            throw new IOException("the app is too big to publish (max 10 MB)");
        }
        return bytes.toByteArray();
    }

    // --- Оценки и отзывы (только официальный каталог) ---

    private static boolean debugReviews;

    /** Отзывы есть только у приложений официального каталога. */
    public static boolean reviewable(Entry entry) {
        return debugReviews || OFFICIAL.equals(entry.source()) && !PUBLISH_URL.isEmpty();
    }

    /** DevShots: выдуманные оценки и отзывы для снимков. */
    public static void debugFakeReviews() {
        debugReviews = true;
        java.util.Map<String, Rating> fake = new java.util.HashMap<>();
        long now = System.currentTimeMillis();
        for (Entry e : entries) {
            fake.put(e.id(), new Rating(3.5 + (Math.abs(e.id().hashCode()) % 15) / 10.0, 3 + Math.abs(e.id().hashCode()) % 40));
            REVIEWS.put(e.id(), List.of(
                    new Review("1", "Steve", 5, "Отличное приложение, играю каждый день!", e.version(), now - 3_600_000L),
                    new Review("2", "Alex", 4, "Хорошо, но хотелось бы больше уровней.", e.version(), now - 86_400_000L * 2),
                    new Review("3", "Notch", 3, "", e.version(), now - 86_400_000L * 9)));
        }
        ratings = fake;
    }

    /** DevShots: показать ход установки (−1 — убрать). */
    public static void debugProgress(String id, float value) {
        if (value < 0) {
            PROGRESS.remove(id);
        } else {
            PROGRESS.put(id, value);
        }
    }

    /** Файл папки reviews/ репозитория официального каталога (с меткой времени — мимо кэша raw.githubusercontent). */
    private static String reviewsUrl(String file) {
        return OFFICIAL.substring(0, OFFICIAL.lastIndexOf('/') + 1) + "reviews/" + file + "?t=" + System.currentTimeMillis() / 60_000;
    }

    public static @Nullable Rating rating(String id) {
        return ratings.get(id);
    }

    /** Отзывы приложения (null — ещё грузятся; первый вызов начинает загрузку). */
    public static @Nullable List<Review> reviews(String id) {
        List<Review> list = REVIEWS.get(id);
        if (list == null && BUSY.putIfAbsent("reviews:" + id, "") == null) {
            ClientFiles.IO.execute(() -> {
                List<Review> loaded = new ArrayList<>();
                try {
                    JsonObject root = JsonParser.parseString(new String(fetch(reviewsUrl(id + ".json"), 2L << 20),
                            StandardCharsets.UTF_8)).getAsJsonObject();
                    for (JsonElement el : root.getAsJsonArray("reviews")) {
                        JsonObject r = el.getAsJsonObject();
                        loaded.add(new Review(str(r, "uuid", ""), str(r, "player", "?"), Math.max(1, Math.min(5, r.get("stars").getAsInt())),
                                str(r, "text", ""), str(r, "version", ""), r.has("time") ? r.get("time").getAsLong() : 0));
                    }
                } catch (Exception ignored) {
                    // 404 — отзывов нет
                }
                loaded.sort(Comparator.comparingLong(Review::time).reversed());
                Minecraft.getInstance().execute(() -> {
                    REVIEWS.put(id, loaded);
                    BUSY.remove("reviews:" + id);
                });
            });
        }
        return list;
    }

    /** Свой отзыв в загруженном списке или null. */
    public static @Nullable Review myReview(String id) {
        List<Review> list = REVIEWS.get(id);
        String me = Minecraft.getInstance().getUser().getProfileId().toString().replace("-", "");
        if (list != null) {
            for (Review r : list) {
                if (r.uuid().replace("-", "").equalsIgnoreCase(me)) {
                    return r;
                }
            }
        }
        return null;
    }

    public static boolean sendingReview(String id) {
        return BUSY.containsKey("review:" + id);
    }

    /**
     * Отправить оценку (1–5) и текст. Кто пишет, подтверждает сервер Mojang: игра «входит» на выдуманный сервер
     * (как при входе на любой сервер — пароль и токен никуда не уходят), приёмник проверяет это у Mojang. Поэтому
     * отзыв может оставить только лицензионный аккаунт, один на приложение (новый заменяет старый).
     * done(null) — принято, иначе текст ошибки. На потоке рендера.
     */
    public static void sendReview(Entry entry, int stars, String text, Consumer<String> done) {
        String id = entry.id();
        if (BUSY.putIfAbsent("review:" + id, "") != null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        var user = mc.getUser();
        String player = user.getName();
        java.util.UUID profile = user.getProfileId();
        String token = user.getAccessToken();
        String version = installed(id) != null ? installed(id) : entry.version();
        ClientFiles.IO.execute(() -> {
            String error = null;
            try {
                byte[] random = new byte[16];
                new java.security.SecureRandom().nextBytes(random);
                String serverId = HexFormat.of().formatHex(random);
                try {
                    mc.services().sessionService().joinServer(profile, token, serverId);
                } catch (Exception e) {
                    throw new IOException("offline");
                }
                JsonObject body = new JsonObject();
                body.addProperty("app", id);
                body.addProperty("stars", stars);
                body.addProperty("text", text);
                body.addProperty("player", player);
                body.addProperty("serverId", serverId);
                body.addProperty("version", version);
                HttpRequest request = HttpRequest.newBuilder(URI.create(PUBLISH_URL + "/review")).timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    String message = response.body();
                    try {
                        message = JsonParser.parseString(message).getAsJsonObject().get("error").getAsString();
                    } catch (RuntimeException ignored) {
                        // как есть
                    }
                    error = response.statusCode() == 404 || message.contains("author key") ? "not_configured" : message;
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            String result = error;
            mc.execute(() -> {
                BUSY.remove("review:" + id);
                if (result == null) {
                    // Сразу показать свой отзыв (файл на GitHub обновится через минуту-другую).
                    List<Review> list = new ArrayList<>(REVIEWS.getOrDefault(id, List.of()));
                    String me = profile.toString().replace("-", "");
                    list.removeIf(r -> r.uuid().replace("-", "").equalsIgnoreCase(me));
                    list.add(0, new Review(me, player, stars, text, version, System.currentTimeMillis()));
                    REVIEWS.put(id, list);
                    double sum = 0;
                    for (Review r : list) {
                        sum += r.stars();
                    }
                    java.util.Map<String, Rating> copy = new java.util.HashMap<>(ratings);
                    copy.put(id, new Rating(sum / list.size(), list.size()));
                    ratings = copy;
                }
                done.accept(result);
            });
        });
    }

    /** Удалить установленное приложение (папку blockphone/apps/&lt;id&gt;). */
    public static void uninstall(String id) {
        if (!id.matches("[a-z0-9_-]{1,32}")) {
            return;
        }
        try {
            deleteTree(CustomApps.folder().resolve(id));
        } catch (IOException e) {
            BlockPhoneFabric.LOGGER.warn("App store: removing {} failed", id, e);
        }
        CustomApps.scan(true);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** Иконка из каталога → файл в кэше (скачивается один раз) или null, пока качается. */
    public static @Nullable Path icon(Entry entry) {
        if (entry.icon() == null) {
            return null;
        }
        try {
            String name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(entry.icon().getBytes(StandardCharsets.UTF_8)));
            Path file = ClientFiles.root().resolve("cache").resolve("store").resolve(name + ".png");
            if (Files.exists(file)) {
                return file;
            }
            if (BUSY.putIfAbsent("icon:" + name, "") == null) {
                ClientFiles.IO.execute(() -> {
                    try {
                        byte[] png = fetch(entry.icon(), 256 * 1024);
                        Files.createDirectories(file.getParent());
                        Files.write(file, png);
                    } catch (Exception ignored) {
                        // нет иконки — будет буква
                    }
                });
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private AppStore() {
    }
}
