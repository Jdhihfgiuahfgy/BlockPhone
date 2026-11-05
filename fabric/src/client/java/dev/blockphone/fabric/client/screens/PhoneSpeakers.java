package dev.blockphone.fabric.client.screens;

import com.google.gson.JsonObject;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.config.PhoneSettings;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusSignal;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleSupplier;

/**
 * Колонки телефона: музыка телефона (проигрыватель «Музыки» — свои файлы, Яндекс) звучит из колонок владельца для всех рядом.
 * У владельца {@link #feed} получает каждый кусок, который проигрыватель отдаёт звуковой карте, сводит в стерео 48 кГц, кладёт
 * в свой буфер (свои колонки) и сжимает Opus (стерео 96 кбит/с, кадры по 20 мс); пачки по 10 кадров (0,2 с) уходят всем той же
 * дорогой, что и экраны ({@link ScreenSync}). У остальных — разжать в буфер владельца и играть из его колонок объёмно.
 */
public final class PhoneSpeakers {
    private static final int RATE = 48_000;
    private static final int FRAME = 960;
    private static final int BATCH = 10;
    private static final long STREAM_EXPIRE_MS = 30_000;

    // --- Владелец (запись — поток проигрывателя) ---
    private static volatile boolean sending;
    private static volatile boolean muteLocal;
    private static volatile float sendGain = 1;
    private static final ScreenAudio LOCAL = new ScreenAudio(RATE, 160, 700);
    private static @Nullable OpusEncoder encoder;
    private static final short[] frame = new short[FRAME * 2];
    private static int frameFill;
    private static final byte[] packet = new byte[1500];
    private static final ByteArrayOutputStream batch = new ByteArrayOutputStream();
    private static int batched;
    private static int seq;
    /** DevShots: свои пачки разжимаются как чужие (проверка сети без второго игрока). */
    public static volatile boolean debugLoopback;

    // --- Слушатели (поток рендера) ---
    private static final class Stream {
        final ScreenAudio audio = new ScreenAudio(RATE, 300, 1200);
        final OpusDecoder decoder;
        final short[] pcm = new short[FRAME * 6 * 2];
        long last;

