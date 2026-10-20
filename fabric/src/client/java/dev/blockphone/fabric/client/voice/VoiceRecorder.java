package dev.blockphone.fabric.client.voice;

import dev.blockphone.common.protocol.data.VoiceData;
import dev.blockphone.fabric.BlockPhoneFabric;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;

/**
 * Запись голосового с микрофона по умолчанию (javax.sound) в своём потоке: моно, 16 кГц (или 48/44,1 кГц с
 * последующим пересэмплированием). Останавливается сама на пределе длины. Один экземпляр — одна запись.
 */
public final class VoiceRecorder {
    private static final int[] RATES = {VoiceData.SAMPLE_RATE, 48_000, 44_100};

    private final int maxMs;
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private volatile boolean stop;
    private volatile boolean finished;
    private volatile float level;
    private volatile long recordedMs;
    private volatile String error;
    private int rate;

    private VoiceRecorder(int maxMs) {
        this.maxMs = maxMs;
    }

    /** Начать запись (не больше maxSeconds). */
    public static VoiceRecorder start(int maxSeconds) {
        VoiceRecorder recorder = new VoiceRecorder(maxSeconds * 1000);
        Thread thread = new Thread(recorder::run, "BlockPhone Voice recorder");
        thread.setDaemon(true);
        thread.start();
        return recorder;
    }

    private void run() {
        TargetDataLine line = null;
        try {
            for (int candidate : RATES) {
                AudioFormat format = new AudioFormat(candidate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                if (AudioSystem.isLineSupported(info)) {
                    line = (TargetDataLine) AudioSystem.getLine(info);
                    line.open(format, candidate / 5);
                    rate = candidate;
                    break;
                }
            }
            if (line == null) {
                error = "voice.error.no_microphone";
                return;
            }
            line.start();
            byte[] buffer = new byte[rate / 25 * 2]; // 40 мс
            long maxBytes = (long) maxMs * rate / 1000 * 2;
            while (!stop && pcm.size() < maxBytes) {
                int n = line.read(buffer, 0, buffer.length);
                if (n <= 0) {
                    continue;
                }
                pcm.write(buffer, 0, n);
                long sum = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    int v = (short) ((buffer[i] & 0xFF) | buffer[i + 1] << 8);
                    sum += (long) v * v;
                }
                level = (float) Math.min(1, Math.sqrt(sum / Math.max(1.0, n / 2.0)) / 8000);
                recordedMs = pcm.size() / 2 * 1000L / rate;
            }
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            BlockPhoneFabric.LOGGER.warn("Microphone unavailable", e);
            error = "voice.error.no_microphone";
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
            level = 0;
            finished = true;
        }
    }

    /** Закончить запись (результат — {@link #samples()} после {@link #finished()}). */
    public void stop() {
        stop = true;
    }

    public boolean finished() {
        return finished;
    }

    /** Достигнут предел длины (запись остановилась сама). */
    public boolean full() {
        return recordedMs >= maxMs - 50;
    }

    public long recordedMs() {
        return recordedMs;
    }

    /** Громкость с микрофона 0..1 (для индикатора). */
    public float level() {
        return level;
    }

    /** Ключ перевода ошибки (нет микрофона) или null. */
    public String error() {
        return error;
    }

    /** Записанный звук, моно 16 кГц. Вызывать после finished(). */
    public short[] samples() {
        byte[] bytes = pcm.toByteArray();
        short[] raw = new short[bytes.length / 2];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (short) ((bytes[i * 2] & 0xFF) | bytes[i * 2 + 1] << 8);
        }
        return Adpcm.resample(raw, rate == 0 ? VoiceData.SAMPLE_RATE : rate, VoiceData.SAMPLE_RATE);
    }
}
