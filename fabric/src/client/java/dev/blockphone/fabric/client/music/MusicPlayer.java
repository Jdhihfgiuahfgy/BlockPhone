package dev.blockphone.fabric.client.music;

import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Музыкальный плеер телефона: очередь, текущий трек, пауза, перемотка, перемешивание, повтор, громкость.
 * Играет и с закрытым телефоном. Состояние — на потоке рендера; звук — в потоке {@link Playback}.
 * Очередь может «подгружаться» (Моя волна): когда треки заканчиваются, вызывается {@code more} (в фоне).
 */
public final class MusicPlayer {
    public enum State { STOPPED, LOADING, PLAYING, PAUSED }

    public enum Repeat { OFF, ALL, ONE }

    private static final int MAX_FAILURES = 3;
    private static final Random RANDOM = new Random();

    private static final List<Track> QUEUE = new ArrayList<>();
    private static final Deque<Integer> HISTORY = new ArrayDeque<>();
    private static int index = -1;
    private static @Nullable Supplier<List<Track>> more;
    private static String queueName = "";
    private static boolean loadingMore;
    private static boolean shuffle;
    private static Repeat repeat = Repeat.OFF;
    private static @Nullable Playback playback;
    private static int failures;
    private static @Nullable String error;
    private static long errorAt;
    private static volatile float gain = 0.5f;

    // --- Чтение состояния ---

    public static @Nullable Track current() {
        return index >= 0 && index < QUEUE.size() ? QUEUE.get(index) : null;
    }

    public static State state() {
        Playback p = playback;
        if (p == null) {
            return State.STOPPED;
        }
        return switch (p.phase) {
            case LOADING -> State.LOADING;
            case PAUSED -> State.PAUSED;
            case PLAYING -> p.paused ? State.PAUSED : State.PLAYING;
            default -> State.STOPPED;
        };
    }

    public static boolean playing() {
        State s = state();
        return s == State.PLAYING || s == State.LOADING;
    }

    private static long lastShown;
    private static Playback lastShownFor;

    /** Позиция трека, плавная и без шагов назад (кроме перемотки и смены трека). */
    public static long positionMs() {
        Playback p = playback;
        if (p == null) {
            return 0;
        }
        long now = p.positionNow();
        // Небольшой откат назад (оценка буфера колеблется) не показываем; перемотку назад — показываем.
        if (p == lastShownFor && now < lastShown && lastShown - now < 400 && p.seekTo < 0) {
            return lastShown;
        }
        lastShownFor = p;
        lastShown = now;
        return now;
    }

    public static long durationMs() {
        Playback p = playback;
        if (p != null && p.durationMs > 0) {
            return p.durationMs;
        }
        Track t = current();
        return t != null ? t.durationMs() : 0;
    }

    public static List<Track> queue() {
        return QUEUE;
    }

    public static int index() {
        return index;
    }

    /** Название очереди («Моя волна», «Мне нравится», «Моя музыка»). */
    public static String queueName() {
        return queueName;
    }

    public static boolean shuffle() {
        return shuffle;
    }

    public static Repeat repeat() {
        return repeat;
    }

    /** Ошибка воспроизведения, если была не позже 5 секунд назад. */
    public static @Nullable String recentError() {
        return error != null && System.currentTimeMillis() - errorAt < 5000 ? error : null;
    }

    /** Множитель громкости (громкость плеера² × общая громкость игры). Читается потоком воспроизведения. */
    static float gain() {
        return gain;
    }

    // --- Управление ---

    /**
     * Играть список с позиции start. more — подгрузка продолжения (вызывается в фоне), name — подпись очереди.
     */
    public static void play(List<Track> tracks, int start, @Nullable Supplier<List<Track>> loader, String name) {
        if (tracks.isEmpty()) {
            return;
        }
        QUEUE.clear();
        QUEUE.addAll(tracks);
        HISTORY.clear();
        more = loader;
        queueName = name;
        failures = 0;
        startAt(Math.max(0, Math.min(tracks.size() - 1, start)));
    }

    public static void toggle() {
        Playback p = playback;
        if (p == null) {
            if (current() != null) {
                startAt(index);
            }
            return;
        }
        p.paused = !p.paused;
    }

    public static void pause() {
        Playback p = playback;
        if (p != null) {
            p.paused = true;
        }
    }

    public static void next() {
        advance(true);
    }

