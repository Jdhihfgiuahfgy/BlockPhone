package dev.blockphone.fabric.client.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Кодек голосовых: сжатие в 4 раза и качество, достаточное для речи. */
class AdpcmTest {

    private static short[] sine(int rate, double hz, int samples, int amplitude) {
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            pcm[i] = (short) Math.round(amplitude * Math.sin(2 * Math.PI * hz * i / rate));
        }
        return pcm;
    }

    @Test
    void roundTripKeepsSignal() {
        short[] pcm = sine(16_000, 440, 16_000, 12_000);
        byte[] encoded = Adpcm.encode(pcm);
        assertEquals(4 + 8_000, encoded.length, "4 бита на отсчёт");
        short[] decoded = Adpcm.decode(encoded);
        assertEquals(pcm.length, decoded.length);
        double signal = 0;
        double noise = 0;
        for (int i = 100; i < pcm.length; i++) {
            signal += (double) pcm[i] * pcm[i];
            double d = pcm[i] - decoded[i];
            noise += d * d;
        }
        double snr = 10 * Math.log10(signal / noise);
        assertTrue(snr > 20, "SNR " + snr + " dB");
    }

    @Test
    void oddLengthAndEmpty() {
        short[] pcm = {1000, -1000, 2000};
        assertEquals(3, Adpcm.decode(Adpcm.encode(pcm)).length);
        assertEquals(0, Adpcm.decode(Adpcm.encode(new short[0])).length);
        assertEquals(0, Adpcm.decode(new byte[2]).length);
    }

    @Test
    void resampleDownAndUp() {
        short[] pcm48 = sine(48_000, 300, 48_000, 10_000);
        short[] pcm16 = Adpcm.resample(pcm48, 48_000, 16_000);
        assertEquals(16_000, pcm16.length);
        // Частота сохранилась: отсчёт через четверть периода — около пика.
        int quarter = 16_000 / 300 / 4;
        assertTrue(Math.abs(pcm16[quarter]) > 8_000, "peak " + pcm16[quarter]);
        assertEquals(44_100, Adpcm.resample(pcm16, 16_000, 44_100).length, 2);
    }
}
