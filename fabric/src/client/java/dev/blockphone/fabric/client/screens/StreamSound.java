package dev.blockphone.fabric.client.screens;

import dev.blockphone.fabric.BlockPhoneFabric;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * Звук экрана или колонки — свой источник OpenAL, мимо пулов игры: у Minecraft на всё потоковое (музыка, пластинки,
 * колонки) всего 8 каналов, и колонки второго экрана уже не получали ни одного. Источник в точке мира (моно, затухает к
 * радиусу; Sound Physics пересчитывает его раз в секунду — {@link SoundPhysicsCompat}) или «в ушах» (звук с телефона —
 * стерео, без затухания). Громкость — как у звуков «Блоки» (общую громкость игра задаёт слушателю).
 * <p>
 * Куски подкладывает свой поток раз в 10 мс (подвисания игры звук не рвут). Каждый кусок берётся по общим часам звука
 * ({@link ScreenAudio#clockFrame}) с поправкой на то, что ещё лежит в очереди источника, — все колонки одного звука
 * играют один и тот же кадр в одно время.
 */
final class StreamSound {
    /** Своё звуковое событие (для Sound Physics — имя звука). */
    static final Identifier EVENT = Identifier.fromNamespaceAndPath("blockphone", "stream");
    /** Все заведённые и ещё не удалённые. */
    static final Set<StreamSound> ALL = ConcurrentHashMap.newKeySet();
    private static final int QUEUE = 4;
    private static final long QUIET_MS = 800;
    private static final long RISE_MS = 1200;
    private static @Nullable Thread pump;
    private static long lastError;

    private final ScreenAudio.Reader reader;
    private final ScreenAudio audio;
    private final @Nullable Vec3 pos;
    private final int radius;
    private final DoubleSupplier gain;
    /** Когда заведён. */
    final long startedAt = System.currentTimeMillis();
    /** С какого момента звук снова нарастает с нуля (запуск, новая ссылка, подвисание игры). */
    volatile long fadeFrom = startedAt;
    /** Громкость для потока подкачки (считается в такте игры). */
    private volatile float alGain;
    private volatile boolean dead;
    /** Для журнала: источник играет и сколько раз пришлось запускать заново (кончались куски). */
    volatile boolean playing;
    volatile int restarts;
    // Только поток подкачки:
    private int source;
    private long context;
    private float setGain = -1;
    private float setPitch = 1;
    private long lastPhysics;

    /** pos == null — звук «с телефона» (без места в мире). */
    StreamSound(ScreenAudio audio, ScreenAudio.Reader reader, @Nullable Vec3 pos, int radius, DoubleSupplier gain) {
        this.audio = audio;
        this.reader = reader;
        this.pos = pos;
        this.radius = Math.max(1, radius);
        this.gain = gain;
    }

    /** Заглушить и плавно вернуть все источники (игра подвисла: потом звук мог бы прийти рывком). */
    static void fadeAll() {
        long now = System.currentTimeMillis();
        for (StreamSound s : ALL) {
            s.fadeFrom = now;
        }
    }

    /** Начать играть. */
    void start() {
        ALL.add(this);
        tick();
        ensurePump();
    }

    /** Остановить (источник удалит поток подкачки). */
    void finish() {
        dead = true;
    }

    boolean dead() {
        return dead;
    }

    boolean isRelative() {
        return pos == null;
    }

    @Nullable Vec3 pos() {
        return pos;
    }

    /** Множитель нарастания: первые 0,8 с тишина, потом за 1,2 с до полной громкости. */
    private float fade() {
        long t = System.currentTimeMillis() - fadeFrom - QUIET_MS;
        return t <= 0 ? 0 : Math.min(1, t / (float) RISE_MS);
    }

    /** Такт игры: громкость (своя × «Блоки» × нарастание). */
    void tick() {
        Minecraft mc = Minecraft.getInstance();
        float category = mc.options == null ? 1 : mc.options.getSoundSourceVolume(SoundSource.BLOCKS);
        alGain = (float) Math.max(0, Math.min(1, gain.getAsDouble())) * category * fade();
    }

    /** Такт игры для всех. */
    static void tickAll() {
        for (StreamSound s : ALL) {
            s.tick();
        }
    }

    private static synchronized void ensurePump() {
        if (pump != null && pump.isAlive()) {
            return;
        }
        Thread t = new Thread(StreamSound::pumpLoop, "BlockPhone Speakers");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY - 1);
        pump = t;
        t.start();
    }

    private static void pumpLoop() {
        while (true) {
            try {
                long ctx = ALC10.alcGetCurrentContext();
                for (StreamSound s : ALL) {
                    s.pump(ctx);
                }
                Thread.sleep(10);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                long now = System.currentTimeMillis();
                if (now - lastError > 10_000) {
                    lastError = now;
                    BlockPhoneFabric.LOGGER.warn("[screens] speaker pump", e);
                }
            }
        }
    }

    /** Поток подкачки: завести источник, подложить куски, держать громкость. */
    private void pump(long ctx) {
        if (dead) {
            release(ctx);
            ALL.remove(this);
            return;
        }
        if (ctx == 0) {
            return; // звук игры перезапускается (смена ресурспака) — подождать
        }
        if (source == 0 || context != ctx || !AL10.alIsSource(source)) {
            source = 0;
            if (!create(ctx)) {
                return;
            }
        }
        if (alGain != setGain) {
            setGain = alGain;
            AL10.alSourcef(source, AL10.AL_GAIN, setGain);
        }
        // Писатель (страница) идёт чуть медленнее/быстрее номинала — играть с той же скоростью, что и часы.
        float pitch = audio.playRate();
        if (Math.abs(pitch - setPitch) > 0.0005f) {
            setPitch = pitch;
            AL10.alSourcef(source, AL10.AL_PITCH, pitch);
        }
        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            int buffer = AL10.alSourceUnqueueBuffers(source);
            AL10.alDeleteBuffers(buffer);
        }
        int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
        int chunk = audio.chunkFrames();
        int format = reader.stereo() ? AL10.AL_FORMAT_STEREO16 : AL10.AL_FORMAT_MONO16;
        while (queued < QUEUE) {
            // Сколько ещё прозвучит из уже лежащего в очереди — новый кусок начнётся после этого.
            int offset = queued == 0 ? 0 : AL10.alGetSourcei(source, 0x1025 /* AL_SAMPLE_OFFSET */);
            long ahead = (long) queued * chunk - offset;
            long want = audio.clockFrame(System.currentTimeMillis()) + ahead;
            ByteBuffer data = reader.readAt(want);
            int buffer = AL10.alGenBuffers();
            AL10.alBufferData(buffer, format, data, reader.rate());
            AL10.alSourceQueueBuffers(source, buffer);
            queued++;
        }
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(source); // кончились куски (сильный рывок) — снова
            restarts++;
        }
        playing = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
        long now = System.currentTimeMillis();
        if (pos != null && now - lastPhysics > 1000) {
            lastPhysics = now;
            SoundPhysicsCompat.process(source, pos.x, pos.y, pos.z);
        }
    }

    private boolean create(long ctx) {
        int src = AL10.alGenSources();
        if (AL10.alGetError() != AL10.AL_NO_ERROR || src == 0) {
            return false;
        }
        source = src;
        context = ctx;
        setGain = -1;
        setPitch = 1;
        AL10.alSourcef(src, AL10.AL_PITCH, 1);
        AL10.alSourcei(src, AL10.AL_LOOPING, AL10.AL_FALSE);
        if (pos == null) {
            AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(src, AL10.AL_POSITION, 0, 0, 0);
            AL10.alSourcei(src, 0xD000 /* AL_SOURCE_DISTANCE_MODEL */, AL10.AL_NONE);
        } else {
            // Как звуки игры: линейное затухание до радиуса.
            AL10.alSourcei(src, AL10.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSource3f(src, AL10.AL_POSITION, (float) pos.x, (float) pos.y, (float) pos.z);
            AL10.alSourcei(src, 0xD000 /* AL_SOURCE_DISTANCE_MODEL */, 0xD003 /* AL_LINEAR_DISTANCE */);
            AL10.alSourcef(src, AL10.AL_MAX_DISTANCE, radius);
            AL10.alSourcef(src, AL10.AL_ROLLOFF_FACTOR, 1);
            AL10.alSourcef(src, AL10.AL_REFERENCE_DISTANCE, 0);
            lastPhysics = 0;
        }
        return true;
    }

    private void release(long ctx) {
        if (source != 0 && ctx != 0 && context == ctx && AL10.alIsSource(source)) {
            AL10.alSourceStop(source);
            int queued = AL10.alGetSourcei(source, AL10.AL_BUFFERS_QUEUED);
            for (int i = 0; i < queued; i++) {
                AL10.alDeleteBuffers(AL10.alSourceUnqueueBuffers(source));
            }
            AL10.alDeleteSources(source);
        }
        source = 0;
    }
}
