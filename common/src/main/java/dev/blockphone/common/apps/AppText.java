package dev.blockphone.common.apps;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Тексты приложений: перевод ({"ru_ru": ..., "en_us": ...}) и подстановки «{имя}». */
public final class AppText {
    private static final Pattern LANG = Pattern.compile("[a-z]{2}_[a-z]{2}|default");
    /** «{имя}» или «{имя|значение по умолчанию}». */
    private static final Pattern VAR = Pattern.compile("\\{([A-Za-z0-9_.-]{1,64})(?:\\|([^{}]{0,64}))?}");

    /** Объект-перевод: только ключи-языки. */
    public static boolean isLocalized(JsonObject o) {
        if (o.size() == 0) {
            return false;
        }
        for (String key : o.keySet()) {
            if (!LANG.matcher(key).matches() || !o.get(key).isJsonPrimitive()) {
                return false;
            }
        }
        return true;
    }

    /** Строка на языке: точный язык → тот же язык другой страны → default → en_us → первый. */
    public static String pick(JsonElement e, String language) {
        if (e == null || e.isJsonNull()) {
            return "";
        }
        if (e.isJsonPrimitive()) {
            return e.getAsString();
        }
        if (!e.isJsonObject()) {
            return "";
        }
        JsonObject o = e.getAsJsonObject();
        String lang = language == null ? "" : language.toLowerCase();
        if (o.has(lang)) {
            return o.get(lang).getAsString();
        }
        String prefix = lang.length() >= 2 ? lang.substring(0, 2) : lang;
        for (String key : o.keySet()) {
            if (key.startsWith(prefix + "_")) {
                return o.get(key).getAsString();
            }
        }
        for (String fallback : new String[]{"default", "en_us"}) {
            if (o.has(fallback)) {
                return o.get(fallback).getAsString();
            }
        }
        return o.keySet().isEmpty() ? "" : o.get(o.keySet().iterator().next()).getAsString();
    }

    /** Применить функцию к строке или к каждому переводу. */
    public static JsonElement map(JsonElement e, UnaryOperator<String> f) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            return new JsonPrimitive(f.apply(e.getAsString()));
        }
        if (e.isJsonObject() && isLocalized(e.getAsJsonObject())) {
            JsonObject copy = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                copy.addProperty(entry.getKey(), f.apply(entry.getValue().getAsString()));
            }
            return copy;
        }
        return e;
    }

    /** Заменить «{имя}» значениями; неизвестные остаются как есть. */
    public static String substitute(String text, Map<String, String> values) {
        if (text.indexOf('{') < 0 || values.isEmpty()) {
            return text;
        }
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = values.get(m.group(1));
            if (value == null) {
                value = m.group(2) != null ? m.group(2) : m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private AppText() {
    }
}
