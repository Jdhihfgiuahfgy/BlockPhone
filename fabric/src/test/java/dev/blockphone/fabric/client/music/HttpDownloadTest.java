package dev.blockphone.fabric.client.music;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Скачивание: перенаправления на другой сервер, токен не уходит чужим, пустой ответ — не файл. */
class HttpDownloadTest {
    private static final byte[] AUDIO = {(byte) 0xFF, (byte) 0xFB, 1, 2, 3, 4};

    @TempDir
    Path dir;
    HttpServer first;
    HttpServer second;
    final AtomicReference<String> authSeenBySecond = new AtomicReference<>("none");

    @BeforeEach
    void setUp() throws Exception {
        second = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        second.createContext("/file", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            authSeenBySecond.set(auth == null ? "none" : auth);
            ex.getResponseHeaders().add("Content-Type", "audio/mpeg");
            ex.sendResponseHeaders(200, AUDIO.length);
            ex.getResponseBody().write(AUDIO);
            ex.close();
        });
        second.createContext("/empty", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        second.start();
        first = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Перенаправление на «другой сервер»: localhost вместо 127.0.0.1.
        first.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://localhost:" + second.getAddress().getPort() + "/file");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        first.start();
    }

    @AfterEach
    void tearDown() {
        first.stop(0);
        second.stop(0);
    }

    @Test
    void followsRedirectAndDropsToken() throws Exception {
        Path target = dir.resolve("track.mp3");
        Http.Download d = Http.download("http://127.0.0.1:" + first.getAddress().getPort() + "/redirect",
                Map.of("Authorization", "OAuth secret"), target, 1_000_000);
        assertTrue(d.ok(), d.describe());
        assertArrayEquals(AUDIO, Files.readAllBytes(target));
        assertEquals("none", authSeenBySecond.get());
        assertTrue(d.describe().contains("audio/mpeg"));
    }

    @Test
    void emptyResponseIsNotAFile() throws Exception {
        Path target = dir.resolve("empty.mp3");
        Http.Download d = Http.download("http://127.0.0.1:" + second.getAddress().getPort() + "/empty", Map.of(), target, 1_000_000);
        assertFalse(d.ok());
        assertEquals(0, d.bytes());
        assertFalse(Files.exists(target));
        assertNull(null);
    }

    /** Один трек качают сразу несколько потоков (проигрывание и предзагрузка) — временные файлы не мешают друг другу. */
    @Test
    void parallelDownloadsOfOneFile() throws Exception {
        Path target = dir.resolve("same.mp3");
        String url = "http://127.0.0.1:" + second.getAddress().getPort() + "/file";
        java.util.List<Thread> threads = new java.util.ArrayList<>();
        java.util.List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < 6; i++) {
            Thread t = new Thread(() -> {
                try {
                    Http.download(url, Map.of(), target, 1_000_000);
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(errors.isEmpty(), errors.toString());
        assertArrayEquals(AUDIO, Files.readAllBytes(target));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "no .part files left");
        }
    }
}
