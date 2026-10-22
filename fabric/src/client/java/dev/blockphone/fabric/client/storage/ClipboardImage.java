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

    /**
     * Чтение буфера в Windows. Буфер бывает занят другой программой — несколько попыток. Порядок: файлы (проводник) →
     * PNG (браузеры, Discord, Telegram) → картинка (Bitmap/DIB: скриншоты, Paint) → текст-путь к файлу («Копировать как
     * путь»). Сначала строка FORMATS (для журнала). Вывод — UTF-8 без BOM; двойных кавычек в скрипте нет (аргумент процесса).
     */
    private static final String WINDOWS_PASTE = String.join("; ",
            "[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false",
            "Add-Type -AssemblyName System.Windows.Forms, System.Drawing",
            "$d = $null",
            "for ($i = 0; $i -lt 8 -and -not $d; $i++) { try { $d = [System.Windows.Forms.Clipboard]::GetDataObject() }"
                    + " catch { Start-Sleep -Milliseconds 120 } }",
            "if (-not $d) { 'FORMATS:none'; exit }",
            "'FORMATS:' + ($d.GetFormats() -join ',')",
            "$out = $env:BLOCKPHONE_CLIP",
            "function o($p) { 'FILE64:' + [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes([string]$p)) }",
            "if ($d.GetDataPresent('FileDrop')) { foreach ($f in $d.GetData('FileDrop')) { (o $f) } }"
                    + " elseif ($d.GetDataPresent('PNG')) { $s = $d.GetData('PNG'); $fs = [System.IO.File]::Create($out);"
                    + " $s.CopyTo($fs); $fs.Close(); (o $out) }"
                    + " elseif ([System.Windows.Forms.Clipboard]::ContainsImage()) { $img = [System.Windows.Forms.Clipboard]::GetImage();"
                    + " if ($img) { $img.Save($out, [System.Drawing.Imaging.ImageFormat]::Png); $img.Dispose(); (o $out) } }"
                    + " elseif ($d.GetDataPresent('UnicodeText')) { $t = ([string]$d.GetData('UnicodeText')).Trim().Trim([char]34);"
                    + " if ($t.Length -lt 400 -and (Test-Path -LiteralPath $t -PathType Leaf)) { (o $t) } }");

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
                String formats = "";
                for (String line : output.split("\\R")) {
                    Path path = null;
                    int file = line.indexOf("FILE:");
                    int file64 = line.indexOf("FILE64:");
                    int uri = line.indexOf("URI:file:");
                    if (line.contains("FORMATS:")) {
                        formats = line.substring(line.indexOf("FORMATS:") + 8).strip();
                    } else if (file64 >= 0) {
                        path = Path.of(new String(java.util.Base64.getDecoder().decode(line.substring(file64 + 7).strip()),
                                java.nio.charset.StandardCharsets.UTF_8));
                    } else if (file >= 0) {
                        path = Path.of(line.substring(file + 5).strip());
                    } else if (uri >= 0) {
                        path = Path.of(java.net.URI.create(line.substring(uri + 4).strip()));
                    }
                    if (path != null && java.nio.file.Files.isRegularFile(path) && java.nio.file.Files.size(path) > 0) {
                        result.add(path);
                    }
                }
                if (result.isEmpty()) {
                    String shown = output.strip();
                    BlockPhoneFabric.LOGGER.info("Clipboard: nothing to attach (formats: {}); output: {}", formats,
                            shown.length() > 400 ? shown.substring(0, 400) : shown);
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
