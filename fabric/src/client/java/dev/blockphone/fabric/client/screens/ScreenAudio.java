package dev.blockphone.fabric.client.screens;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Звук для колонок: страница экрана (скрипт перехватывает звук видео и присылает PCM 24 кГц — стерео или 5.1) или музыка
 * телефона (по сети — Opus 48 кГц стерео, {@link PhoneSpeakers}); здесь он лежит в кольце на 4 с, до 6 каналов (порядок
 * Web Audio: L, R, C, LFE, SL, SR). Каждый источник звука в игре ({@link StreamSound}) читает своим «читателем» один
 * канал (колонка: OpenAL делает объёмным только моно), смесь в моно или стерео (звук «с телефона»). Если в дорожке только
 * стерео, недостающие каналы собираются из него: центр — середина, сабвуфер — низы середины, тыловые — копия передних.
 * Запись — поток Chromium или рендера, чтение — поток звука игры.
 */
public final class ScreenAudio {
    /** Что отдаёт читатель. */
    public enum Pick {
        MIX, STEREO, L, R, C, LFE, SL, SR;

        /** Канал колонки по имени из настроек («mix», «L», …). */
        public static Pick of(String name) {
            for (Pick p : values()) {
                if (p != STEREO && p.name().equalsIgnoreCase(name)) {
                    return p;
                }
            }
            return MIX;
        }
    }

    public static final int MAX_CHANNELS = 6;
    private final int rate;
    /** Кусок, который отдаётся движку за раз: 40 мс (в очереди OpenAL — 4 куска). */
    private final int chunk;
    /** Запас против рывков: читатель держится на столько позади головы. */
    private final int startLag;
    /** Читатель отстал больше чем на это — догоняет (иначе звук копит задержку). */
    private final int maxLag;
    private final int capacity;
    private final short[][] data = new short[MAX_CHANNELS][];
    /** Сколько каналов в дорожке сейчас (1, 2 или 6). */
    private volatile int channels = 2;
    /** Всего записано кадров (позиция головы). */
    private long written;
    private long lastWrite;
    // Сила басов (для «качающихся» колонок): по кускам при записи, со временем, когда кусок прозвучит.
    private static final int BASS_RING = 64;
    private final long[] bassAt = new long[BASS_RING];
    private final float[] bassLevel = new float[BASS_RING];
    private int bassHead;
    private float bassLow;
    private float bassPeak = 0.02f;

    /** Звук страницы: 24 кГц, запас 200 мс (страница присылает пачками по ~43 мс, бывают рывки). */
    public ScreenAudio() {
        this(24_000, 300, 600); // звук страницы приходит пачками по ~90 мс — запас 0,3 с, дальше 0,6 с — догнать
    }

    /** rate — частота; startLagMs — запас против рывков; maxLagMs — сколько можно отстать. */
    public ScreenAudio(int rate, int startLagMs, int maxLagMs) {
        this.rate = rate;
        this.chunk = rate * 40 / 1000;
        this.startLag = rate * startLagMs / 1000;
        this.maxLag = rate * maxLagMs / 1000;
        this.capacity = rate * 4;
        for (int c = 0; c < MAX_CHANNELS; c++) {
            data[c] = new short[capacity];
        }
    }

    /** Сколько каналов в дорожке сейчас (для приложения: «стерео», «5.1»). */
    public int channels() {
        return channels;
    }

    /** Кусок PCM: 16 бит, little-endian, ch чередующихся каналов (1, 2 или 6, порядок L, R, C, LFE, SL, SR). */
    public synchronized void push(byte[] pcm, int ch) {
        ch = ch >= MAX_CHANNELS ? MAX_CHANNELS : Math.max(1, Math.min(2, ch));
        int frames = pcm.length / (2 * ch);
        for (int i = 0; i < frames; i++) {
            int at = (int) ((written + i) % capacity);
            for (int c = 0; c < ch; c++) {
                int p = (i * ch + c) * 2;
                data[c][at] = (short) ((pcm[p] & 0xFF) | (pcm[p + 1] << 8));
            }
            if (ch == 1) {
                data[1][at] = data[0][at];
            }
        }
        channels = ch;
        measureBass(written, frames);
        written += frames;
        lastWrite = System.currentTimeMillis();
        stat(frames, lastWrite);
    }

