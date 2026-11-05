package dev.blockphone.fabric.client.storage;

import dev.blockphone.fabric.BlockPhoneFabric;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * Шифрование секретов ключом Windows (DPAPI, «для текущего пользователя»): расшифровать файл можно только под той же
 * учётной записью Windows на этом компьютере — скопированный на другой компьютер файл бесполезен. JNA (и jna-platform с
 * {@code Crypt32Util}) есть в самой игре. На других системах шифрования нет ({@link #available()} = false).
 */
public final class SecretBox {
    /** Подпись зашифрованного файла. */
    private static final byte[] MAGIC = "BPSEC1\n".getBytes(StandardCharsets.US_ASCII);
    /** Дополнительная «соль» DPAPI: чужая программа того же пользователя не расшифрует, не зная её. */
    private static final byte[] ENTROPY = "BlockPhone secrets v1".getBytes(StandardCharsets.US_ASCII);
    private static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

    private static Boolean available;

    /** Можно ли шифровать (Windows и DPAPI отвечает). */
    public static synchronized boolean available() {
        if (available == null) {
            boolean ok = false;
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
                try {
                    byte[] probe = {1, 2, 3};
                    ok = Arrays.equals(probe, unprotect(protect(probe)));
                } catch (Throwable e) {
                    BlockPhoneFabric.LOGGER.warn("Windows data protection (DPAPI) is unavailable, secrets are stored as is", e);
                }
            }
            available = ok;
        }
        return available;
    }

    public static byte[] protect(byte[] data) {
        return com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(data, ENTROPY, CRYPTPROTECT_UI_FORBIDDEN, "BlockPhone", null);
    }

    public static byte[] unprotect(byte[] data) {
        return com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(data, ENTROPY, CRYPTPROTECT_UI_FORBIDDEN, null);
    }

    /** Записать зашифрованным (через временный файл — при сбое старый файл цел). */
    public static void write(Path file, byte[] data) throws IOException {
        byte[] sealed = protect(data);
        byte[] out = new byte[MAGIC.length + sealed.length];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(sealed, 0, out, MAGIC.length, sealed.length);
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, out);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Результат чтения: файла нет, прочитан или не расшифровывается (другой компьютер / пользователь Windows). */
    public record Read(@Nullable byte[] data, boolean unreadable) {
    }

    public static Read read(Path file) {
        if (!Files.exists(file)) {
            return new Read(null, false);
        }
        try {
            byte[] all = Files.readAllBytes(file);
            if (all.length < MAGIC.length || !Arrays.equals(Arrays.copyOf(all, MAGIC.length), MAGIC)) {
                return new Read(null, true);
            }
            return new Read(unprotect(Arrays.copyOfRange(all, MAGIC.length, all.length)), false);
        } catch (Throwable e) {
            BlockPhoneFabric.LOGGER.warn("Cannot decrypt {} (made on another computer or Windows user?)", file, e);
            return new Read(null, true);
        }
    }

    /** Отложить нечитаемый файл в сторону (не удалять — вдруг вернутся на тот компьютер). */
    public static void moveAside(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".unreadable-" + System.currentTimeMillis()));
        } catch (IOException e) {
            BlockPhoneFabric.LOGGER.warn("Cannot move {} aside", file, e);
        }
    }

    private SecretBox() {
    }
}
