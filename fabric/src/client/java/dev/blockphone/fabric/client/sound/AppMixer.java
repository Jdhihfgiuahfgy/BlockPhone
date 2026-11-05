package dev.blockphone.fabric.client.sound;

import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.music.audio.AudioDecoder;
import dev.blockphone.fabric.client.music.audio.Decoders;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Микшер звуков своих приложений: все звуки сводятся в один стерео-выход 48 кГц в своём потоке (раньше — отдельная
 * линия javax.sound и поток на каждый звук: задержка, перегрузка и «каша», когда стреляют несколько игроков).
 * Звуки декодируются один раз и хранятся в памяти (кэш до {@link #CACHE_BYTES}); у каждого голоса — громкость,
 * панорама (равная мощность), глухость (фильтр), высота тона, повтор; остановка — плавная (15 мс), без щелчка.
 * На выходе — ограничитель: громкие моменты сжимаются, а не обрезаются. Линия закрывается через 10 с тишины.
 */
final class AppMixer {
    static final int RATE = 48000;
    private static final int BLOCK = 480; // 10 мс
    private static final int FADE = RATE * 15 / 1000;
    private static final long CACHE_BYTES = 96L << 20;
    private static final int MAX_VOICES = 48;
    private static final long MAX_ONESHOT_FRAMES = 8L * 48000 * 4; // не-повтор длиннее ~32 с обрезается

    private record Pcm(short[] data, int channels, int rate) {
        long bytes() {
            return data.length * 2L;
        }
    }

    private static final class Voice {
        final PhoneSounds.Handle handle;
        final Pcm pcm;
        final boolean loop;
        final float volume;
        final double step;
        double pos;
        float lowL, lowR;
        float panL = Float.NaN, panR;
        /** Плавная громкость sound.tune (без щелчков). */
        float tuneGain = -1;
        int fade = -1; // >= 0 — затухает
        long played;

        Voice(PhoneSounds.Handle handle, Pcm pcm, boolean loop, float volume, float pitch) {
            this.handle = handle;
            this.pcm = pcm;
            this.loop = loop;
            this.volume = volume;
            this.step = pcm.rate / (double) RATE * pitch;
        }
    }

    private static final Map<Path, Pcm> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static long cacheBytes;
    private static final ConcurrentLinkedQueue<Voice> INCOMING = new ConcurrentLinkedQueue<>();
    private static Thread thread;

    private AppMixer() {
    }

    /** Сыграть файл через микшер. pan — NaN для обычного (не объёмного) звука. */
    static PhoneSounds.Handle play(Path file, float volume, boolean loop, float pan, float muffle, float pitch) {
        PhoneSounds.Handle handle = new PhoneSounds.Handle();
        handle.place(Float.isNaN(pan) ? 0 : pan, muffle);
        Pcm pcm;
        try {
            pcm = load(file);
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("App sound failed: {}", file, e);
            handle.finished = true;
            return handle;
        }
        INCOMING.add(new Voice(handle, pcm, loop, Math.max(0, Math.min(1, volume)), Math.max(0.5f, Math.min(2f, pitch))));
        ensureThread();
        return handle;
    }

    private static Pcm load(Path file) throws IOException {
        synchronized (CACHE) {
            Pcm cached = CACHE.get(file);
            if (cached != null) {
                return cached;
            }
        }
        short[] all;
        int channels, rate;
        try (AudioDecoder decoder = Decoders.open(file)) {
            if (decoder == null) {
                throw new IOException("unsupported sound");
            }
            channels = Math.max(1, Math.min(2, decoder.channels()));
            rate = decoder.sampleRate();
            int srcChannels = Math.max(1, decoder.channels());
            short[] buf = new short[8192];
            short[] out = new short[1 << 16];
            int len = 0;
            int n;
            while ((n = decoder.read(buf)) != -1) {
                // больше двух каналов — берём первые два
                int frames = n / srcChannels;
                if (len + frames * channels > out.length) {
                    out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, len + frames * channels));
                }
                for (int f = 0; f < frames; f++) {
                    for (int c = 0; c < channels; c++) {
                        out[len++] = buf[f * srcChannels + c];
                    }
                }
                if (len > MAX_ONESHOT_FRAMES * channels) {
                    break;
                }
            }
            all = java.util.Arrays.copyOf(out, len);
        }
        Pcm pcm = new Pcm(all, channels, rate);
        synchronized (CACHE) {
            CACHE.put(file, pcm);
            cacheBytes += pcm.bytes();
            Iterator<Map.Entry<Path, Pcm>> it = CACHE.entrySet().iterator();
            while (cacheBytes > CACHE_BYTES && it.hasNext()) {
                Map.Entry<Path, Pcm> e = it.next();
                if (e.getValue() == pcm) {
                    continue;
                }
                cacheBytes -= e.getValue().bytes();
                it.remove();
            }
        }
        return pcm;
    }

    private static synchronized void ensureThread() {
        if (thread != null && thread.isAlive()) {
            return;
        }
        thread = new Thread(AppMixer::run, "BlockPhone App Mixer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
    }

    private static void run() {
        List<Voice> voices = new ArrayList<>();
        float[] l = new float[BLOCK], r = new float[BLOCK];
        byte[] bytes = new byte[BLOCK * 4];
        float limit = 1f;
        long idleSince = System.currentTimeMillis();
        SourceDataLine line = null;
        try {
            AudioFormat format = new AudioFormat(RATE, 16, 2, true, false);
            line = AudioSystem.getSourceDataLine(format);
            line.open(format, RATE * 4 * 60 / 1000); // ~60 мс
            line.start();
            while (true) {
                Voice v;
                while ((v = INCOMING.poll()) != null) {
                    voices.add(v);
                    if (voices.size() > MAX_VOICES) {
                        // самый старый — затухает (по одному за раз)
                        for (Voice old : voices) {
                            if (old.fade < 0) {
                                old.fade = FADE;
                                break;
                            }
                        }
                    }
                }
                if (voices.isEmpty()) {
                    if (System.currentTimeMillis() - idleSince > 10_000) {
                        break;
                    }
                } else {
                    idleSince = System.currentTimeMillis();
                }
                java.util.Arrays.fill(l, 0);
                java.util.Arrays.fill(r, 0);
                float g = PhoneSounds.gain(1f);
                for (Iterator<Voice> it = voices.iterator(); it.hasNext(); ) {
                    Voice voice = it.next();
                    if (!mixVoice(voice, l, r, g)) {
                        voice.handle.finished = true;
                        it.remove();
                    }
                }
                // ограничитель: пик блока выше 0,9 — громкость плавно вниз; потом медленно назад (~0,4 с)
                float peak = 0;
                for (int i = 0; i < BLOCK; i++) {
                    peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
                }
                float target = peak * limit > 0.9f ? 0.9f / peak : Math.min(1f, limit + 0.025f);
                for (int i = 0; i < BLOCK; i++) {
                    float k = limit + (target - limit) * (i + 1) / BLOCK;
                    int vl = Math.round(soft(l[i] * k) * 32767);
                    int vr = Math.round(soft(r[i] * k) * 32767);
                    bytes[4 * i] = (byte) vl;
                    bytes[4 * i + 1] = (byte) (vl >> 8);
                    bytes[4 * i + 2] = (byte) vr;
                    bytes[4 * i + 3] = (byte) (vr >> 8);
                }
                limit = target;
                line.write(bytes, 0, bytes.length);
            }
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("App mixer stopped", e);
        } finally {
            for (Voice voice : voices) {
                voice.handle.finished = true;
            }
            if (line != null) {
                line.drain();
                line.close();
            }
            synchronized (AppMixer.class) {
                thread = null;
            }
            if (!INCOMING.isEmpty()) {
                ensureThread();
            }
        }
    }

    /** Мягкое ограничение выше 0,95 (на всякий случай после ограничителя). */
    private static float soft(float x) {
        float a = Math.abs(x);
        if (a <= 0.95f) {
            return x;
        }
        float y = 0.95f + 0.05f * (float) Math.tanh((a - 0.95f) / 0.05f);
        return Math.copySign(Math.min(1f, y), x);
    }

    /** Добавить голос в блок. false — закончился. */
    private static boolean mixVoice(Voice v, float[] l, float[] r, float gain) {
        PhoneSounds.Handle h = v.handle;
        if (h.stopped && v.fade < 0) {
            v.fade = FADE;
        }
        double angle = (h.pan() + 1) * Math.PI / 4;
        float tl = (float) Math.cos(angle) * 1.41f, tr = (float) Math.sin(angle) * 1.41f;
        if (Float.isNaN(v.panL)) {
            v.panL = tl;
            v.panR = tr;
        }
        float m = h.muffle();
        float a = 1 - m * 0.92f;
        float quiet = (1 - m * 0.35f) * v.volume * gain;
        double step = v.step * h.tunePitch;
        float tuneTarget = h.tuneVolume;
        if (v.tuneGain < 0) {
            v.tuneGain = tuneTarget;
        }
        short[] d = v.pcm.data;
        int ch = v.pcm.channels;
        int frames = d.length / ch;
        if (frames == 0) {
            return false;
        }
        for (int i = 0; i < BLOCK; i++) {
            if (v.pos >= frames - 1) {
                if (!v.loop) {
                    return false;
                }
                v.pos -= frames - 1;
            }
            int p = (int) v.pos;
            float t = (float) (v.pos - p);
            float sl, sr;
            if (ch == 1) {
                sl = d[p] + (d[p + 1] - d[p]) * t;
                sr = sl;
            } else {
                sl = d[2 * p] + (d[2 * p + 2] - d[2 * p]) * t;
                sr = d[2 * p + 1] + (d[2 * p + 3] - d[2 * p + 1]) * t;
            }
            v.lowL += (sl - v.lowL) * a;
            v.lowR += (sr - v.lowR) * a;
            // панорама плавно (звук движется)
            v.panL += (tl - v.panL) * 0.01f;
            v.panR += (tr - v.panR) * 0.01f;
            v.tuneGain += (tuneTarget - v.tuneGain) * 0.003f;
            float k = quiet * v.tuneGain / 32768f;
            if (v.fade >= 0) {
                k *= v.fade / (float) FADE;
                if (--v.fade <= 0) {
                    return false;
                }
            }
            l[i] += v.lowL * v.panL * k;
            r[i] += v.lowR * v.panR * k;
            v.pos += step;
        }
        return true;
    }
}
