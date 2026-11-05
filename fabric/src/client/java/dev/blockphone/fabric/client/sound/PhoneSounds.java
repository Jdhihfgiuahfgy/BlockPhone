package dev.blockphone.fabric.client.sound;

import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.music.audio.AudioDecoder;
import dev.blockphone.fabric.client.music.audio.Decoders;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Звуки телефона мимо звуков игры (громче и свои): уведомления, рингтон звонка, будильник, таймер. Каждый — встроенная
 * мелодия (синтез, «builtin:имя») или свой файл MP3/OGG/WAV из папки blockphone/sounds («file:имя»). Громкость —
 * {@link PhoneSettings#soundVolume} × общая громкость игры. Играет в своём потоке через javax.sound.
 */
public final class PhoneSounds {
    /** Что звучит: ключ настройки и мелодия по умолчанию. */
    public enum Kind {
        NOTIFY("chime"), RINGTONE("classic"), ALARM("radar"), TIMER("digital");

        public final String defaultTone;

        Kind(String defaultTone) {
            this.defaultTone = defaultTone;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final List<String> BUILTIN = List.of("chime", "classic", "digital", "radar", "marimba", "soft", "pop", "bells");
    private static final int RATE = 44100;
    /** Не-повторяемый звук (уведомление) обрезается до. */
    private static final int MAX_ONESHOT_MS = 8000;

    /** Идущий звук; stop() — прекратить. */
    public static final class Handle {
        volatile boolean stopped;
        volatile boolean finished;
        /** Объёмный звук: стерео-панорама −1 (слева) … 1 (справа) и «глухость» 0..1 (за стеной — низкие частоты). */
        private volatile float pan;
        private volatile float muffle;
        private volatile boolean spatial;
        private float pitch = 1;

        /** Поменять панораму и глухость на ходу (источник движется). */
        public void place(float pan, float muffle) {
            this.pan = Math.max(-1, Math.min(1, pan));
            this.muffle = Math.max(0, Math.min(1, muffle));
        }

        /** Громкость (множитель к начальной, 0..2) и высота тона (множитель, 0.25..4) на ходу — мотор, сирена (только микшер приложений). */
        volatile float tuneVolume = 1;
        volatile float tunePitch = 1;

        public void tune(float volume, float pitch) {
            this.tuneVolume = Math.max(0, Math.min(2, volume));
            this.tunePitch = Math.max(0.25f, Math.min(4, pitch));
        }

        public void stop() {
            stopped = true;
        }

        public boolean playing() {
            return !stopped && !finished;
        }

        float pan() {
            return pan;
        }

        float muffle() {
            return muffle;
        }
    }

    private static @Nullable Handle preview;

    private PhoneSounds() {
    }

    public static Path dir() {
        return ClientFiles.root().resolve("sounds");
    }

    /** Свои звуки из папки (MP3/OGG/WAV), по имени. */
    public static List<String> files() {
        List<String> list = new ArrayList<>();
        try {
            Files.createDirectories(dir());
            try (Stream<Path> s = Files.list(dir())) {
                s.filter(Files::isRegularFile).filter(Decoders::supported)
                        .map(p -> p.getFileName().toString()).sorted(String.CASE_INSENSITIVE_ORDER).forEach(list::add);
            }
        } catch (IOException ignored) {
            // нет папки
        }
        return list;
    }

    /** Выбранный звук для вида («builtin:chime» / «file:my.mp3»). */
    public static String choice(Kind kind) {
        String value = PhoneSettings.get().sounds.get(kind.key());
        return value == null || value.isBlank() ? "builtin:" + kind.defaultTone : value;
    }

    public static void setChoice(Kind kind, String value) {
        PhoneSettings.get().sounds.put(kind.key(), value);
        PhoneSettings.get().save();
    }

    /** Сыграть звук вида; loop — повторять, пока не остановят. */
    public static Handle play(Kind kind, boolean loop) {
        return play(choice(kind), loop);
    }

    /** Прослушать выбор (предыдущее прослушивание останавливается). */
    public static void preview(String choice) {
        stopPreview();
        preview = play(choice, false);
    }

    public static void stopPreview() {
        if (preview != null) {
            preview.stop();
            preview = null;
        }
    }

    public static Handle play(String choice, boolean loop) {
        Handle handle = new Handle();
        if (choice.startsWith("file:")) {
            Path file = dir().resolve(choice.substring(5)).normalize();
            if (file.startsWith(dir().normalize()) && Files.isRegularFile(file)) {
                return playFile(file, 1f, loop);
            }
            choice = "builtin:" + BUILTIN.get(0); // файл удалили — встроенный
        }
        String name = choice.startsWith("builtin:") ? choice.substring(8) : choice;
        short[] pcm = Tones.make(BUILTIN.contains(name) ? name : BUILTIN.get(0), RATE);
        start(handle, () -> null, pcm, loop, 1f);
        return handle;
    }

    /** Сыграть файл (свой звук или звук приложения), volume 0..1 поверх общей громкости телефона. */
    public static Handle playFile(Path file, float volume, boolean loop) {
        Handle handle = new Handle();
        start(handle, () -> Decoders.open(file), null, loop, volume);
        return handle;
    }

    /**
     * Объёмный звук приложения: панорама (−1..1), глухость (0..1 — низкочастотный фильтр, «за стеной») и высота тона
     * (0.5..2 — частота воспроизведения). Вывод всегда стерео.
     */
    public static Handle playFile(Path file, float volume, boolean loop, float pan, float muffle, float pitch) {
        Handle handle = new Handle();
        handle.spatial = true;
        handle.place(pan, muffle);
        handle.pitch = Math.max(0.5f, Math.min(2f, pitch));
        start(handle, () -> Decoders.open(file), null, loop, volume);
        return handle;
    }

    /**
     * Звук своего приложения через общий микшер ({@link AppMixer}): один выход для всех звуков приложения, без
     * перегрузки, с плавной остановкой. pan — NaN для обычного звука.
     */
    public static Handle playApp(Path file, float volume, boolean loop, float pan, float muffle, float pitch) {
        return AppMixer.play(file, volume, loop, pan, muffle, pitch);
    }

    private interface Opener {
        @Nullable AudioDecoder open() throws IOException;
    }

    static float gain(float extra) {
        Minecraft mc = Minecraft.getInstance();
        float v = Math.max(0, Math.min(1, PhoneSettings.get().soundVolume / 100f));
        return v * mc.options.getSoundSourceVolume(SoundSource.MASTER) * Math.max(0, Math.min(1, extra));
    }

    private static void start(Handle handle, Opener opener, short @Nullable [] tone, boolean loop, float extra) {
        Thread thread = new Thread(() -> {
            try {
                do {
                    if (tone != null) {
                        playPcm(handle, tone, RATE, 1, extra, loop);
                    } else {
                        try (AudioDecoder decoder = opener.open()) {
                            if (decoder == null) {
                                break;
                            }
                            playDecoder(handle, decoder, extra, loop);
                        }
                    }
                    if (loop && !handle.stopped) {
                        Thread.sleep(250);
                    }
                } while (loop && !handle.stopped);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Phone sound failed", e);
            } finally {
                handle.finished = true;
            }
        }, "BlockPhone Sound");
        thread.setDaemon(true);
        thread.start();
    }

    private static void playPcm(Handle handle, short[] pcm, int rate, int channels, float extra, boolean loop) throws Exception {
        try (SourceDataLine line = open(rate, channels)) {
            byte[] bytes = new byte[4096];
            int pos = 0;
            while (pos < pcm.length && !handle.stopped) {
                float g = gain(extra);
                int n = Math.min(bytes.length / 2, pcm.length - pos);
                for (int i = 0; i < n; i++) {
                    int v = Math.round(pcm[pos + i] * g);
                    bytes[2 * i] = (byte) v;
                    bytes[2 * i + 1] = (byte) (v >> 8);
                }
                line.write(bytes, 0, n * 2);
                pos += n;
            }
            finish(line, handle);
        }
    }

    private static void playDecoder(Handle handle, AudioDecoder decoder, float extra, boolean loop) throws Exception {
        if (handle.spatial) {
            playSpatial(handle, decoder, extra, loop);
            return;
        }
        int rate = decoder.sampleRate();
        int channels = decoder.channels();
        long maxSamples = loop ? Long.MAX_VALUE : (long) rate * channels * MAX_ONESHOT_MS / 1000;
        long played = 0;
        try (SourceDataLine line = open(rate, channels)) {
            short[] buf = new short[4096];
            byte[] bytes = new byte[buf.length * 2];
            int n;
            while (!handle.stopped && played < maxSamples && (n = decoder.read(buf)) != -1) {
                float g = gain(extra);
                for (int i = 0; i < n; i++) {
                    int v = Math.round(buf[i] * g);
                    bytes[2 * i] = (byte) v;
                    bytes[2 * i + 1] = (byte) (v >> 8);
                }
                line.write(bytes, 0, n * 2);
                played += n;
            }
            finish(line, handle);
        }
    }

    /** Стерео с панорамой (равная мощность), низкочастотным фильтром по глухости и сменой частоты для высоты тона. */
    private static void playSpatial(Handle handle, AudioDecoder decoder, float extra, boolean loop) throws Exception {
        int rate = decoder.sampleRate();
        int channels = Math.max(1, decoder.channels());
        long maxSamples = loop ? Long.MAX_VALUE : (long) rate * channels * MAX_ONESHOT_MS / 1000;
        long played = 0;
        int outRate = Math.round(rate * handle.pitch);
        try (SourceDataLine line = open(outRate, 2)) {
            short[] buf = new short[4096];
            byte[] bytes = new byte[4096 * 4];
            float lowL = 0, lowR = 0;
            int n;
            while (!handle.stopped && played < maxSamples && (n = decoder.read(buf)) != -1) {
                float g = gain(extra);
                double angle = (handle.pan + 1) * Math.PI / 4;
                float gl = (float) Math.cos(angle) * 1.41f * g;
                float gr = (float) Math.sin(angle) * 1.41f * g;
                float m = handle.muffle;
                // Один полюс: при глухости 1 — срез около 600 Гц, звук ещё и тише.
                float a = 1 - m * 0.92f;
                float quiet = 1 - m * 0.35f;
                int frames = n / channels;
                for (int f = 0; f < frames; f++) {
                    float l = buf[f * channels];
                    float r = channels > 1 ? buf[f * channels + 1] : l;
                    lowL += (l - lowL) * a;
                    lowR += (r - lowR) * a;
                    int vl = Math.max(-32768, Math.min(32767, Math.round(lowL * gl * quiet)));
                    int vr = Math.max(-32768, Math.min(32767, Math.round(lowR * gr * quiet)));
                    bytes[4 * f] = (byte) vl;
                    bytes[4 * f + 1] = (byte) (vl >> 8);
                    bytes[4 * f + 2] = (byte) vr;
                    bytes[4 * f + 3] = (byte) (vr >> 8);
                }
                line.write(bytes, 0, frames * 4);
                played += n;
            }
            finish(line, handle);
        }
    }

    private static SourceDataLine open(int rate, int channels) throws Exception {
        AudioFormat format = new AudioFormat(rate, 16, channels, true, false);
        SourceDataLine line = AudioSystem.getSourceDataLine(format);
        line.open(format, rate * channels / 5 * 2); // ~0,1 с — быстро останавливается
        line.start();
        return line;
    }

    private static void finish(SourceDataLine line, Handle handle) {
        if (handle.stopped) {
            line.stop();
            line.flush();
        } else {
            line.drain();
        }
    }

    /** Название выбора для настроек. */
    public static String label(String choice) {
        if (choice.startsWith("file:")) {
            String name = choice.substring(5);
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : name;
        }
        return dev.blockphone.fabric.client.i18n.PhoneLang.tr("sound.tone." + (choice.startsWith("builtin:") ? choice.substring(8) : choice));
    }
}