    /**
     * Басы куска: низкие частоты (~120 Гц) середины каналов, среднеквадратично. Прозвучит кусок позже — через запас
     * читателя и очередь OpenAL (~160 мс).
     */
    private void measureBass(long from, int frames) {
        if (frames <= 0) {
            return;
        }
        float k = (float) (1 - Math.exp(-2 * Math.PI * 120 / rate));
        double sum = 0;
        for (int i = 0; i < frames; i++) {
            int at = (int) ((from + i) % capacity);
            float x = (data[0][at] + data[1][at]) / 65536f;
            bassLow += k * (x - bassLow);
            sum += bassLow * bassLow;
        }
        float rms = (float) Math.sqrt(sum / frames);
        bassPeak = Math.max(rms, bassPeak * 0.995f);
        int i = bassHead++ % BASS_RING;
        bassAt[i] = System.currentTimeMillis() + (startLag * 1000L / rate) + 100;
        bassLevel[i] = rms / Math.max(0.01f, bassPeak);
    }

    /** Сила баса, что звучит сейчас: 0..1 (1 — самый сильный за последнее время). */
    public synchronized float bassNow() {
        if (System.currentTimeMillis() - lastWrite > 1000) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long best = Long.MIN_VALUE;
        float level = 0;
        for (int i = 0; i < BASS_RING; i++) {
            if (bassAt[i] <= now && bassAt[i] > best) {
                best = bassAt[i];
                level = bassLevel[i];
            }
        }
        return level;
    }

    /** Кусок PCM из чередующихся short (ch — 1, 2 или 6 каналов). */
    public synchronized void push(short[] pcm, int offset, int frames, int ch) {
        ch = ch >= MAX_CHANNELS ? MAX_CHANNELS : Math.max(1, Math.min(2, ch));
        for (int i = 0; i < frames; i++) {
            int at = (int) ((written + i) % capacity);
            for (int c = 0; c < ch; c++) {
                data[c][at] = pcm[offset + i * ch + c];
            }
            if (ch == 1) {
                data[1][at] = data[0][at];
            }
        }
        channels = ch;
        measureBass(written, frames);
        written += frames;
        lastWrite = System.currentTimeMillis();
        stat(frames, lastWrite);
    }

    /** Звук шёл недавно (страница играет). */
    public synchronized boolean live() {
        return System.currentTimeMillis() - lastWrite < 1000;
    }

    public Reader reader(Pick pick) {
        return new Reader(pick);
    }

    public int rate() {
        return rate;
    }

    /** Кадров в одном куске (40 мс). */
    public int chunkFrames() {
        return chunk;
    }

    // --- Журнал (DevShots): сколько звука приходит в секунду, сколько раз переставлялись часы ---
    private long statFrames;
    private long statSince;
    private long statFps = -1;
    private int statResume;
    private int statBack;
    private int statForward;
    private long statClock;
    private long statMaxGap;
    private long statLastWrite;

    // --- Настоящая скорость писателя: страница отдаёт звук не ровно 24 000 кадров в секунду (у Chromium в окне без
    // экрана часы звука идут на ~2 % медленнее). Часы колонок и скорость их проигрывания подстраиваются под неё,
    // иначе запас тает, звук начинает рваться и пропадает. ---
    private double effRate = -1;
    private long rateFrames;
    private long rateSince;
    private long rateLast;
    private long rateMaxGap;
    private long rateStreamStart;

