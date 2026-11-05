package dev.blockphone.fabric.client.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Секреты шифруются ключом Windows: в файле нет открытого текста, испорченный файл не читается. */
class SecretBoxTest {
    @TempDir
    Path dir;

    @Test
    void roundTripAndNoPlainText() throws Exception {
        assumeTrue(SecretBox.available(), "только Windows");
        byte[] secret = "{\"yandexToken\":\"secret-token-123\"}".getBytes(StandardCharsets.UTF_8);
        Path file = dir.resolve("accounts.dat");
        SecretBox.write(file, secret);
        String raw = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("secret-token-123"), "в файле не должно быть открытого текста");
        assertArrayEquals(secret, SecretBox.read(file).data());
    }

    @Test
    void tamperedFileIsUnreadable() throws Exception {
        assumeTrue(SecretBox.available(), "только Windows");
        Path file = dir.resolve("accounts.dat");
        SecretBox.write(file, new byte[]{1, 2, 3, 4});
        byte[] all = Files.readAllBytes(file);
        all[all.length - 5] ^= 0x55;
        Files.write(file, all);
        SecretBox.Read read = SecretBox.read(file);
        assertNull(read.data());
        assertTrue(read.unreadable());
        assertFalse(SecretBox.read(dir.resolve("missing.dat")).unreadable());
    }
}
