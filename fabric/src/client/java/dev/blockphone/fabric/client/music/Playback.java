package dev.blockphone.fabric.client.music;

import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.music.audio.AudioDecoder;
import dev.blockphone.fabric.client.music.audio.Decoders;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Path;

/**
 * Воспроизведение одного трека в своём потоке: получить файл (для Яндекса — скачать), декодировать и писать PCM
 * в звуковую карту (javax.sound). Управление — через volatile-поля из потока рендера ({@link MusicPlayer}).
 */
final class Playback implements Runnable {
    enum Phase { LOADING, PLAYING, PAUSED, FINISHED, FAILED }

    final Track track;
    volatile boolean paused;
    volatile boolean stopped;
    /** Запрошенная перемотка, мс (-1 — нет). */
    volatile long seekTo = -1;

    volatile Phase phase = Phase.LOADING;
    volatile long positionMs;
    volatile long durationMs;
    /** Ключ перевода ошибки (при FAILED). */
    volatile String error;

    Playback(Track track, boolean paused) {
        this.track = track;
        this.paused = paused;
        this.durationMs = track.durationMs();
    }

    void start() {
        Thread thread = new Thread(this, "BlockPhone Music");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        AudioDecoder decoder = null;
        SourceDataLine line = null;
        try {
            Path file = TrackSources.resolve(track);
            if (stopped) {
                return;
            }
            decoder = Decoders.open(file);
            if (decoder.durationMs() > 0 && durationMs <= 0) {
                durationMs = decoder.durationMs();
            }
            int rate = decoder.sampleRate();
            int channels = decoder.channels();
            AudioFormat format = new AudioFormat(rate, 16, channels, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, rate * channels * 2 / 4); // буфер ~0,25 с — пауза и перемотка срабатывают быстро
            short[] samples = new short[2048 * channels];
            byte[] bytes = new byte[samples.length * 2];
            long base = 0;
            long framesAtBase = 0;
            while (!stopped) {
                long seek = seekTo;
                if (seek >= 0) {
                    seekTo = -1;
                    line.stop();
                    line.flush();
                    if (!decoder.seek(seek)) {
                        decoder.close();
                        decoder = Decoders.open(file);
                        skip(decoder, seek);
                    }
                    base = seek;
                    framesAtBase = line.getLongFramePosition();
                    positionMs = seek;
                }
                if (paused) {
                    if (line.isRunning()) {
                        line.stop();
                    }
                    phase = Phase.PAUSED;
                    Thread.sleep(20);
                    continue;
                }
                if (!line.isRunning()) {
                    line.start();
                }
                phase = Phase.PLAYING;
                int n = decoder.read(samples);
                if (n < 0) {
                    line.drain();
                    positionMs = Math.max(positionMs, durationMs);
                    phase = Phase.FINISHED;
                    return;
                }
                float gain = MusicPlayer.gain();
                for (int i = 0; i < n; i++) {
                    int v = Math.round(samples[i] * gain);
                    bytes[i * 2] = (byte) v;
                    bytes[i * 2 + 1] = (byte) (v >> 8);
                }
                line.write(bytes, 0, n * 2);
                positionMs = base + (line.getLongFramePosition() - framesAtBase) * 1000L / rate;
            }
        } catch (LineUnavailableException | IllegalArgumentException e) {
            fail("music.error.audio_device", e);
        } catch (TrackSourceException e) {
            fail(e.getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            fail("music.error.decode", e);
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
            if (decoder != null) {
                decoder.close();
            }
        }
    }

    /** Пропустить начало (для декодеров без перемотки): декодировать и выбросить. */
    private static void skip(AudioDecoder decoder, long ms) throws java.io.IOException {
        long left = ms * decoder.sampleRate() / 1000L * decoder.channels();
        short[] buffer = new short[8192];
        while (left > 0) {
            int n = decoder.read(buffer);
            if (n < 0) {
                return;
            }
            left -= n;
        }
    }

    private void fail(String key, Throwable cause) {
        if (cause != null) {
            BlockPhoneFabric.LOGGER.warn("Music playback failed for {}: {}", track.label(), key, cause);
        }
        error = key;
        phase = Phase.FAILED;
    }
}