    private void measureRate(int frames, long now) {
        if (rateSince == 0 || now - rateLast > 300) {
            rateSince = now; // пауза писателя — измерять заново
            rateFrames = 0;
            rateMaxGap = 0;
            if (rateStreamStart == 0 || now - rateLast > 300) {
                rateStreamStart = now;
            }
        } else {
            rateFrames += frames;
            rateMaxGap = Math.max(rateMaxGap, now - rateLast);
            long dt = now - rateSince;
            if (dt >= 8000) {
                double measured = rateFrames * 1000.0 / dt;
                // Только ровный поток: не первые секунды (загрузка страницы шлёт звук рывками) и без пауз в окне.
                if (now - rateStreamStart > 10_000 && rateMaxGap < 250 && measured > rate * 0.85 && measured < rate * 1.15) {
                    double old = effRate < 0 ? rate : effRate;
                    double next = Math.max(rate * 0.975, Math.min(rate * 1.025, old * 0.5 + measured * 0.5));
                    setEffRate(next, now);
                }
                rateSince = now;
                rateFrames = 0;
                rateMaxGap = 0;
            }
        }
        rateLast = now;
    }

    /** Новая скорость часов — без скачка: часы продолжают с того же кадра. */
    private void setEffRate(double next, long now) {
        if (anchorFrame >= 0) {
            anchorFrame = anchorFrame + (long) ((now - anchorTime) * currentRate() / 1000);
            anchorTime = now;
        }
        effRate = next;
    }

    private double currentRate() {
        return effRate < 0 ? rate : effRate;
    }

    /** Скорость проигрывания колонок (AL_PITCH): во сколько раз писатель быстрее/медленнее номинала. */
    public synchronized float playRate() {
        return (float) (currentRate() / rate);
    }

    private void stat(int frames, long now) {
        measureRate(frames, now);
        if (statLastWrite > 0) {
            statMaxGap = Math.max(statMaxGap, now - statLastWrite);
        }
        statLastWrite = now;
        if (statSince == 0) {
            statSince = now;
        }
        statFrames += frames;
        if (now - statSince >= 2000) {
            statFps = statFrames * 1000 / (now - statSince);
            statFrames = 0;
            statSince = now;
        }
    }

    /** «пришло кадров/с из нужных, перестановки часов (пауза/назад/вперёд), запас, самая длинная пауза писателя». */
    public synchronized String stats() {
        String out = "in=" + statFps + "/" + rate + " clock=" + Math.round(currentRate()) + " resume=" + statResume + " back=" + statBack + " fwd=" + statForward
                + " margin=" + (written - statClock) * 1000 / rate + "ms gap=" + statMaxGap + "ms";
        statMaxGap = 0;
        return out;
    }

    // --- Общие часы: все колонки этого звука играют один и тот же кадр в одно и то же время ---
    private long anchorFrame = -1;
    private long anchorTime;
    /** С этого кадра звук свежий (после паузы писателя старое не повторяется). */
    private long resumeFrame;

    /**
     * Кадр, который должен звучать сейчас (общий для всех читателей): идёт по реальному времени на запас позади головы
     * записи. Писатель ушёл вперёд или отстал (рывки страницы, пауза кончилась) — часы переставляются заново.
     */
    public synchronized long clockFrame(long nowMs) {
        long ideal = written - startLag;
        if (anchorFrame < 0) {
            anchorFrame = Math.max(0, ideal);
            anchorTime = nowMs;
        }
        long c = anchorFrame + (long) ((nowMs - anchorTime) * currentRate() / 1000);
        boolean writing = nowMs - lastWrite < 300;
        if (writing && c > written + rate / 2) {
            // Писатель долго молчал (пауза, загрузка) и снова пишет: звук — с новых кадров, с запасом.
            resumeFrame = written;
            statResume++;
            anchorFrame = Math.max(0, ideal);
            anchorTime = nowMs;
            c = anchorFrame;
        } else if (writing && c < written - maxLag) {
            // Сильно отстали (рывок, видео догоняло владельца) — к свежему звуку.
            statForward++;
            anchorFrame = Math.max(0, ideal);
            anchorTime = nowMs;
            c = anchorFrame;
        } else if (writing && c > written - chunk / 2) {
            // Часы догнали писателя (часы браузера и компьютера расходятся — на длинных видео за минуты набегает запас):
            // один раз отступить на прежний запас, а не играть тишину по кусочкам. Плавной подстройки нет — её дёргал
            // каждый источник, и звук рвался.
            statBack++;
            anchorFrame = Math.max(0, ideal);
            anchorTime = nowMs;
            c = anchorFrame;
        }
        statClock = c;
        return c;
    }

