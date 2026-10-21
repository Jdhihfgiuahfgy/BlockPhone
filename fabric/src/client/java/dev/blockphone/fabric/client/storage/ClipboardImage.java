package dev.blockphone.fabric.client.storage;

import dev.blockphone.fabric.BlockPhoneFabric;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Картинка в буфер обмена системы. Minecraft запускает Java без графики AWT (headless), поэтому буфер заполняют
 * средства самой системы: Windows — PowerShell (картинка + сам файл, чтобы вставлялось и в чаты, и в папки),
 * macOS — osascript, Linux — wl-copy или xclip. Путь передаётся переменной окружения — не частью команды.
 */
public final class ClipboardImage {
    private static final String WINDOWS_SCRIPT = String.join("; ",
            "Add-Type -AssemblyName System.Windows.Forms, System.Drawing",
            "$p = $env:BLOCKPHONE_CLIP",
            "$img = [System.Drawing.Image]::FromFile($p)",
            "$data = New-Object System.Windows.Forms.DataObject",
            "$data.SetImage($img)",
            "$files = New-Object System.Collections.Specialized.StringCollection",
            "[void]$files.Add($p)",
            "$data.SetFileDropList($files)",
            "[System.Windows.Forms.Clipboard]::SetDataObject($data, $true)",
            "$img.Dispose()");

    /** Скопировать PNG (в фоне); done(true/false) — на потоке рендера. */
    public static void copy(Path png, Consumer<Boolean> done) {
        Path file = png.toAbsolutePath();
        ClientFiles.IO.execute(() -> {
            boolean ok = false;
            try {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                List<String> command;
                if (os.contains("win")) {
                    command = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-Command", WINDOWS_SCRIPT);
                } else if (os.contains("mac")) {
                    command = List.of("osascript", "-e",
                            "set the clipboard to (read (POSIX file (system attribute \"BLOCKPHONE_CLIP\")) as «class PNGf»)");
                } else if (System.getenv("WAYLAND_DISPLAY") != null) {
                    command = List.of("sh", "-c", "wl-copy --type image/png < \"$BLOCKPHONE_CLIP\"");
                } else {
                    command = List.of("sh", "-c", "xclip -selection clipboard -t image/png -i \"$BLOCKPHONE_CLIP\"");
                }
                ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
                builder.environment().put("BLOCKPHONE_CLIP", file.toString());
                Process process = builder.start();
                process.getOutputStream().close();
                byte[] output = process.getInputStream().readAllBytes();
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                } else {
                    ok = process.exitValue() == 0;
                }
                if (!ok) {
                    BlockPhoneFabric.LOGGER.warn("Copying image to clipboard failed: {}", new String(output).strip());
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Copying image to clipboard failed", e);
            }
            boolean result = ok;
            Minecraft.getInstance().execute(() -> done.accept(result));
        });
    }

    private static final String WINDOWS_PASTE = String.join("; ",
            "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
            "Add-Type -AssemblyName System.Windows.Forms, System.Drawing",
            "$files = [System.Windows.Forms.Clipboard]::GetFileDropList()",
            "if ($files.Count -gt 0) { foreach ($f in $files) { 'FILE:' + $f } }",
            "elseif ([System.Windows.Forms.Clipboard]::ContainsImage()) {"
                    + " $img = [System.Windows.Forms.Clipboard]::GetImage();"
                    + " $img.Save($env:BLOCKPHONE_CLIP, [System.Drawing.Imaging.ImageFormat]::Png); $img.Dispose();"
                    + " 'FILE:' + $env:BLOCKPHONE_CLIP }");

    /**
     * Что лежит в буфере обмена — файлы (скопированные в проводнике) или картинка (сохраняется во временный PNG).
     * В фоне; done(список путей, пустой — ничего подходящего) — на потоке рендера.
     */
    public static void paste(Consumer<List<Path>> done) {
        ClientFiles.IO.execute(() -> {
            List<Path> result = new java.util.ArrayList<>();
            try {
                Path dir = ClientFiles.root().resolve("cache").resolve("clipboard");
                java.nio.file.Files.createDirectories(dir);
                Path image = dir.resolve("clip_" + System.currentTimeMillis() + ".png").toAbsolutePath();
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                List<String> command;
                if (os.contains("win")) {
                    command = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-STA", "-Command", WINDOWS_PASTE);
                } else if (os.contains("mac")) {
                    command = List.of("osascript", "-e", "set f to open for access (POSIX file (system attribute \"BLOCKPHONE_CLIP\")) with write permission",
                            "-e", "write (the clipboard as «class PNGf») to f", "-e", "close access f",
                            "-e", "return \"FILE:\" & (system attribute \"BLOCKPHONE_CLIP\")");
                } else if (System.getenv("WAYLAND_DISPLAY") != null) {
                    command = List.of("sh", "-c", "if wl-paste --list-types | grep -q text/uri-list; then wl-paste --type text/uri-list | sed 's/^/URI:/';"
                            + " else wl-paste --type image/png > \"$BLOCKPHONE_CLIP\" && echo \"FILE:$BLOCKPHONE_CLIP\"; fi");
                } else {
                    command = List.of("sh", "-c", "if xclip -selection clipboard -t TARGETS -o | grep -q text/uri-list; then"
                            + " xclip -selection clipboard -t text/uri-list -o | sed 's/^/URI:/';"
                            + " else xclip -selection clipboard -t image/png -o > \"$BLOCKPHONE_CLIP\" && echo \"FILE:$BLOCKPHONE_CLIP\"; fi");
                }
                ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
                builder.environment().put("BLOCKPHONE_CLIP", image.toString());
                Process process = builder.start();
                process.getOutputStream().close();
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                for (String line : output.split("\\R")) {
                    line = line.strip();
                    Path path = null;
                    if (line.startsWith("FILE:")) {
                        path = Path.of(line.substring(5));
                    } else if (line.startsWith("URI:file:")) {
                        path = Path.of(java.net.URI.create(line.substring(4)));
                    }
                    if (path != null && java.nio.file.Files.isRegularFile(path) && java.nio.file.Files.size(path) > 0) {
                        result.add(path);
                    }
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Reading clipboard failed", e);
            }
            Minecraft.getInstance().execute(() -> done.accept(result));
        });
    }

    private ClipboardImage() {
    }
}
