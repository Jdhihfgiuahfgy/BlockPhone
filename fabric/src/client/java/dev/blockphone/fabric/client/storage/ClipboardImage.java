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

    private ClipboardImage() {
    }
}