    /** В начало трека, если прошло больше 3 секунд, иначе — предыдущий. */
    public static void previous() {
        if (positionMs() > 3000 || QUEUE.isEmpty()) {
            seek(0);
            return;
        }
        if (!HISTORY.isEmpty()) {
            int prev = HISTORY.pop();
            if (prev < QUEUE.size()) {
                startAt(prev, false);
                return;
            }
        }
        startAt(index > 0 ? index - 1 : (repeat == Repeat.ALL ? QUEUE.size() - 1 : 0), false);
    }

    public static void seek(long ms) {
        Playback p = playback;
        if (p != null) {
            p.seekTo = Math.max(0, ms);
            p.positionMs = Math.max(0, ms);
        }
    }

    public static void setShuffle(boolean value) {
        shuffle = value;
    }

    public static void cycleRepeat() {
        repeat = Repeat.values()[(repeat.ordinal() + 1) % Repeat.values().length];
    }

    public static void stop() {
        if (playback != null) {
            playback.stopped = true;
            playback = null;
        }
    }

    /** Подгрузчик текущей очереди (null — обычный список). */
    public static @Nullable Supplier<List<Track>> loader() {
        return more;
    }

    /** Перейти к треку очереди. */
    public static void jump(int i) {
        if (i >= 0 && i < QUEUE.size()) {
            startAt(i);
        }
    }

    private static void startAt(int i) {
        startAt(i, true);
    }

    private static void startAt(int i, boolean remember) {
        if (remember && index >= 0 && index != i) {
            HISTORY.push(index);
            while (HISTORY.size() > 100) {
                HISTORY.removeLast();
            }
        }
        stop();
        index = i;
        Playback next = new Playback(QUEUE.get(i), false);
        playback = next;
        next.start();
        prefetchMore();
    }

    /** Следующий трек. byUser — нажали «вперёд» (тогда повтор одного трека не держит). */
    private static void advance(boolean byUser) {
        if (QUEUE.isEmpty()) {
            return;
        }
        if (!byUser && repeat == Repeat.ONE) {
            startAt(index, false);
            return;
        }
        if (shuffle && QUEUE.size() > 1 && more == null) {
            int next;
            do {
                next = RANDOM.nextInt(QUEUE.size());
            } while (next == index);
            startAt(next);
            return;
        }
        if (index + 1 < QUEUE.size()) {
            startAt(index + 1);
        } else if (more != null) {
            loadMore(true);
        } else if (repeat == Repeat.ALL || byUser) {
            startAt(0);
        } else {
            stop();
        }
    }

    private static void prefetchMore() {
        if (more != null && index >= QUEUE.size() - 2) {
            loadMore(false);
        }
    }

    private static void loadMore(boolean thenPlay) {
        if (loadingMore || more == null) {
            return;
        }
        loadingMore = true;
        Supplier<List<Track>> loader = more;
        ClientFiles.IO.execute(() -> {
            List<Track> loaded;
            try {
                loaded = loader.get();
            } catch (RuntimeException e) {
                loaded = List.of();
            }
            List<Track> result = loaded;
            Minecraft.getInstance().execute(() -> {
                loadingMore = false;
                if (more != loader) {
                    return; // Очередь уже сменили.
                }
                for (Track t : result) {
                    if (QUEUE.stream().noneMatch(q -> q.key().equals(t.key()))) {
                        QUEUE.add(t);
                    }
                }
                if (thenPlay) {
                    if (index + 1 < QUEUE.size()) {
                        startAt(index + 1);
                    } else {
                        stop();
                    }
                }
            });
        });
    }

    /** Каждый тик клиента: громкость, переход к следующему треку, ошибки. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        float volume = PhoneSettings.get().musicVolume;
        gain = volume * volume * mc.options.getSoundSourceVolume(SoundSource.MASTER);
        Playback p = playback;
        if (p == null) {
            return;
        }
        if (p.phase == Playback.Phase.FINISHED) {
            failures = 0;
            playback = null;
            advance(false);
        } else if (p.phase == Playback.Phase.FAILED) {
            playback = null;
            error = PhoneLang.tr(p.error) + " — " + p.track.title();
            errorAt = System.currentTimeMillis();
            if (++failures < MAX_FAILURES && QUEUE.size() > 1) {
                advance(true);
            } else {
                failures = 0;
            }
        }
    }

    private MusicPlayer() {
    }
}
