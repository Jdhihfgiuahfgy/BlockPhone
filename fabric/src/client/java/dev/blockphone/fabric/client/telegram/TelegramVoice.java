package dev.blockphone.fabric.client.telegram;

import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.storage.ClientFiles;
import dev.blockphone.fabric.client.voice.VoiceMessages;
import it.tdlight.jni.TdApi;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Голосовые Telegram: проигрывание (файл скачивает TDLib, OGG Opus → PCM в фоне, звук — общий проигрыватель
 * {@link VoiceMessages}, музыка на паузе) и отправка записи с микрофона (PCM → OGG Opus → файл → TDLib).
 */
public final class TelegramVoice {
    private static final Map<Integer, short[]> PCM = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, short[]> eldest) {
            return size() > 20;
        }
    };
    private static final Set<Integer> DECODING = new HashSet<>();
    private static final Set<Integer> FAILED = new HashSet<>();
    private static @Nullable TdApi.File waiting;

    /** Ключ в общем проигрывателе (не пересекается с id голосовых сервера). */
    private static long key(int fileId) {
        return -1_000_000L - fileId;
    }

    /** Нажали на голосовое: играть / пауза; если ещё не скачано — скачать и сыграть. */
    public static void toggle(TdApi.VoiceNote voice) {
        int id = voice.voice.id;
        short[] pcm = PCM.get(id);
        if (pcm != null) {
            waiting = null;
            VoiceMessages.togglePcm(key(id), pcm);
            return;
        }
        FAILED.remove(id);
        waiting = waiting != null && waiting.id == id ? null : voice.voice;
    }

    /** Каждый кадр (из экрана чата): ждём скачивания и распаковки того, что нажали. */
    public static void tick() {
        TdApi.File file = waiting;
        if (file == null) {
            return;
        }
        Path path = Telegram.localFile(file);
        int id = file.id;
        if (path == null || !DECODING.add(id)) {
            return;
        }
        ClientFiles.IO.execute(() -> {
            short[] pcm;
            try {
                pcm = OggOpus.decode(Files.readAllBytes(path));
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Telegram voice {} is not playable: {}", path.getFileName(), e.toString());
                pcm = null;
            }
            short[] result = pcm;
            Minecraft.getInstance().execute(() -> {
                DECODING.remove(id);
                if (result == null) {
                    FAILED.add(id);
                    if (waiting != null && waiting.id == id) {
                        waiting = null;
                    }
                    return;
                }
                PCM.put(id, result);
                if (waiting != null && waiting.id == id) {
                    waiting = null;
                    VoiceMessages.togglePcm(key(id), result);
                }
            });
        });
    }

    public static boolean playing(TdApi.VoiceNote voice) {
        return VoiceMessages.playing(key(voice.voice.id));
    }

    public static boolean loading(TdApi.VoiceNote voice) {
        return waiting != null && waiting.id == voice.voice.id;
    }

    public static boolean failed(TdApi.VoiceNote voice) {
        return FAILED.contains(voice.voice.id);
    }

    public static float progress(TdApi.VoiceNote voice) {
        return VoiceMessages.progress(key(voice.voice.id));
    }

    /** Сжать запись (моно 16 кГц) и отправить голосовым (в фоне). */
    public static void send(long chatId, short[] pcm, long replyTo) {
        if (pcm.length < OggOpus.RATE / 2) {
            return; // короче полсекунды — случайное нажатие
        }
        ClientFiles.IO.execute(() -> {
            try {
                Path dir = ClientFiles.root().resolve("telegram").resolve("voice");
                Files.createDirectories(dir);
                Path file = dir.resolve("voice_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".ogg");
                Files.write(file, OggOpus.encode(pcm));
                byte[] waveform = OggOpus.waveform(pcm);
                int seconds = Math.max(1, Math.round(pcm.length / (float) OggOpus.RATE));
                Minecraft.getInstance().execute(() -> Telegram.sendVoice(chatId, file, seconds, waveform, replyTo));
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Telegram voice encoding failed", e);
            }
        });
    }

    private TelegramVoice() {
    }
}
