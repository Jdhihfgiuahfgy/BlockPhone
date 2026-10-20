package dev.blockphone.fabric.client.telegram;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Голосовые Telegram: OGG Opus записывается и читается обратно (длина, тон), «волна» упаковывается по 5 бит. */
class OggOpusTest {
    private static short[] tone(int ms, double hz) {
        short[] pcm = new short[OggOpus.RATE * ms / 1000];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (Math.sin(2 * Math.PI * hz * i / OggOpus.RATE) * 8000);
        }
        return pcm;
    }

    @Test
    void roundTrip() throws Exception {
        short[] pcm = tone(2000, 440);
        byte[] ogg = OggOpus.encode(pcm);
        assertEquals("OggS", new String(ogg, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(ogg.length < 12_000, "size " + ogg.length); // ~24 кбит/с
        List<byte[]> packets = OggOpus.packets(ogg);
        assertTrue(new String(packets.get(0), 0, 8, java.nio.charset.StandardCharsets.US_ASCII).equals("OpusHead"));
        short[] back = OggOpus.decode(ogg);
        assertTrue(Math.abs(back.length - pcm.length) <= 320, "length " + back.length);
        // Тон 440 Гц: число переходов через ноль в середине ≈ 880 в секунду.
        int crossings = 0;
        for (int i = 4000; i < 20000; i++) {
            if ((back[i - 1] < 0) != (back[i] < 0)) {
                crossings++;
            }
        }
        assertTrue(crossings > 820 && crossings < 940, "crossings " + crossings);
        long energy = 0;
        for (int i = 4000; i < 20000; i++) {
            energy += (long) back[i] * back[i];
        }
        double rms = Math.sqrt(energy / 16000.0);
        assertTrue(rms > 3500 && rms < 7500, "rms " + rms);
    }

    @Test
    void waveformPacking() {
        short[] pcm = new short[1000];
        for (int i = 500; i < 1000; i++) {
            pcm[i] = (short) (i % 2 == 0 ? 20000 : -20000);
        }
        byte[] packed = OggOpus.waveform(pcm);
        assertEquals(63, packed.length);
        int[] values = OggOpus.unpackWaveform(packed);
        assertEquals(0, values[10]);
        assertEquals(31, values[80]);
        assertArrayEquals(values, OggOpus.unpackWaveform(packed));
    }
}
