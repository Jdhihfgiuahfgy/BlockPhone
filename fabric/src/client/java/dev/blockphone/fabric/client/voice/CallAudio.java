package dev.blockphone.fabric.client.voice;

import dev.blockphone.common.protocol.data.VoiceData;
import dev.blockphone.common.protocol.packet.VoicePackets;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.network.ClientNetwork;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Звук звонков BlockPhone (без других модов): свой микрофон → Opus 16 кГц (кадры по 20 мс, по два в пакете) → сервер;
 * звук от сервера → декодер на каждый голос → небольшой буфер против рывков сети → общий микшер → динамики.
 * Тишина не отправляется (порог громкости с «хвостом» 0,3 с). Микрофон выключен — запись не идёт.
 * Голоса из точки мира (громкая связь соседа) тише с расстоянием. Потоки: «BlockPhone Call mic» и «BlockPhone Call mixer».
 */
public final class CallAudio {
    private static final int RATE = VoiceData.SAMPLE_RATE;
    private static final int FRAME = RATE / 50;
    private static final int FRAMES_PER_PACKET = 2;
    private static final int[] MIC_RATES = {RATE, 48_000, 44_100};
    /** Порог голоса (RMS по 16-битной шкале) и сколько ещё слать после тишины. */
    private static final double VOICE_RMS = 260;
    private static final long HANGOVER_MS = 300;
    /** Буфер против рывков: начать играть голос, когда накопилось столько кадров; больше максимума — выбросить старые. */
    private static final int START_FRAMES = 3;
    private static final int MAX_FRAMES = 15;
    /** Радиус, на котором слышно громкую связь соседа (блоков). */
    private static final double SPEAKER_RANGE = 10;

    /** Голос одного игрока (или одного источника в мире). */
    private static final class Voice {
        final UUID from;
        final UUID at;
        final OpusDecoder decoder;
        final int codec;
        final ConcurrentLinkedDeque<short[]> frames = new ConcurrentLinkedDeque<>();
        volatile boolean started;
        volatile long heardAt;
        volatile float gain = 1;

        Voice(UUID from, UUID at, int codec) throws OpusException {
            this.from = from;
            this.at = at;
            this.codec = codec;
            this.decoder = new OpusDecoder(codec == VoiceData.CODEC_SVC_OPUS48 ? 48_000 : RATE, 1);
        }
    }

    private static final Map<String, Voice> VOICES = new ConcurrentHashMap<>();
    private static volatile long chatId = -1;
    private static volatile boolean running;
    private static volatile boolean micOn;
    private static volatile float micLevel;
    private static volatile long sentAt;
    private static volatile @Nullable String micError;
    private static Thread micThread;
    private static Thread mixerThread;
    /** Когда последний раз слышали голос звонка из мира (громкая связь соседа) — для микшера без своего звонка. */
    private static volatile long worldHeardAt;

    // --- Свой звонок ---

    /** Вошли в звонок: запустить микрофон (если не выключен) и микшер. */
    public static synchronized void start(long chat, boolean muted) {
        chatId = chat;
        running = true;
        micError = null;
        setMuted(muted);
        ensureMixer();
    }

    /** Вышли из звонка. */
    public static synchronized void stop() {
        chatId = -1;
        running = false;
        micOn = false;
        micLevel = 0;
        VOICES.values().removeIf(v -> v.at.equals(VoiceData.NOBODY));
    }

    public static synchronized void setMuted(boolean muted) {
        boolean want = running && !muted;
        if (want == micOn) {
            return;
        }
        micOn = want;
        if (want && (micThread == null || !micThread.isAlive())) {
            micThread = new Thread(CallAudio::captureLoop, "BlockPhone Call mic");
            micThread.setDaemon(true);
            micThread.start();
        }
        if (!want) {
            micLevel = 0;
        }
    }

    /** Громкость с микрофона 0..1 (для индикатора). */
    public static float micLevel() {
        return micOn ? micLevel : 0;
    }

    /** Свой голос сейчас уходит в звонок. */
    public static boolean speaking() {
        return micOn && System.currentTimeMillis() - sentAt < 250;
    }

