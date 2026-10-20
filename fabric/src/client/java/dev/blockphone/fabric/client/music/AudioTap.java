package dev.blockphone.fabric.client.music;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Копия звучащей музыки (для звука в «кружочке» Telegram): пока запись идёт, {@link Playback} отдаёт сюда каждый записанный
 * в звуковую карту кусок — уже с громкостью, как его слышит игрок. Здесь он сводится в моно 48 кГц и кладётся по времени
 * от начала записи (пауза музыки — тишина, без сдвига).
 */
public final class AudioTap {
    public static final int RATE = 48_000;
    private static @Nullable AudioTap current;

    private final long startedAt = System.currentTimeMillis();
    private short[] buffer = new short[RATE * 4];
    private int cursor;
    private long lastFeed;

    /** Начать копить звук музыки. */
    public static synchronized void begin() {
        current = new AudioTap();
    }

    /** Закончить; возвращает накопленное (моно 48 кГц) или пустой массив. */
    public static synchronized short[] end() {
        AudioTap tap = current;
        current = null;
        return tap == null ? new short[0] : Arrays.copyOf(tap.buffer, tap.cursor);
    }

    /** Из потока проигрывания: samples — чередующиеся каналы, n — число значений. */
    static void feed(short[] samples, int n, int channels, int rate) {
        AudioTap tap = current;
        if (tap == null) {
            return;
        }
        synchronized (AudioTap.class) {
            if (current == tap) {
                tap.add(samples, n, channels, rate);
            }
        }
    }

    private void add(short[] samples, int n, int channels, int rate) {
        long now = System.currentTimeMillis();
        int expected = (int) ((now - startedAt) * RATE / 1000);
        // После паузы (или в начале) — встать по часам; пока музыка идёт подряд — писать подряд, без щелчков.
        if (now - lastFeed > 300 || expected - cursor > RATE / 2) {
            cursor = Math.max(cursor, expected - RATE / 4);
        }
        lastFeed = now;
        int frames = n / channels;
        int out = (int) ((long) frames * RATE / rate);
        if (cursor + out > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, cursor + out));
        }
        for (int i = 0; i < out; i++) {
            int src = (int) ((long) i * rate / RATE);
            int sum = 0;
            for (int c = 0; c < channels; c++) {
                sum += samples[src * channels + c];
            }
            buffer[cursor + i] = (short) (sum / channels);
        }
        cursor += out;
    }

    private AudioTap() {
    }
}
