package dev.blockphone.fabric.client.music.audio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Декодер OGG (stb_vorbis) на настоящей музыке Minecraft из кэша ассетов Loom.
 * Если кэша нет (чистая машина) — тест пропускается.
 */
class OggDecoderTest {
    @TempDir
    Path dir;

    private static Path minecraftOgg() throws Exception {
        Path assets = Path.of(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom", "assets");
        Path indexes = assets.resolve("indexes");
        Assumptions.assumeTrue(Files.isDirectory(indexes), "no Minecraft assets cache");
        Path index;
        try (Stream<Path> files = Files.list(indexes)) {
            index = files.max(Comparator.naturalOrder()).orElseThrow();
        }
        JsonObject objects;
        try (Reader reader = Files.newBufferedReader(index)) {
            objects = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("objects");
        }
        JsonObject clark = objects.getAsJsonObject("minecraft/sounds/music/game/clark.ogg");
        Assumptions.assumeTrue(clark != null, "no clark.ogg in index");
        String hash = clark.get("hash").getAsString();
        Path file = assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
        Assumptions.assumeTrue(Files.exists(file), "clark.ogg not downloaded");
        return file;
    }

    @Test
    void decodesAndSeeksMinecraftMusic() throws Exception {
        // Копия с русскими буквами в пути — проверка, что открытие не зависит от кодировки пути.
        Path copy = dir.resolve("Музыка - Clark.ogg");
        Files.copy(minecraftOgg(), copy);
        try (AudioDecoder decoder = Decoders.open(copy)) {
            assertTrue(decoder instanceof OggDecoder);
            assertEquals(2, decoder.channels());
            assertTrue(decoder.sampleRate() == 44100 || decoder.sampleRate() == 48000);
            assertTrue(decoder.durationMs() > 60_000, "duration " + decoder.durationMs());
            short[] buffer = new short[4096];
            int n = decoder.read(buffer);
            assertTrue(n > 0 && n % 2 == 0);
            assertTrue(decoder.seek(decoder.durationMs() - 500));
            int total = 0;
            while ((n = decoder.read(buffer)) > 0) {
                total += n;
            }
            // После перемотки на 0,5 с до конца осталось примерно 0,5 с звука.
            assertTrue(total < decoder.sampleRate() * 2 && total > 0, "samples after seek " + total);
        }
    }
}
