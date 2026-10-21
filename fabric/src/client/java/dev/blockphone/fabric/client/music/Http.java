package dev.blockphone.fabric.client.music;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.StringJoiner;

/** Простые HTTP-запросы для музыкальных сервисов (HttpURLConnection — есть в любой Java). Вызывать не из потока рендера. */
public final class Http {
    public record Response(int code, String body) {
        public boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    /** GET с собственными тайм-аутами (для быстрых сервисов — не ждать по 30 секунд). */
    public static Response get(String url, Map<String, String> headers, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = open("GET", url, headers);
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : new String(in.readNBytes(4 * 1024 * 1024), StandardCharsets.UTF_8);
            return new Response(code, body);
        } finally {
            c.disconnect();
        }
    }

    public static Response request(String method, String url, Map<String, String> headers, String formBody) throws IOException {
        return request(method, url, headers, formBody, "application/x-www-form-urlencoded");
    }

    public static Response request(String method, String url, Map<String, String> headers, String formBody, String contentType)
            throws IOException {
        HttpURLConnection c = open(method, url, headers);
        if (formBody != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            try (OutputStream out = c.getOutputStream()) {
                out.write(formBody.getBytes(StandardCharsets.UTF_8));
            }
        } else if (method.equals("PUT") || method.equals("POST")) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
        }
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : new String(in.readNBytes(16 * 1024 * 1024), StandardCharsets.UTF_8);
            return new Response(code, body);
        } finally {
            c.disconnect();
        }
    }

    /**
     * Итог скачивания.
     *
     * @param code     HTTP-код последнего ответа
     * @param bytes    сколько байт записано (0 — сервер ничего не прислал)
     * @param type     Content-Type ответа ("" — нет)
     * @param finalUrl адрес после всех перенаправлений (для журнала)
     */
    public record Download(int code, long bytes, String type, String finalUrl) {
        public boolean ok() {
            return code >= 200 && code < 300 && bytes > 0;
        }

        /** Коротко для журнала: код, тип, размер и сервер. */
        public String describe() {
            String host;
            try {
                host = URI.create(finalUrl).getHost();
            } catch (IllegalArgumentException e) {
                host = "?";
            }
            return "HTTP " + code + ", " + (type.isEmpty() ? "no type" : type) + ", " + bytes + " bytes from " + host;
        }
    }

    /**
     * Скачать в файл (через временный). Перенаправления проходятся вручную (до 5), в том числе https↔http — сама Java
     * между протоколами не переходит и отдаёт пустой ответ. При переходе на другой сервер заголовок Authorization не
     * передаётся (чтобы токен не ушёл чужому серверу). Файл появляется, только если что-то скачано.
     */
    public static Download download(String url, Map<String, String> headers, Path target, long maxBytes) throws IOException {
        String current = url;
        Map<String, String> currentHeaders = headers;
        for (int hop = 0; hop <= 5; hop++) {
            HttpURLConnection c = open("GET", current, currentHeaders);
            c.setInstanceFollowRedirects(false);
            try {
                int code = c.getResponseCode();
                String type = c.getContentType() == null ? "" : c.getContentType();
                if (code >= 300 && code < 400) {
                    String location = c.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        return new Download(code, 0, type, current);
                    }
                    URI next = URI.create(current).resolve(location.strip());
                    if (!next.getHost().equalsIgnoreCase(URI.create(current).getHost()) && currentHeaders.containsKey("Authorization")) {
                        Map<String, String> without = new java.util.HashMap<>(currentHeaders);
                        without.remove("Authorization");
                        currentHeaders = without;
                    }
                    current = next.toString();
                    continue;
                }
                if (code >= 400) {
                    return new Download(code, 0, type, current);
                }
                Files.createDirectories(target.getParent());
                // Своё имя у каждой загрузки: один трек могут качать сразу два потока (проигрывание и предзагрузка).
                Path tmp = target.resolveSibling(target.getFileName() + "." + Thread.currentThread().threadId() + "-"
                        + System.nanoTime() + ".part");
                long total = 0;
                // Сервер может прислать сжатое (gzip), хотя мы не просили — распаковываем.
                boolean gzip = "gzip".equalsIgnoreCase(c.getContentEncoding());
                try {
                    try (InputStream raw = c.getInputStream();
                         InputStream in = gzip ? new java.util.zip.GZIPInputStream(raw) : raw;
                         OutputStream out = Files.newOutputStream(tmp)) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buffer)) > 0) {
                            total += n;
                            if (total > maxBytes) {
                                throw new IOException("File too large");
                            }
                            out.write(buffer, 0, n);
                        }
                    }
                    if (total > 0) {
                        try {
                            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        } catch (IOException e) {
                            // Тот же файл в этот момент положил другой поток (Windows не даёт заменить) — берём его.
                            if (!Files.isRegularFile(target) || Files.size(target) == 0) {
                                throw e;
                            }
                        }
                    }
                } finally {
                    Files.deleteIfExists(tmp);
                }
                return new Download(code, total, type, current);
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static HttpURLConnection open(String method, String url, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "BlockPhone");
        headers.forEach(c::setRequestProperty);
        return c;
    }

    /** Форма «a=1&b=2» с URL-кодированием. */
    public static String form(Map<String, String> fields) {
        StringJoiner joiner = new StringJoiner("&");
        fields.forEach((k, v) -> joiner.add(encode(k) + "=" + encode(v)));
        return joiner.toString();
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Http() {
    }
}
