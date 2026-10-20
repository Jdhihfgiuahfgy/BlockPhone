package dev.blockphone.common.apps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Загрузка клиентских приложений: папка с {@code app.json} и {@code icon.png} или файл {@code <имя>.json}
 * (иконка — {@code <имя>.png} рядом). Ошибки не роняют остальные приложения — копятся в {@link Result#errors()}.
 * Действие, для которого приложение не объявило разрешение ({@link AppPermission}), — ошибка загрузки.
 */
public final class AppLoader {
    public static final int MAX_ICON_BYTES = 32 * 1024;
    public static final int MAX_JSON_CHARS = 200_000;
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    public record Result(List<AppDefinition> apps, List<String> errors) {
    }

    /** Ошибка в описании приложения (сообщение — для лога/админа). */
    public static final class AppException extends Exception {
        public AppException(String message) {
            super(message);
        }
    }

    /** Все приложения из папки (несуществующая папка — пусто). */
    public static Result loadDirectory(Path dir) {
        List<AppDefinition> apps = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return new Result(apps, errors);
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(entries::add);
        } catch (IOException e) {
            errors.add(dir + ": " + e.getMessage());
            return new Result(apps, errors);
        }
        entries.sort(null);
        Map<String, Boolean> seen = new HashMap<>();
        for (Path entry : entries) {
            String file = entry.getFileName().toString();
            Path json;
            Path icon;
            String fallbackId;
            if (Files.isDirectory(entry) && Files.isRegularFile(entry.resolve("app.json"))) {
                json = entry.resolve("app.json");
                icon = entry.resolve("icon.png");
                fallbackId = file;
            } else if (Files.isRegularFile(entry) && file.toLowerCase(Locale.ROOT).endsWith(".json")) {
                json = entry;
                fallbackId = file.substring(0, file.length() - 5);
                icon = entry.resolveSibling(fallbackId + ".png");
            } else {
                continue;
            }
            try {
                String text = Files.readString(json, StandardCharsets.UTF_8);
                byte[] iconBytes = Files.isRegularFile(icon) ? Files.readAllBytes(icon) : null;
                AppDefinition app = parse(text, iconBytes, fallbackId);
                if (seen.put(app.id(), true) != null) {
                    errors.add(file + ": id \"" + app.id() + "\" already used");
                    continue;
                }
                apps.add(app);
            } catch (IOException | AppException | RuntimeException e) {
                errors.add(file + ": " + e.getMessage());
            }
        }
        return new Result(apps, errors);
    }

    /** Разобрать описание приложения. */
    public static AppDefinition parse(String text, byte[] icon, String fallbackId) throws AppException {
        String sha256 = sha256(text);
        if (text.length() > MAX_JSON_CHARS) {
            throw new AppException("file is too big");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new AppException("bad JSON: " + e.getMessage());
        }
        String id = root.has("id") ? root.get("id").getAsString() : fallbackId;
        id = id.toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw new AppException("id must be 1-32 chars of a-z, 0-9, _ and -");
        }
        JsonElement name = root.has("name") ? root.get("name") : new com.google.gson.JsonPrimitive(id);
        int color = parseColor(root.has("color") ? root.get("color").getAsString() : "#4C8DF6");
        if (icon != null && (icon.length > MAX_ICON_BYTES || icon.length < 8 || (icon[0] & 0xFF) != 0x89 || icon[1] != 'P')) {
            throw new AppException("icon must be a PNG up to " + MAX_ICON_BYTES / 1024 + " KB");
        }
        java.util.EnumSet<AppPermission> permissions = java.util.EnumSet.noneOf(AppPermission.class);
        if (root.has("permissions")) {
            for (JsonElement p : root.getAsJsonArray("permissions")) {
                try {
                    permissions.add(AppPermission.parse(p.getAsString()));
                } catch (IllegalArgumentException e) {
                    throw new AppException("unknown permission \"" + p.getAsString() + "\" (storage, links, clipboard, commands, server)");
                }
            }
        }
        String version = root.has("version") ? root.get("version").getAsString() : "1.0";
        String author = root.has("author") ? root.get("author").getAsString() : "";
        int order = root.has("order") ? root.get("order").getAsInt() : 100;
        if (!root.has("pages") || !root.get("pages").isJsonObject() || root.getAsJsonObject("pages").size() == 0) {
            throw new AppException("\"pages\" must contain at least one page");
        }
        JsonObject pages = root.getAsJsonObject("pages").deepCopy();
        String start = root.has("start") ? root.get("start").getAsString() : pages.keySet().iterator().next();
        if (!pages.has(start)) {
            throw new AppException("start page \"" + start + "\" not found");
        }
        Map<String, JsonObject> actions = new LinkedHashMap<>();
        Map<String, Map<String, JsonObject>> inputs = new HashMap<>();
        for (Map.Entry<String, JsonElement> page : pages.entrySet()) {
            if (!page.getValue().isJsonObject()) {
                throw new AppException("page \"" + page.getKey() + "\" must be an object");
            }
            Map<String, JsonObject> pageInputs = new LinkedHashMap<>();
            walk(page.getKey(), page.getValue(), actions, pageInputs, new int[]{0});
            inputs.put(page.getKey(), pageInputs);
        }
        // Страница с данными сервера — нужно разрешение server.
        for (Map.Entry<String, JsonElement> page : pages.entrySet()) {
            JsonObject p = page.getValue().getAsJsonObject();
            if (p.has("server") && p.get("server").getAsBoolean() && !permissions.contains(AppPermission.SERVER)) {
                throw new AppException("page \"" + page.getKey() + "\" uses the server but permission \"server\" is not declared");
            }
        }
        for (Map.Entry<String, JsonObject> action : actions.entrySet()) {
            AppPermission needed = required(action.getValue());
            if (needed != null && !permissions.contains(needed)) {
                throw new AppException("action in page \"" + action.getKey().split(":")[0] + "\" needs permission \""
                        + needed.key() + "\"");
            }
        }
        // Ссылки на страницы должны существовать.
        for (Map.Entry<String, JsonObject> action : actions.entrySet()) {
            for (String key : new String[]{"open", "then"}) {
                JsonObject a = action.getValue();
                if (a.has(key) && a.get(key).isJsonPrimitive()) {
                    String target = a.get(key).getAsString();
                    if (!target.equals("back") && !target.equals("refresh") && !target.equals("close") && !pages.has(target)) {
                        throw new AppException("action in page \"" + action.getKey().split(":")[0] + "\" opens unknown page \""
                                + target + "\"");
                    }
                }
            }
        }
        return new AppDefinition(id, name, version, author, color, icon, java.util.Collections.unmodifiableSet(permissions), start, order,
                pages, actions, inputs, sha256);
    }

    /** Какое разрешение нужно действию (null — только переходы и сообщения). */
    public static AppPermission required(JsonObject action) {
        if (action.has("command") || action.has("commands")) {
            return AppPermission.COMMANDS;
        }
        if (action.has("url")) {
            return AppPermission.LINKS;
        }
        if (action.has("copy")) {
            return AppPermission.CLIPBOARD;
        }
        if (action.has("set") || action.has("add")) {
            return AppPermission.STORAGE;
        }
        if (action.has("server")) {
            return AppPermission.SERVER;
        }
        return null;
    }

    public static String sha256(String text) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Обойти страницу: пронумеровать действия ("_a" = "страница:n") и собрать поля ввода. */
    private static void walk(String page, JsonElement e, Map<String, JsonObject> actions, Map<String, JsonObject> inputs, int[] counter)
            throws AppException {
        if (e.isJsonArray()) {
            for (JsonElement child : e.getAsJsonArray()) {
                walk(page, child, actions, inputs, counter);
            }
            return;
        }
        if (!e.isJsonObject()) {
            return;
        }
        JsonObject o = e.getAsJsonObject();
        if (o.has("type") && "input".equals(o.get("type").getAsString())) {
            if (!o.has("id")) {
                throw new AppException("input in page \"" + page + "\" has no \"id\"");
            }
            inputs.put(o.get("id").getAsString(), o);
        }
        if (o.has("action") && o.get("action").isJsonObject()) {
            String id = page + ":" + counter[0]++;
            actions.put(id, o.getAsJsonObject("action"));
            o.addProperty("_a", id);
        }
        for (Map.Entry<String, JsonElement> child : o.entrySet()) {
            if (!child.getKey().equals("action") && (child.getValue().isJsonObject() || child.getValue().isJsonArray())) {
                walk(page, child.getValue(), actions, inputs, counter);
            }
        }
    }

    /** «#RRGGBB» или «#AARRGGBB» → ARGB (непрозрачный, если альфа не указана). */
    public static int parseColor(String value) {
        String hex = value.strip();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        try {
            long v = Long.parseLong(hex, 16);
            return hex.length() <= 6 ? (int) (0xFF000000L | v) : (int) v;
        } catch (NumberFormatException e) {
            return 0xFF4C8DF6;
        }
    }

    /** Для проверки и тестов: все элементы страницы в порядке обхода. */
    static List<JsonObject> elements(JsonObject page) {
        List<JsonObject> result = new ArrayList<>();
        JsonArray array = page.has("elements") && page.get("elements").isJsonArray() ? page.getAsJsonArray("elements") : new JsonArray();
        for (JsonElement e : array) {
            if (e.isJsonObject()) {
                result.add(e.getAsJsonObject());
            }
        }
        return result;
    }

    private AppLoader() {
    }
}
