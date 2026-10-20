package dev.blockphone.common.apps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Серверная часть клиентского приложения (файл {@code <id>.json} в папке apps сервера). Даёт приложению данные
 * ("variables" — шаблоны с {player} и %placeholderapi%) и действия ("functions" — проверка полей, команды, ответ).
 * Логику на Java можно подключить через {@link BlockPhoneApps#handler}.
 *
 * @param id         id клиентского приложения
 * @param permission право, без которого серверная часть не отвечает (null — всем)
 * @param variables  имя → шаблон
 * @param functions  имя → описание функции
 */
public record ServerPart(String id, String permission, Map<String, String> variables, Map<String, JsonObject> functions) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    public record Loaded(List<ServerPart> parts, List<String> errors) {
    }

    public static ServerPart parse(String text, String fallbackId) throws AppLoader.AppException {
        JsonObject root;
        try {
            root = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new AppLoader.AppException("bad JSON: " + e.getMessage());
        }
        String id = (root.has("id") ? root.get("id").getAsString() : fallbackId).toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            throw new AppLoader.AppException("id must be 1-32 chars of a-z, 0-9, _ and -");
        }
        String permission = root.has("permission") ? root.get("permission").getAsString().strip() : "";
        Map<String, String> variables = new LinkedHashMap<>();
        if (root.has("variables")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("variables").entrySet()) {
                variables.put(e.getKey(), e.getValue().getAsString());
            }
        }
        Map<String, JsonObject> functions = new LinkedHashMap<>();
        if (root.has("functions")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("functions").entrySet()) {
                if (!e.getValue().isJsonObject()) {
                    throw new AppLoader.AppException("function \"" + e.getKey() + "\" must be an object");
                }
                functions.put(e.getKey(), e.getValue().getAsJsonObject());
            }
        }
        return new ServerPart(id, permission.isEmpty() ? null : permission, Map.copyOf(variables), Map.copyOf(functions));
    }

    /** Все серверные части из папки: {@code <id>.json} или {@code <id>/server.json}. */
    public static Loaded loadDirectory(Path dir) {
        List<ServerPart> parts = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return new Loaded(parts, errors);
        }
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(entries::add);
        } catch (IOException e) {
            errors.add(dir + ": " + e.getMessage());
            return new Loaded(parts, errors);
        }
        entries.sort(null);
        for (Path entry : entries) {
            String file = entry.getFileName().toString();
            Path json;
            String fallbackId;
            if (Files.isDirectory(entry) && Files.isRegularFile(entry.resolve("server.json"))) {
                json = entry.resolve("server.json");
                fallbackId = file;
            } else if (Files.isRegularFile(entry) && file.toLowerCase(Locale.ROOT).endsWith(".json")) {
                json = entry;
                fallbackId = file.substring(0, file.length() - 5);
            } else {
                continue;
            }
            try {
                ServerPart part = parse(Files.readString(json, StandardCharsets.UTF_8), fallbackId);
                if (parts.stream().anyMatch(p -> p.id().equals(part.id()))) {
                    errors.add(file + ": id \"" + part.id() + "\" already used");
                } else {
                    parts.add(part);
                }
            } catch (IOException | AppLoader.AppException | RuntimeException e) {
                errors.add(file + ": " + e.getMessage());
            }
        }
        return new Loaded(parts, errors);
    }
}
