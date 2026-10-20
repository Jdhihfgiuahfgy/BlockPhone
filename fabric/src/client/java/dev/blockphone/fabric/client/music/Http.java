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

    public static Response request(String method, String url, Map<String, String> headers, String formBody) throws IOException {
        HttpURLConnection c = open(method, url, headers);
        if (formBody != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
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

    /** Скачать в файл (через временный). Возвращает HTTP-код. */
    public static int download(String url, Map<String, String> headers, Path target, long maxBytes) throws IOException {
        HttpURLConnection c = open("GET", url, headers);
        try {
            int code = c.getResponseCode();
            if (code >= 400) {
                return code;
            }
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > maxBytes) {
                        throw new IOException("File too large");
                    }
                    out.write(buffer, 0, n);
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return code;
        } finally {
            c.disconnect();
        }
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
