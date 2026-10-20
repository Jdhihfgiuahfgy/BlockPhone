package dev.blockphone.common.apps;

import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Поля ввода: проверка и очистка значения. Одни правила и на телефоне (сразу показать ошибку), и на сервере
 * (сервер не доверяет телефону и проверяет заново по своему описанию функции).
 * Описание поля: {"kind": "text|number|player", "required": true, "max": 32 (длина текста или наибольшее число), "min": 1}.
 */
public final class InputRules {
    public static final int MAX_INPUT = 256;
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,15}([.,]\\d{1,8})?");
    private static final Pattern PLAYER = Pattern.compile("\\.?[A-Za-z0-9_]{1,16}");

    /** null — значение подходит, иначе ключ ошибки телефона ("@app.error.number" и т. п.). */
    public static String validate(JsonObject def, String raw) {
        String value = clean(raw);
        boolean required = def.has("required") && def.get("required").getAsBoolean();
        if (value.isEmpty()) {
            return required ? "@app.error.required" : null;
        }
        String kind = kind(def);
        int maxLength = def.has("max") && "text".equals(kind) ? Math.min(MAX_INPUT, def.get("max").getAsInt()) : MAX_INPUT;
        if (value.length() > maxLength) {
            return "@app.error.too_long";
        }
        switch (kind) {
            case "number" -> {
                if (!NUMBER.matcher(value).matches()) {
                    return "@app.error.number";
                }
                BigDecimal n = new BigDecimal(value.replace(',', '.'));
                if (def.has("min") && n.compareTo(def.get("min").getAsBigDecimal()) < 0) {
                    return "@app.error.min";
                }
                if (def.has("max") && n.compareTo(def.get("max").getAsBigDecimal()) > 0) {
                    return "@app.error.max";
                }
            }
            case "player" -> {
                if (!PLAYER.matcher(value).matches()) {
                    return "@app.error.player";
                }
            }
            default -> {
            }
        }
        return null;
    }

    /** Значение для подстановки: без управляющих символов и «§», числа — с точкой. */
    public static String normalize(JsonObject def, String raw) {
        String value = clean(raw);
        return "number".equals(kind(def)) ? value.replace(',', '.') : value;
    }

    public static String kind(JsonObject def) {
        return def.has("kind") ? def.get("kind").getAsString() : "text";
    }

    /** Без переводов строк, управляющих символов и «§», не длиннее {@link #MAX_INPUT}. */
    public static String clean(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() <= MAX_INPUT; i++) {
            char c = raw.charAt(i);
            if (c >= ' ' && c != '§' && c != 0x7F) {
                sb.append(c);
            } else if (c == '\n' || c == '\t') {
                sb.append(' ');
            }
        }
        return sb.toString().strip();
    }

    private InputRules() {
    }
}
