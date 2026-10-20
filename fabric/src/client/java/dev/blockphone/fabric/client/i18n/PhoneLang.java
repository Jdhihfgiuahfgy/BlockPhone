package dev.blockphone.fabric.client.i18n;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.config.PhoneSettings;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Переводы интерфейса телефона. В отличие от ванильных переводов, язык выбирается
 * в настройках телефона (или «как в игре»). Файлы те же: {@code assets/blockphone/lang/*.json}.
 */
public final class PhoneLang {
    public static final List<String> SUPPORTED = List.of("ru_ru", "en_us");
    private static final String FALLBACK = "en_us";

    private static final Map<String, Map<String, String>> CACHE = new HashMap<>();

    /** Перевод ключа на текущий язык телефона. Аргументы подставляются через {@link String#format}. */
    public static String tr(String key, Object... args) {
        // Ключ ошибки с сервера может нести аргумент: «photo.error.quota|50».
        int bar = key.indexOf('|');
        if (bar > 0 && args.length == 0) {
            return tr(key.substring(0, bar), key.substring(bar + 1));
        }
        String value = table(current()).get(key);
        if (value == null) {
            value = table(FALLBACK).getOrDefault(key, key);
        }
        return args.length == 0 ? value : String.format(value, args);
    }

    /** Код языка, который сейчас использует телефон. */
    public static String current() {
        String setting = PhoneSettings.get().language;
        if (SUPPORTED.contains(setting)) {
            return setting;
        }
        String game = Minecraft.getInstance().getLanguageManager().getSelected();
        return game != null && game.startsWith("ru") ? "ru_ru" : FALLBACK;
    }

    private static Map<String, String> table(String code) {
        return CACHE.computeIfAbsent(code, PhoneLang::load);
    }

    private static Map<String, String> load(String code) {
        Map<String, String> map = new HashMap<>();
        String path = "/assets/blockphone/lang/" + code + ".json";
        try (InputStream in = PhoneLang.class.getResourceAsStream(path)) {
            if (in == null) {
                return map;
            }
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            json.entrySet().forEach(e -> map.put(e.getKey(), e.getValue().getAsString()));
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("Failed to load phone language {}", code, e);
        }
        return map;
    }

    private PhoneLang() {
    }
}