        Stream() {
            try {
                decoder = new OpusDecoder(RATE, 2);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final Map<String, Stream> STREAMS = new HashMap<>();
    private static final Map<String, StreamSound> SOUNDS = new LinkedHashMap<>();

    /** Проигрыватель телефона молчит «в ушах» владельца (музыка идёт только из колонок). */
    public static boolean muteLocal() {
        return muteLocal;
    }

    /** Звук колонок телефона игрока (свои — owner null) для «качания» от басов; null — нет. */
    static @Nullable ScreenAudio audioOf(@Nullable String owner) {
        if (owner == null) {
            return sending ? LOCAL : null;
        }
        Stream s = STREAMS.get(owner.toLowerCase(Locale.ROOT));
        return s == null ? null : s.audio;
    }

    /** Сколько каналов в музыке телефона (для приложения). */
    public static int localChannels() {
        return LOCAL.channels();
    }

    /** Свои колонки телефона сейчас играют (для приложения). */
    public static boolean sending() {
        return sending;
    }

    /** Из потока проигрывателя: samples — чередующиеся каналы до громкости игры, n — число значений. */
    public static void feed(short[] samples, int n, int channels, int rate) {
        if (!sending || channels <= 0 || rate <= 0) {
            return;
        }
        synchronized (PhoneSpeakers.class) {
            float g = sendGain;
            int frames = n / channels;
            int out = (int) ((long) frames * RATE / rate);
            for (int i = 0; i < out; i++) {
                int src = (int) ((long) i * rate / RATE) * channels;
                float l;
                float r;
                if (channels == 1) {
                    l = r = samples[src];
                } else if (channels == 6) {
                    // Vorbis 5.1: L, C, R, тыл L, тыл R, LFE → стерео.
                    l = samples[src] + 0.7f * samples[src + 1] + 0.7f * samples[src + 3];
                    r = samples[src + 2] + 0.7f * samples[src + 1] + 0.7f * samples[src + 4];
                } else {
                    l = samples[src];
                    r = samples[src + 1];
                }
                frame[frameFill * 2] = (short) Math.max(-32768, Math.min(32767, Math.round(l * g)));
                frame[frameFill * 2 + 1] = (short) Math.max(-32768, Math.min(32767, Math.round(r * g)));
                if (++frameFill == FRAME) {
                    frameFill = 0;
                    LOCAL.push(frame, 0, FRAME, 2);
                    encode();
                }
            }
        }
    }

    private static void encode() {
        try {
            if (encoder == null) {
                encoder = new OpusEncoder(RATE, 2, OpusApplication.OPUS_APPLICATION_AUDIO);
                encoder.setBitrate(96_000);
                encoder.setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC);
                encoder.setComplexity(5);
            }
            int len = encoder.encode(frame, 0, FRAME, packet, 0, packet.length);
            batch.write(len >> 8);
            batch.write(len & 0xFF);
            batch.write(packet, 0, len);
            if (++batched >= BATCH) {
                JsonObject o = new JsonObject();
                o.addProperty("t", "pa");
                o.addProperty("q", seq++);
                o.addProperty("d", Base64.getEncoder().encodeToString(batch.toByteArray()));
                batch.reset();
                batched = 0;
                String payload = o.toString();
                Minecraft.getInstance().execute(() -> {
                    ScreenSync.sendAudio(payload);
                    if (debugLoopback) {
                        onAudio("loopback", com.google.gson.JsonParser.parseString(payload).getAsJsonObject());
                    }
                });
            }
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.debug("[screens] phone audio encode: {}", e.toString());
            batch.reset();
            batched = 0;
        }
    }

    /** Пачка звука телефона другого игрока. */
    static void onAudio(String from, JsonObject o) {
        String key = from.toLowerCase(Locale.ROOT);
        Stream stream = STREAMS.computeIfAbsent(key, k -> new Stream());
        stream.last = System.currentTimeMillis();
        try {
            byte[] data = Base64.getDecoder().decode(o.get("d").getAsString());
            int pos = 0;
            while (pos + 2 <= data.length) {
                int len = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
                pos += 2;
                if (len <= 0 || pos + len > data.length) {
                    break;
                }
                int samples = stream.decoder.decode(data, pos, len, stream.pcm, 0, FRAME * 6, false);
                if (samples > 0) {
                    stream.audio.push(stream.pcm, 0, samples, 2);
                }
                pos += len;
            }
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.debug("[screens] phone audio from {}: {}", from, e.toString());
        }
    }

    /** Каждый такт: что слать, какие колонки звучат. */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            sending = false;
            muteLocal = false;
            stopAll();
            STREAMS.clear();
            return;
        }
        String dim = mc.level.dimension().identifier().toString();
        Vec3 eye = mc.player.getEyePosition();
        PhoneSettings settings = PhoneSettings.get();
        Map<String, Source> want = new HashMap<>();
        ScreenData.Set own = ScreenStore.data();
        boolean any = false;
        for (ScreenData.Speaker sp : own.speakers) {
            if (sp.screen.equals(ScreenData.PHONE) && own.speakerOn(sp)) {
                any = true;
                if (own.phone.on && dim.equals(sp.dim)) {
                    add(want, "me", sp, own.phone, eye, LOCAL, () -> ScreenStore.data().phone.volume / 100.0 * sp.volume / 100.0
                            * ScreenStore.data().speakerGain(sp), own.speakerGain(sp));
                }
            }
        }
        sending = any && own.phone.on;
        muteLocal = sending && !own.phone.local;
        sendGain = settings.musicVolume * settings.musicVolume;
        if (settings.screensOthers) {
            double others = settings.screensOthersVolume / 100.0;
            for (ScreenSync.Remote r : ScreenSync.remotes().values()) {
                ScreenData.PhoneOut out = r.data().phone;
                if (!out.on) {
                    continue;
                }
                String key = r.owner().toLowerCase(Locale.ROOT);
                for (ScreenData.Speaker sp : r.data().speakers) {
                    if (sp.screen.equals(ScreenData.PHONE) && r.data().speakerOn(sp) && dim.equals(sp.dim)) {
                        ScreenAudio audio = STREAMS.computeIfAbsent(key, k -> new Stream()).audio;
                        double group = r.data().speakerGain(sp);
                        add(want, key, sp, out, eye, audio, () -> out.volume / 100.0 * sp.volume / 100.0 * group * others, group);
                    }
                }
            }
        }
        long now = System.currentTimeMillis();
        STREAMS.entrySet().removeIf(e -> now - e.getValue().last > STREAM_EXPIRE_MS && !ScreenSync.remotes().containsKey(e.getKey()));
        SOUNDS.entrySet().removeIf(e -> {
            boolean keep = want.containsKey(e.getKey()) && !e.getValue().dead();
            if (!keep) {
                e.getValue().finish();
            }
            return !keep;
        });
        for (Map.Entry<String, Source> e : want.entrySet()) {
            if (!SOUNDS.containsKey(e.getKey())) {
                Source s = e.getValue();
                StreamSound sound = new StreamSound(s.audio, s.audio.reader(s.pick), s.pos, s.radius, s.gain);
                SOUNDS.put(e.getKey(), sound);
                sound.start();
            }
        }
    }

    private record Source(ScreenAudio audio, Vec3 pos, int radius, DoubleSupplier gain, ScreenAudio.Pick pick) {
    }

    private static void add(Map<String, Source> want, String owner, ScreenData.Speaker sp, ScreenData.PhoneOut out, Vec3 eye,
                            ScreenAudio audio, DoubleSupplier gain, double groupGain) {
        Vec3 c = sp.center();
        double reach = out.radius + 8;
        if (c.distanceToSqr(eye) < reach * reach) {
            ScreenAudio.Pick pick = ScreenAudio.Pick.of(sp.channel);
            want.put(owner + "/" + sp.id + ":" + sp.x + "," + sp.y + "," + sp.z + "@" + out.radius + "#" + sp.volume + "*"
                    + Math.round(groupGain * 100) + "/" + pick,
                    new Source(audio, c, out.radius, gain, pick));
        }
    }

    static String debugStatus() {
        StringBuilder streams = new StringBuilder();
        STREAMS.forEach((k, v) -> streams.append(k).append(v.audio.live() ? "(live)" : "(silent)").append(' '));
        return "sending=" + sending + " muteLocal=" + muteLocal + " localLive=" + LOCAL.live() + " streams=" + streams
                + " sounds=" + SOUNDS.keySet() + " active=" + SOUNDS.values().stream().filter(x -> !x.dead()).count();
    }

    static void stopAll() {
        for (StreamSound s : SOUNDS.values()) {
            s.finish();
        }
        SOUNDS.clear();
    }

    private PhoneSpeakers() {
    }
}
