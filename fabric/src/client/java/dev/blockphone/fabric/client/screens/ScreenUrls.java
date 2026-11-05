package dev.blockphone.fabric.client.screens;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ссылки на видеосервисы → их «плеер без сайта» (embed): нет шапки, комментариев, рекомендаций и части баннеров, видео
 * сразу на всю страницу. Сохраняется ссылка игрока как есть, подмена — только при загрузке во вкладку экрана.
 */
public final class ScreenUrls {
    private static final Pattern RUTUBE = Pattern.compile("^/(?:video|shorts)/(?:private/)?([0-9a-f]{32})");
    private static final Pattern VK = Pattern.compile("video(-?\\d+)_(\\d+)");
    private static final Pattern VIMEO = Pattern.compile("^/(\\d+)");
    private static final Pattern DAILYMOTION = Pattern.compile("^/video/([a-z0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TWITCH_VIDEO = Pattern.compile("^/videos/(\\d+)");
    private static final Pattern TWITCH_CHANNEL = Pattern.compile("^/([A-Za-z0-9_]{3,25})/?$");
    private static final Pattern KICK = Pattern.compile("^/([A-Za-z0-9_-]{3,30})/?$");
    private static final Pattern YT_SHORTS = Pattern.compile("^/shorts/([A-Za-z0-9_-]{6,})");

    /** Ссылка для вкладки экрана (не известный сервис — как есть). */
    public static String forScreen(String url) {
        try {
            URI u = URI.create(url.strip());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT).replaceFirst("^(www\\.|m\\.)", "");
            String path = u.getPath() == null ? "" : u.getPath();
            String query = u.getRawQuery() == null ? "" : u.getRawQuery();
            Matcher m;
            switch (host) {
                case "rutube.ru" -> {
                    if ((m = RUTUBE.matcher(path)).find()) {
                        // Закрытое видео — ключ доступа «p» нужен и плееру.
                        String p = param(query, "p");
                        return "https://rutube.ru/play/embed/" + m.group(1) + (p != null ? "?p=" + p : "");
                    }
                }
                case "vk.com", "vk.ru", "vkvideo.ru" -> {
                    if ((m = VK.matcher(path + "?" + query)).find()) {
                        return "https://vkvideo.ru/video_ext.php?oid=" + m.group(1) + "&id=" + m.group(2) + "&hd=2&autoplay=1";
                    }
                }
                case "vimeo.com" -> {
                    if ((m = VIMEO.matcher(path)).find()) {
                        return "https://player.vimeo.com/video/" + m.group(1) + "?autoplay=1";
                    }
                }
                case "dailymotion.com" -> {
                    if ((m = DAILYMOTION.matcher(path)).find()) {
                        return "https://www.dailymotion.com/embed/video/" + m.group(1) + "?autoplay=1";
                    }
                }
                case "dai.ly" -> {
                    if (path.length() > 1) {
                        return "https://www.dailymotion.com/embed/video/" + path.substring(1) + "?autoplay=1";
                    }
                }
                case "twitch.tv" -> {
                    if ((m = TWITCH_VIDEO.matcher(path)).find()) {
                        return "https://player.twitch.tv/?video=v" + m.group(1) + "&parent=player.twitch.tv&autoplay=true";
                    }
                    if ((m = TWITCH_CHANNEL.matcher(path)).find()) {
                        return "https://player.twitch.tv/?channel=" + m.group(1) + "&parent=player.twitch.tv&autoplay=true";
                    }
                }
                case "kick.com" -> {
                    if ((m = KICK.matcher(path)).find()) {
                        return "https://player.kick.com/" + m.group(1);
                    }
                }
                case "youtube.com" -> {
                    if ((m = YT_SHORTS.matcher(path)).find()) {
                        return "https://www.youtube.com/watch?v=" + m.group(1);
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException ignored) {
            // не ссылка — как есть
        }
        return url;
    }

    /** Id видео YouTube в ссылке (null — не YouTube или не видео). */
    public static String youtubeId(String url) {
        try {
            URI u = URI.create(url.strip());
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT).replaceFirst("^(www\\.|m\\.|music\\.)", "");
            String path = u.getPath() == null ? "" : u.getPath();
            if (host.equals("youtu.be") && path.length() > 1) {
                return path.substring(1).split("/")[0];
            }
            if (host.equals("youtube.com")) {
                Matcher m = Pattern.compile("^/(?:shorts|embed|live)/([A-Za-z0-9_-]{6,})").matcher(path);
                if (m.find()) {
                    return m.group(1);
                }
                return param(u.getRawQuery() == null ? "" : u.getRawQuery(), "v");
            }
        } catch (RuntimeException ignored) {
            // не ссылка
        }
        return null;
    }

    private static String param(String query, String name) {
        for (String part : query.split("&")) {
            if (part.startsWith(name + "=")) {
                return part.substring(name.length() + 1);
            }
        }
        return null;
    }

    private ScreenUrls() {
    }
}