    /** Игрок говорит (его голос звучал за последние 0,3 с). */
    public static boolean speaking(UUID player) {
        long now = System.currentTimeMillis();
        for (Voice v : VOICES.values()) {
            if (v.from.equals(player) && now - v.heardAt < 300) {
                return true;
            }
        }
        return false;
    }

    public static @Nullable String micError() {
        return micError;
    }

    // --- Микрофон ---

    private static void captureLoop() {
        TargetDataLine line = null;
        int rate = 0;
        try {
            for (int candidate : MIC_RATES) {
                AudioFormat format = new AudioFormat(candidate, 16, 1, true, false);
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                if (AudioSystem.isLineSupported(info)) {
                    line = (TargetDataLine) AudioSystem.getLine(info);
                    line.open(format, candidate / 10 * 2);
                    rate = candidate;
                    break;
                }
            }
            if (line == null) {
                micError = "voice.error.no_microphone";
                return;
            }
            line.start();
            OpusEncoder encoder = new OpusEncoder(RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            encoder.setBitrate(24_000);
            encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
            encoder.setComplexity(5);
            int inFrame = rate / 50;
            byte[] raw = new byte[inFrame * 2];
            short[] samples = new short[inFrame];
            byte[] packet = new byte[255];
            ByteArrayOutputStream batch = new ByteArrayOutputStream();
            int batched = 0;
            int seq = 0;
            long voiceUntil = 0;
            while (micOn) {
                int read = 0;
                while (read < raw.length && micOn) {
                    int n = line.read(raw, read, raw.length - read);
                    if (n <= 0) {
                        break;
                    }
                    read += n;
                }
                if (!micOn) {
                    break;
                }
                for (int i = 0; i < inFrame; i++) {
                    samples[i] = (short) ((raw[i * 2] & 0xFF) | raw[i * 2 + 1] << 8);
                }
                short[] frame = rate == RATE ? samples.clone() : Adpcm.resample(samples, rate, RATE);
                if (frame.length != FRAME) {
                    frame = java.util.Arrays.copyOf(frame, FRAME);
                }
                double sum = 0;
                for (short s : frame) {
                    sum += (double) s * s;
                }
                double rms = Math.sqrt(sum / FRAME);
                micLevel = (float) Math.min(1, rms / 6000);
                long now = System.currentTimeMillis();
                if (rms > VOICE_RMS) {
                    voiceUntil = now + HANGOVER_MS;
                }
                if (now > voiceUntil) {
                    // Тишина: недособранный пакет — отправить, дальше молчим.
                    if (batched > 0) {
                        send(batch.toByteArray(), seq++);
                        batch.reset();
                        batched = 0;
                    }
                    continue;
                }
                int n = encoder.encode(frame, 0, FRAME, packet, 0, packet.length);
                batch.write(n);
                batch.write(packet, 0, n);
                if (++batched >= FRAMES_PER_PACKET) {
                    send(batch.toByteArray(), seq++);
                    batch.reset();
                    batched = 0;
                }
            }
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException | OpusException e) {
            BlockPhoneFabric.LOGGER.warn("Call microphone unavailable", e);
            micError = "voice.error.no_microphone";
        } finally {
            if (line != null) {
                line.stop();
                line.close();
            }
            micLevel = 0;
        }
    }

    private static void send(byte[] data, int seq) {
        long chat = chatId;
        if (chat < 0 || data.length == 0) {
            return;
        }
        sentAt = System.currentTimeMillis();
        Minecraft.getInstance().execute(() -> ClientNetwork.send(new VoicePackets.CallAudio(chat, seq, data)));
    }

    // --- Приём ---

    /** Звук от сервера (поток рендера): разобрать кадры, декодировать в очередь голоса. */
    public static void onAudio(VoicePackets.CallAudioFrom p) {
        boolean world = !p.at().equals(VoiceData.NOBODY);
        if (!world && p.chatId() != chatId) {
            return;
        }
        String key = p.from() + "|" + p.at() + "|" + p.codec();
        Voice voice = VOICES.get(key);
        try {
            if (voice == null) {
                voice = new Voice(p.from(), p.at(), p.codec());
                VOICES.put(key, voice);
            }
            if (p.codec() == VoiceData.CODEC_SVC_OPUS48) {
                short[] big = new short[960 * 6];
                int n = voice.decoder.decode(p.data(), 0, p.data().length, big, 0, 960 * 6, false);
                // 48 кГц → 16 кГц: среднее по трём отсчётам.
                for (int off = 0; off + 960 <= n; off += 960) {
                    short[] frame = new short[FRAME];
                    for (int i = 0; i < FRAME; i++) {
                        frame[i] = (short) ((big[off + i * 3] + big[off + i * 3 + 1] + big[off + i * 3 + 2]) / 3);
                    }
                    queue(voice, frame);
                }
            } else {
                byte[] data = p.data();
                int pos = 0;
                while (pos < data.length) {
                    int len = data[pos++] & 0xFF;
                    if (len == 0 || pos + len > data.length) {
                        break;
                    }
                    short[] frame = new short[FRAME * 3];
                    int n = voice.decoder.decode(data, pos, len, frame, 0, FRAME * 3, false);
                    pos += len;
                    queue(voice, n == FRAME ? frame : java.util.Arrays.copyOf(frame, FRAME));
                }
            }
        } catch (OpusException | RuntimeException e) {
            BlockPhoneFabric.LOGGER.debug("Bad call audio from {}: {}", p.from(), e.toString());
            return;
        }
        voice.heardAt = System.currentTimeMillis();
        if (world) {
            worldHeardAt = voice.heardAt;
        }
        ensureMixer();
    }

    private static void queue(Voice voice, short[] frame) {
        voice.frames.addLast(frame);
        while (voice.frames.size() > MAX_FRAMES) {
            voice.frames.pollFirst();
        }
        if (voice.frames.size() >= START_FRAMES) {
            voice.started = true;
        }
    }

    /** Каждый тик (поток рендера): громкость голосов из мира по расстоянию, уборка старых голосов. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        VOICES.values().removeIf(v -> now - v.heardAt > 30_000);
        for (Voice v : VOICES.values()) {
            if (v.at.equals(VoiceData.NOBODY)) {
                v.gain = 1;
                continue;
            }
            Player source = mc.level != null ? mc.level.getPlayerByUUID(v.at) : null;
            if (source == null || mc.player == null) {
                v.gain = 0;
                continue;
            }
            double d = Math.sqrt(source.distanceToSqr(mc.player));
            v.gain = (float) Math.max(0, 1 - d / SPEAKER_RANGE);
        }
    }

    // --- Микшер ---

    private static synchronized void ensureMixer() {
        if (mixerThread == null || !mixerThread.isAlive()) {
            mixerThread = new Thread(CallAudio::mixLoop, "BlockPhone Call mixer");
            mixerThread.setDaemon(true);
            mixerThread.start();
        }
    }

    /** Пока идёт звонок или звучит громкая связь соседа — смешивать кадры голосов по 20 мс и играть. */
    private static void mixLoop() {
        AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
        try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
            line.open(format, FRAME * 2 * 6);
            line.start();
            int[] mix = new int[FRAME];
            byte[] out = new byte[FRAME * 2];
            while (running || System.currentTimeMillis() - worldHeardAt < 3000) {
                java.util.Arrays.fill(mix, 0);
                PhoneSettings s = PhoneSettings.get();
                float master = s.callDeafened ? 0 : Math.max(0, s.callVolume / 100f);
                for (Voice v : VOICES.values()) {
                    if (!v.started) {
                        continue;
                    }
                    short[] frame = v.frames.pollFirst();
                    if (frame == null) {
                        // Очередь кончилась — снова копим буфер.
                        v.started = false;
                        continue;
                    }
                    float gain = master * v.gain;
                    for (int i = 0; i < FRAME; i++) {
                        mix[i] += (int) (frame[i] * gain);
                    }
                }
                for (int i = 0; i < FRAME; i++) {
                    int value = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mix[i]));
                    out[i * 2] = (byte) value;
                    out[i * 2 + 1] = (byte) (value >> 8);
                }
                // write блокируется, пока в линии нет места, — так микшер идёт в реальном времени.
                line.write(out, 0, out.length);
            }
            line.drain();
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            BlockPhoneFabric.LOGGER.warn("Call audio output unavailable", e);
        }
    }

    private CallAudio() {
    }
}
