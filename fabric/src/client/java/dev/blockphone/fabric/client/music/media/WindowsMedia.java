package dev.blockphone.fabric.client.music.media;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Пульт любого плеера на компьютере через системные медиа Windows (System Media Transport Controls) — вместо Spotify
 * Web API: работает с приложением Spotify, Яндекс Музыкой, браузерами (YouTube, VK) и другими плеерами, без входа и
 * без Premium. Мод запускает PowerShell со скриптом {@code assets/blockphone/scripts/media.ps1}: тот раз в ~0,4 с пишет
 * JSON с тем, что играет (название, исполнитель, обложка, позиция, длительность, пауза, перемешивание, повтор), и
 * принимает команды (пауза, вперёд, назад, перемотка). Процесс живёт, пока UI или HUD смотрят на состояние
 * ({@link #watch()}), и закрывается через {@link #IDLE_STOP_MS} без взглядов. Только Windows 10/11.
 */
public final class WindowsMedia {
    private static final long IDLE_STOP_MS = 30_000;
    private static final long RESTART_DELAY_MS = 5_000;

    public enum Status { UNSUPPORTED, STOPPED, STARTING, RUNNING, ERROR }

    /**
     * Что играет. position/duration — мс; receivedAt — когда пришло состояние (для плавной позиции);
     * repeat — None | Track | List.
     */
    public record Playing(String app, String title, String artist, String album, boolean playing, long positionMs, long durationMs,
                          long receivedAt, boolean shuffle, String repeat, boolean canSeek, boolean canNext, boolean canPrev,
                          boolean canShuffle, boolean canRepeat, @Nullable Path cover) {
        /** Позиция с учётом времени после последнего состояния. */
        public long position() {
            long p = playing ? positionMs + System.currentTimeMillis() - receivedAt : positionMs;
            return durationMs > 0 ? Math.max(0, Math.min(durationMs, p)) : Math.max(0, p);
        }

        /** Название приложения для экрана: «Spotify», «Chrome», «Яндекс Музыка»… */
        public String appName() {
            String id = app.toLowerCase(Locale.ROOT);
            if (id.contains("spotify")) {
                return "Spotify";
            }
            if (id.contains("yandex") && id.contains("music")) {
                return "Яндекс Музыка";
            }
            if (id.contains("chrome")) {
                return "Chrome";
            }
            if (id.contains("msedge") || id.contains("edge")) {
                return "Edge";
            }
            if (id.contains("firefox")) {
                return "Firefox";
            }
            if (id.contains("opera")) {
                return "Opera";
            }
            if (id.contains("yandex")) {
                return "Яндекс Браузер";
            }
            if (id.contains("vlc")) {
                return "VLC";
            }
            if (id.contains("zunemusic") || id.contains("media")) {
                return "Windows Media";
            }
            String name = app.contains("!") ? app.substring(app.lastIndexOf('!') + 1) : app;
            name = name.contains("\\") ? name.substring(name.lastIndexOf('\\') + 1) : name;
            return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
        }
    }

    private static Status status;
    private static @Nullable Process process;
    private static @Nullable OutputStream commands;
    private static @Nullable Playing playing;
    private static boolean nothing;
    private static @Nullable String error;
    private static long errorAt;
    private static long watchedAt;
    private static long stoppedAt;
    private static String coverKey = "";
    private static @Nullable Path coverPath;

    public static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public static Status status() {
        if (status == null) {
            status = supported() ? Status.STOPPED : Status.UNSUPPORTED;
        }
        return status;
    }

    /** Что играет сейчас (null — ничего или мост не запущен). */
    public static @Nullable Playing playing() {
        return playing;
    }

    /** Мост работает, но ни одно приложение ничего не проигрывает. */
    public static boolean nothingPlaying() {
        return nothing;
    }

    /** Есть ли что-то, чем можно управлять (для клавиш музыки и HUD). */
    public static boolean active() {
        return playing != null;
    }

    public static @Nullable String recentError() {
        return error != null && System.currentTimeMillis() - errorAt < 6000 ? error : null;
    }

    /** UI или HUD показывает состояние: запустить мост, если он не запущен. */
    public static void watch() {
        watchedAt = System.currentTimeMillis();
        if (status() == Status.STOPPED || (status == Status.ERROR && watchedAt - stoppedAt > RESTART_DELAY_MS)) {
            start();
        }
    }

    /** Каждый тик игры: остановить мост, если на него давно не смотрят. */
    public static void tick() {
        if (process != null && System.currentTimeMillis() - watchedAt > IDLE_STOP_MS) {
            stop();
        }
    }

    private static void start() {
        Path script;
        try {
            script = ClientFiles.root().resolve("cache").resolve("media.ps1");
            Files.createDirectories(script.getParent());
            try (InputStream in = WindowsMedia.class.getResourceAsStream("/assets/blockphone/scripts/media.ps1")) {
                if (in == null) {
                    throw new IOException("media.ps1 not found in the mod");
                }
                Files.write(script, in.readAllBytes());
            }
        } catch (IOException e) {
            fail("script: " + e.getMessage());
            return;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", script.toAbsolutePath().toString());
            builder.redirectErrorStream(false);
            Process p = builder.start();
            process = p;
            commands = p.getOutputStream();
            status = Status.STARTING;
            Thread reader = new Thread(() -> read(p), "BlockPhone Windows Media");
            reader.setDaemon(true);
            reader.start();
            Thread errors = new Thread(() -> drain(p.getErrorStream()), "BlockPhone Windows Media errors");
            errors.setDaemon(true);
            errors.start();
        } catch (IOException e) {
            fail("powershell: " + e.getMessage());
        }
    }

    /** Остановить мост (закрыть stdin — скрипт выходит сам; на всякий случай — завершить процесс). */
    public static void stop() {
        Process p = process;
        process = null;
        OutputStream out = commands;
        commands = null;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // процесс уже закрыт
            }
        }
        if (p != null) {
            p.destroy();
        }
        playing = null;
        nothing = false;
        if (status != Status.UNSUPPORTED) {
            status = Status.STOPPED;
        }
    }

    private static void fail(String message) {
        BlockPhoneFabric.LOGGER.warn("Windows media bridge: {}", message);
        error = message;
        errorAt = System.currentTimeMillis();
        stoppedAt = errorAt;
        status = Status.ERROR;
        process = null;
        commands = null;
        playing = null;
    }

    private static void read(Process p) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String text = line.strip();
                if (text.startsWith("﻿")) {
                    text = text.substring(1);
                }
                if (!text.startsWith("{")) {
                    continue;
                }
                JsonObject json;
                try {
                    json = JsonParser.parseString(text).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                handle(json, p);
            }
        } catch (IOException ignored) {
            // процесс закрыт
        }
        Minecraft.getInstance().execute(() -> {
            if (process == p) {
                fail("bridge stopped (exit " + (p.isAlive() ? "?" : String.valueOf(p.exitValue())) + ")");
            }
        });
    }

    private static void drain(InputStream errors) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(errors, StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isBlank()) {
                    BlockPhoneFabric.LOGGER.debug("Windows media bridge: {}", line);
                }
            }
        } catch (IOException ignored) {
            // процесс закрыт
        }
    }

    /** Поток чтения: разобрать состояние, обложку сохранить в файл, остальное — в поток рендера. */
    private static void handle(JsonObject json, Process p) {
        String type = str(json, "type");
        if (type.equals("error")) {
            String message = str(json, "message");
            Minecraft.getInstance().execute(() -> {
                error = message;
                errorAt = System.currentTimeMillis();
            });
            return;
        }
        if (!type.equals("state")) {
            return;
        }
        long received = System.currentTimeMillis();
        if (!json.has("active") || !json.get("active").getAsBoolean()) {
            Minecraft.getInstance().execute(() -> {
                if (process == p) {
                    status = Status.RUNNING;
                    playing = null;
                    nothing = true;
                }
            });
            return;
        }
        String key = str(json, "key");
        Path cover = null;
        String thumb = str(json, "thumb");
        if (!thumb.isEmpty()) {
            try {
                byte[] bytes = java.util.Base64.getDecoder().decode(thumb);
                Path dir = ClientFiles.root().resolve("cache").resolve("media");
                Files.createDirectories(dir);
                cover = dir.resolve(Integer.toHexString(key.hashCode()) + "_" + bytes.length + ".img");
                if (!Files.exists(cover)) {
                    Files.write(cover, bytes);
                }
            } catch (IOException | IllegalArgumentException e) {
                cover = null;
            }
        }
        String state = str(json, "status");
        long position = json.has("position") ? json.get("position").getAsLong() : 0;
        long updated = json.has("updated") ? json.get("updated").getAsLong() : 0;
        long now = json.has("now") ? json.get("now").getAsLong() : 0;
        boolean isPlaying = state.equalsIgnoreCase("Playing");
        // Многие приложения обновляют позицию редко: досчитываем с момента их последнего обновления.
        if (isPlaying && updated > 0 && now > updated && now - updated < 6 * 3600_000L) {
            position += now - updated;
        }
        long pos = position;
        Path newCover = cover;
        Playing next = new Playing(str(json, "app"), str(json, "title"), str(json, "artist"), str(json, "album"), isPlaying, pos,
                json.has("duration") ? json.get("duration").getAsLong() : 0, received, bool(json, "shuffle"), str(json, "repeat"),
                bool(json, "canSeek"), bool(json, "canNext"), bool(json, "canPrev"), bool(json, "canShuffle"), bool(json, "canRepeat"),
                null);
        Minecraft.getInstance().execute(() -> {
            if (process != p) {
                return;
            }
            if (!key.equals(coverKey)) {
                coverKey = key;
                coverPath = null;
            }
            if (newCover != null) {
                coverPath = newCover;
            }
            WindowsMedia.status = Status.RUNNING;
            nothing = false;
            playing = new Playing(next.app(), next.title(), next.artist(), next.album(), next.playing(), next.positionMs(),
                    next.durationMs(), next.receivedAt(), next.shuffle(), next.repeat(), next.canSeek(), next.canNext(), next.canPrev(),
                    next.canShuffle(), next.canRepeat(), coverPath);
        });
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() && o.get(key).getAsBoolean();
    }

    // --- Команды ---

    private static void send(String command) {
        watch();
        OutputStream out = commands;
        if (out == null) {
            return;
        }
        try {
            out.write((command + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            fail("command: " + e.getMessage());
        }
    }

    public static void togglePlay() {
        Playing p = playing;
        if (p != null) {
            // Сразу показать новое состояние, не дожидаясь ответа.
            playing = new Playing(p.app(), p.title(), p.artist(), p.album(), !p.playing(), p.position(), p.durationMs(),
                    System.currentTimeMillis(), p.shuffle(), p.repeat(), p.canSeek(), p.canNext(), p.canPrev(), p.canShuffle(),
                    p.canRepeat(), p.cover());
        }
        send("toggle");
    }

    public static void next() {
        send("next");
    }

    public static void previous() {
        send("prev");
    }

    public static void seek(long ms) {
        Playing p = playing;
        if (p != null) {
            playing = new Playing(p.app(), p.title(), p.artist(), p.album(), p.playing(), Math.max(0, ms), p.durationMs(),
                    System.currentTimeMillis(), p.shuffle(), p.repeat(), p.canSeek(), p.canNext(), p.canPrev(), p.canShuffle(),
                    p.canRepeat(), p.cover());
        }
        send("seek " + Math.max(0, ms));
    }

    public static void toggleShuffle() {
        Playing p = playing;
        send("shuffle " + (p != null && p.shuffle() ? 0 : 1));
    }

    /** Повтор: нет → весь список → один трек → нет. */
    public static void cycleRepeat() {
        Playing p = playing;
        String repeat = p != null ? p.repeat() : "None";
        int next = switch (repeat) {
            case "List" -> 1;
            case "Track" -> 0;
            default -> 2;
        };
        send("repeat " + next);
    }

    private WindowsMedia() {
    }
}
