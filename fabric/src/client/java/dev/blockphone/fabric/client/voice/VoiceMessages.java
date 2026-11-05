package dev.blockphone.fabric.client.voice;

import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.data.VoiceData;
import dev.blockphone.common.protocol.data.VoiceData.VoiceRef;
import dev.blockphone.common.protocol.packet.VoicePackets;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Голосовые сообщения на клиенте: отправка (сжатие + загрузка кусками), скачивание и проигрывание
 * (одно за раз; музыка на это время ставится на паузу). Состояние — на потоке рендера.
 */
public final class VoiceMessages {
    private static final int MAX_CACHED = 30;

    /** Скачанные и распакованные голосовые (LRU). */
    private static final Map<Long, short[]> AUDIO = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, short[]> eldest) {
            return size() > MAX_CACHED;
        }
    };
    private static final Map<Long, byte[]> DOWNLOADS = new HashMap<>();
    private static final Map<Long, Integer> RECEIVED = new HashMap<>();
    private static final java.util.Set<Long> FAILED = new java.util.HashSet<>();

    private static long playingId = -1;
    private static long waitingId = -1;
    private static @Nullable Player player;
    private static boolean resumeMusic;

    private static final Map<Integer, Consumer<VoicePackets.UploadResult>> UPLOADS = new HashMap<>();
    private static int uploadCounter;

    // --- Отправка ---

    /** Сжать и загрузить (в фоне). onResult — на потоке рендера. */
    public static void upload(short[] pcm16k, Consumer<VoicePackets.UploadResult> onResult) {
        int id = ++uploadCounter;
        int durationMs = (int) (pcm16k.length * 1000L / VoiceData.SAMPLE_RATE);
        UPLOADS.put(id, onResult);
        ClientFiles.IO.execute(() -> {
            byte[] data = Adpcm.encode(pcm16k);
            Minecraft.getInstance().execute(() -> {
                if (!UPLOADS.containsKey(id)) {
                    return;
                }
                ClientNetwork.send(new VoicePackets.UploadBegin(id, data.length, VoiceData.CODEC_ADPCM16, durationMs));
                for (int offset = 0; offset < data.length; offset += ImageRef.CHUNK) {
                    ClientNetwork.send(new VoicePackets.UploadChunk(id, offset,
                            Arrays.copyOfRange(data, offset, Math.min(data.length, offset + ImageRef.CHUNK))));
                }
            });
        });
    }

    public static void onUploadResult(VoicePackets.UploadResult result) {
        if (result.uploadId() == -1) {
            UPLOADS.values().forEach(c -> c.accept(result));
            UPLOADS.clear();
            return;
        }
        Consumer<VoicePackets.UploadResult> callback = UPLOADS.remove(result.uploadId());
        if (callback != null) {
            callback.accept(result);
        }
    }

    // --- Проигрывание ---

    /** Нажали на голосовое: играть / пауза / продолжить. */
    public static void toggle(VoiceRef voice) {
        if (playingId == voice.id() && player != null) {
            player.paused = !player.paused;
            return;
        }
        stop();
        short[] audio = AUDIO.get(voice.id());
        if (audio != null) {
            play(voice.id(), audio);
            return;
        }
        if (FAILED.contains(voice.id())) {
            return;
        }
        waitingId = voice.id();
        if (!DOWNLOADS.containsKey(voice.id())) {
            DOWNLOADS.put(voice.id(), null);
            ClientNetwork.send(new VoicePackets.RequestVoice(voice.id()));
        }
    }

    /** Проиграть готовый звук (моно 16 кГц) под своим ключом (голосовые Telegram); тот же ключ — пауза/продолжить. */
    public static void togglePcm(long key, short[] audio) {
        togglePcm(key, audio, 1f);
    }

    /** То же с усилением boost (1 — как есть; громче 1 — тихие записи, пики сглаживает ограничитель). */
    public static void togglePcm(long key, short[] audio, float boost) {
        if (playingId == key && player != null) {
            player.paused = !player.paused;
            player.boost = boost;
            return;
        }
        stop();
        play(key, audio);
        player.boost = boost;
    }

    /** Поменять усиление уже играющего звука с этим ключом (ползунок громкости во время проигрывания). */
    public static void setBoost(long key, float boost) {
        Player p = player;
        if (playingId == key && p != null) {
            p.boost = boost;
        }
    }

    public static void onChunk(VoicePackets.VoiceChunk chunk) {
        long id = chunk.voiceId();
        if (chunk.total() <= 0) {
            DOWNLOADS.remove(id);
            FAILED.add(id);
            return;
        }
        byte[] buffer = DOWNLOADS.get(id);
        // Размер объявляет сервер: не больше 16 МБ (~30 минут ADPCM) и не больше 8 загрузок сразу.
        if (buffer == null && (chunk.total() > 16 * 1024 * 1024 || DOWNLOADS.size() >= 8)) {
            FAILED.add(id);
            return;
        }
        if (buffer == null) {
            buffer = new byte[chunk.total()];
            DOWNLOADS.put(id, buffer);
            RECEIVED.put(id, 0);
        }
        int received = RECEIVED.getOrDefault(id, 0);
        if (chunk.offset() != received || received + chunk.data().length > buffer.length) {
            DOWNLOADS.remove(id);
            RECEIVED.remove(id);
            return;
        }
        System.arraycopy(chunk.data(), 0, buffer, received, chunk.data().length);
        received += chunk.data().length;
        RECEIVED.put(id, received);
        if (received < buffer.length) {
            return;
        }
        DOWNLOADS.remove(id);
        RECEIVED.remove(id);
        short[] audio = Adpcm.decode(buffer);
        AUDIO.put(id, audio);
        if (waitingId == id) {
            waitingId = -1;
            play(id, audio);
        }
    }

    private static void play(long id, short[] audio) {
        resumeMusic = MusicPlayer.state() == MusicPlayer.State.PLAYING;
        if (resumeMusic) {
            MusicPlayer.pause();
        }
        playingId = id;
        player = new Player(audio);
        Thread thread = new Thread(player, "BlockPhone Voice player");
        thread.setDaemon(true);
        thread.start();
    }

    public static void stop() {
        if (player != null) {
            player.stopped = true;
            player = null;
        }
        playingId = -1;
        waitingId = -1;
        if (resumeMusic) {
            resumeMusic = false;
            MusicPlayer.toggle();
        }
    }

    /** Каждый тик: голосовое доиграло — вернуть музыку. */
    public static void tick() {
        if (player != null && player.done) {
            player = null;
            playingId = -1;
            if (resumeMusic) {
                resumeMusic = false;
                MusicPlayer.toggle();
            }
        }
    }

    /** Это голосовое сейчас играет (или грузится для проигрывания). */
    public static boolean active(long id) {
        return playingId == id || waitingId == id;
    }

    public static boolean playing(long id) {
        return playingId == id && player != null && !player.paused;
    }

    public static boolean loading(long id) {
        return waitingId == id;
    }

    public static boolean failed(long id) {
        return FAILED.contains(id);
    }

    /** Доля проигранного 0..1 (для полоски). */
    public static float progress(long id) {
        Player p = player;
        return playingId == id && p != null ? p.position / (float) Math.max(1, p.audio.length) : 0;
    }

    public static String time(long ms) {
        long s = Math.max(0, (ms + 500) / 1000);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    public static void reset() {
        stop();
        AUDIO.clear();
        DOWNLOADS.clear();
        RECEIVED.clear();
        FAILED.clear();
        UPLOADS.clear();
    }

    /** Проигрывание одного голосового в своём потоке. */
    private static final class Player implements Runnable {
        final short[] audio;
        volatile boolean paused;
        volatile boolean stopped;
        volatile boolean done;
        volatile int position;
        volatile float boost = 1f;

        Player(short[] audio) {
            this.audio = audio;
        }

        @Override
        public void run() {
            AudioFormat format = new AudioFormat(VoiceData.SAMPLE_RATE, 16, 1, true, false);
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format, VoiceData.SAMPLE_RATE / 2);
                line.start();
                byte[] bytes = new byte[1600 * 2];
                while (!stopped && position < audio.length) {
                    if (paused) {
                        line.stop();
                        Thread.sleep(20);
                        continue;
                    }
                    if (!line.isRunning()) {
                        line.start();
                    }
                    float gain = mediaGain() * boost * 1.4f / 32768f;
                    int n = Math.min(1600, audio.length - position);
                    for (int i = 0; i < n; i++) {
                        int v = Math.round(limit(audio[position + i] * gain) * 32767f);
                        bytes[i * 2] = (byte) v;
                        bytes[i * 2 + 1] = (byte) (v >> 8);
                    }
                    line.write(bytes, 0, n * 2);
                    position += n;
                }
                if (!stopped) {
                    line.drain();
                }
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Voice message playback failed", e);
            } finally {
                done = true;
            }
        }
    }

    private VoiceMessages() {
    }

    /** Мягкий ограничитель: до 0,6 — без изменений, выше — плавно к 1 (громкие записи не хрипят при усилении). */
    static float limit(float s) {
        float a = Math.abs(s);
        if (a <= 0.6f) {
            return s;
        }
        float out = 0.6f + 0.4f * (float) Math.tanh((a - 0.6f) / 0.4f);
        return s < 0 ? -out : out;
    }

    /** Множитель громкости голосовых и видео: своя настройка (0..300 %) × общая громкость игры. */
    public static float mediaGain() {
        return PhoneSettings.get().mediaVolume / 100f * Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER);
    }

    /** Изменить громкость голосовых и видео на delta процентов (0..300) и сохранить. */
    public static void changeMediaVolume(int delta) {
        PhoneSettings s = PhoneSettings.get();
        s.mediaVolume = Math.max(0, Math.min(300, s.mediaVolume + delta));
        s.save();
    }
}