    private static short clamp(float v) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(v)));
    }

    /** Читатель одного канала (или смеси) для источника звука колонки: куски с заданного кадра общих часов. */
    public final class Reader {
        private final Pick pick;
        /** Кадр сразу после последнего отданного куска (−1 — ещё не читал). */
        private long next = -1;
        /** Низкочастотный фильтр для сабвуфера из стерео (~120 Гц). */
        private float lfe;
        private final float lfeK;

        Reader(Pick pick) {
            this.pick = pick;
            this.lfeK = (float) (1 - Math.exp(-2 * Math.PI * 120 / rate));
        }

        public boolean stereo() {
            return pick == Pick.STEREO;
        }

        public int rate() {
            return rate;
        }

        /**
         * Кусок, который начнёт звучать на кадре want общих часов. Если он почти продолжает прошлый (разница меньше
         * четверти куска) — берётся продолжение (без щелчков), иначе — прыжок к нужному месту. Чего ещё нет — тишина.
         */
        public ByteBuffer readAt(long want) {
            boolean stereo = pick == Pick.STEREO;
            ByteBuffer out = BufferUtils.createByteBuffer(chunk * (stereo ? 4 : 2)).order(ByteOrder.LITTLE_ENDIAN);
            synchronized (ScreenAudio.this) {
                // Колонки одного звука читают подряд и идут в ногу (одни часы устройства); к часам — только если ушли на 2 куска.
                long from = next >= 0 && Math.abs(want - next) < chunk * 2L ? next : want;
                boolean surround = channels >= MAX_CHANNELS;
                for (int i = 0; i < chunk; i++) {
                    long frame = from + i;
                    float l = 0;
                    float r = 0;
                    float c = 0;
                    float sub = 0;
                    float sl = 0;
                    float sr = 0;
                    if (frame >= resumeFrame && frame >= 0 && frame < written && written - frame < capacity) {
                        int at = (int) (frame % capacity);
                        l = data[0][at];
                        r = data[1][at];
                        if (surround) {
                            c = data[2][at];
                            sub = data[3][at];
                            sl = data[4][at];
                            sr = data[5][at];
                        }
                    }
                    if (!surround) {
                        // Из стерео: центр — середина, тыловые — передние, сабвуфер — низы середины.
                        c = (l + r) / 2;
                        sl = l;
                        sr = r;
                        lfe += lfeK * (c - lfe);
                        sub = lfe;
                    }
                    switch (pick) {
                        case STEREO -> {
                            out.putShort(clamp(surround ? l + 0.7f * c + 0.7f * sl : l));
                            out.putShort(clamp(surround ? r + 0.7f * c + 0.7f * sr : r));
                        }
                        case MIX -> out.putShort(clamp(surround ? 0.3f * (l + r + c) + 0.2f * (sl + sr) : (l + r) / 2));
                        case L -> out.putShort(clamp(l));
                        case R -> out.putShort(clamp(r));
                        case C -> out.putShort(clamp(c));
                        case LFE -> out.putShort(clamp(surround ? sub : sub * 1.5f));
                        case SL -> out.putShort(clamp(sl));
                        case SR -> out.putShort(clamp(sr));
                    }
                }
                next = from + chunk;
            }
            out.flip();
            return out;
        }
    }
}
