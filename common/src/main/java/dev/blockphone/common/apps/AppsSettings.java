package dev.blockphone.common.apps;

import java.util.ArrayList;
import java.util.List;

/**
 * Политика сервера для клиентских приложений игроков (поля открыты — для Gson; Paper заполняет из config.yml).
 * Записи списков: uid приложения ("uid:bp…" — уникален, не зависит от названия и id), id приложения ("bank") или хэш
 * его app.json ("sha256:…") — всё видно игроку в «Настройки → Свои приложения» и в «Магазине».
 */
public final class AppsSettings {
    /** blacklist — работают все, кроме {@link #blacklist}; whitelist — только {@link #whitelist}; none — никакие. */
    public String mode = "blacklist";
    public List<String> blacklist = new ArrayList<>();
    public List<String> whitelist = new ArrayList<>();

    public AppsSettings fixNulls() {
        mode = mode == null ? "blacklist" : mode.toLowerCase(java.util.Locale.ROOT);
        blacklist = blacklist == null ? new ArrayList<>() : blacklist;
        whitelist = whitelist == null ? new ArrayList<>() : whitelist;
        return this;
    }

    /** Разрешено ли приложение (одинаково считают клиент и сервер). */
    public static boolean allowed(String mode, List<String> blacklist, List<String> whitelist, String appId, String sha256) {
        return allowed(mode, blacklist, whitelist, appId, "", sha256);
    }

    /**
     * Записи списков: "uid:bp…" (или просто uid) — постоянный уникальный id приложения из магазина (рекомендуется:
     * у разных приложений с одинаковым id/названием он разный); "id:snake" (или просто snake) — id из app.json;
     * "sha256:…" — конкретная версия.
     */
    public static boolean allowed(String mode, List<String> blacklist, List<String> whitelist, String appId, String uid, String sha256) {
        return switch (mode) {
            case "none" -> false;
            case "whitelist" -> listed(whitelist, appId, uid, sha256);
            default -> !listed(blacklist, appId, uid, sha256);
        };
    }

    private static boolean listed(List<String> list, String appId, String uid, String sha256) {
        for (String entry : list) {
            String e = entry.strip().toLowerCase(java.util.Locale.ROOT);
            if (e.startsWith("uid:")) {
                if (uid != null && !uid.isEmpty() && e.substring(4).equals(uid)) {
                    return true;
                }
            } else if (e.startsWith("id:")) {
                if (e.substring(3).equals(appId)) {
                    return true;
                }
            } else if (e.equals(appId) || (uid != null && !uid.isEmpty() && e.equals(uid))
                    || (sha256 != null && e.equals("sha256:" + sha256))) {
                return true;
            }
        }
        return false;
    }
}
