package dev.blockphone.fabric.client.telegram;

import com.mojang.blaze3d.platform.NativeImage;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.BlockPhoneClient;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.music.AudioTap;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.storage.ClientFiles;
import dev.blockphone.fabric.client.voice.Adpcm;
import dev.blockphone.fabric.client.voice.VoiceRecorder;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * «Кружочек» для Telegram из кадров игры: телефон закрывается, в центре экрана — красное кольцо и таймер.
 * Кадры берутся до отрисовки интерфейса (только мир), квадрат из середины, 320×320, 15 кадров/с, до 15 секунд;
 * кодирование в MP4 — в отдельном потоке ({@link VideoNoteEncoder}). Клавиша телефона — отправить, меню (Esc) — отмена.
 */
public final class VideoNoteRecorder {
    public static final int SIZE = 320;
    private static final int FPS = 15;
    private static final long MAX_MS = 15_000;

    private static volatile boolean active;
    private static volatile boolean finishing;
    private static volatile boolean cancelled;
    private static long chatId;
    private static long startedAt;
    private static long nextCapture;
    private static BlockingQueue<int[]> queue;
    private static Path file;
    /** Звук: микрофон (16 кГц) и копия играющей музыки (48 кГц) — по настройкам videoNoteMic/videoNoteMusic. */
    private static volatile VoiceRecorder mic;
    private static volatile short[] musicPcm;

    public static void init() {
        // Самый первый элемент HUD: мир уже нарисован, интерфейс ещё нет — снимаем кадр.
        HudElementRegistry.addFirst(Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "video_note_capture"),
                (g, delta) -> capture());
        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "video_note_overlay"),
                VideoNoteRecorder::renderOverlay);
    }

    public static boolean active() {
        return active;
    }

    /** Начать запись «кружочка» для чата (телефон закрывается). */
    public static void start(long chat) {
        if (active || finishing) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        chatId = chat;
        cancelled = false;
        finishing = false;
        queue = new ArrayBlockingQueue<>(60);
        file = ClientFiles.root().resolve("telegram").resolve("videonotes")
                .resolve("note_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".mp4");
        BlockingQueue<int[]> frames = queue;
        Path out = file;
        Thread encoder = new Thread(() -> encode(frames, out), "BlockPhone Video note");
        encoder.setDaemon(true);
        encoder.start();
        mc.setScreen(null);
        PhoneSettings settings = PhoneSettings.get();
        musicPcm = null;
        mic = settings.videoNoteMic ? VoiceRecorder.start((int) (MAX_MS / 1000) + 2) : null;
        if (settings.videoNoteMusic) {
            AudioTap.begin();
        }
        startedAt = System.currentTimeMillis();
        nextCapture = startedAt;
        active = true;
    }

    /** Закончить и отправить. */
    public static void stop() {
        if (active) {
            VoiceRecorder m = mic;
            if (m != null) {
                m.stop();
            }
            musicPcm = AudioTap.end();
            active = false;
            finishing = true;
        }
    }

    /** Отменить без отправки. */
    public static void cancel() {
        if (active) {
            cancelled = true;
            stop();
        }
    }

    /** Каждый тик: открыли меню — отмена; вышло время — отправка. */
    public static void tick() {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.level == null) {
            cancel();
        } else if (System.currentTimeMillis() - startedAt >= MAX_MS) {
            stop();
        }
    }

    private static void capture() {
        if (!active) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextCapture) {
            return;
        }
        nextCapture += 1000 / FPS;
        if (nextCapture < now) {
            nextCapture = now + 1000 / FPS; // Игра тормозит — не пытаться догонять.
        }
        BlockingQueue<int[]> frames = queue;
        Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(), image -> {
            try (NativeImage img = image) {
                int[] square = VideoNoteEncoder.cropSquare(img.getPixels(), img.getWidth(), img.getHeight(), SIZE);
                frames.offer(square);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Video note frame failed", e);
            }
        });
    }

    private static void encode(BlockingQueue<int[]> frames, Path out) {
        int count = 0;
        String error = null;
        try {
            Files.createDirectories(out.getParent());
            try (VideoNoteEncoder encoder = new VideoNoteEncoder(out, SIZE, FPS)) {
                while (true) {
                    int[] frame = frames.poll(100, TimeUnit.MILLISECONDS);
                    if (frame != null) {
                        if (!cancelled) {
                            encoder.addFrame(frame);
                            count++;
                        }
                    } else if (finishing && !active) {
                        break;
                    }
                }
            }
            if (!cancelled && count > 0) {
                addSound(out, count);
            }
        } catch (IOException | RuntimeException e) {
            BlockPhoneFabric.LOGGER.warn("Video note encoding failed", e);
            error = "telegram.video_note_failed";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int frameCount = count;
        String failure = error;
        Minecraft.getInstance().execute(() -> finished(out, frameCount, failure));
    }

    /** Свести микрофон и музыку и дописать дорожку Opus; не вышло — «кружочек» уйдёт без звука. */
    private static void addSound(Path out, int frames) {
        try {
            VoiceRecorder m = mic;
            short[] voice = new short[0];
            if (m != null) {
                for (int i = 0; i < 200 && !m.finished(); i++) {
                    Thread.sleep(10);
                }
                if (m.finished() && m.error() == null) {
                    voice = m.samples();
                }
            }
            short[] music = musicPcm != null ? musicPcm : new short[0];
            short[] track = soundtrack(voice, music, (int) ((long) frames * AudioTap.RATE / FPS));
            if (track == null) {
                return;
            }
            OggOpus.Encoded encoded = OggOpus.encodePackets(track, AudioTap.RATE, 64_000, false);
            Mp4Audio.addOpusTrack(out, encoded.packets(), encoded.preSkip());
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("Video note sound failed (sending without sound)", e);
        }
    }

    /**
     * Звуковая дорожка «кружочка»: микрофон (моно 16 кГц) + музыка (моно 48 кГц, уже с громкостью игрока), длина — как
     * у видео. null — звука нет совсем (всё выключено или тишина).
     */
    static short[] soundtrack(short[] voice16k, short[] music48k, int length) {
        if (length <= 0 || (voice16k.length == 0 && music48k.length == 0)) {
            return null;
        }
        short[] voice = voice16k.length > 0 ? Adpcm.resample(voice16k, 16_000, AudioTap.RATE) : new short[0];
        short[] out = new short[length];
        boolean any = false;
        for (int i = 0; i < length; i++) {
            float v = (i < voice.length ? voice[i] * 1.2f : 0) + (i < music48k.length ? music48k[i] * 0.8f : 0);
            int s = Math.max(-32768, Math.min(32767, Math.round(v)));
            out[i] = (short) s;
            any |= s != 0;
        }
        return any ? out : null;
    }

    private static void finished(Path out, int frames, String error) {
        finishing = false;
        if (cancelled || error != null || frames < FPS / 2) {
            try {
                Files.deleteIfExists(out);
            } catch (IOException ignored) {
            }
            if (!cancelled) {
                toast(PhoneLang.tr(error != null ? error : "telegram.video_note_short"));
            }
            return;
        }
        int seconds = Math.max(1, Math.round(frames / (float) FPS));
        Telegram.sendVideoNote(chatId, out, seconds, SIZE);
        toast(PhoneLang.tr("telegram.video_note_sent"));
    }

    private static void toast(String text) {
        Minecraft mc = Minecraft.getInstance();
        SystemToast.add(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Telegram"), Component.literal(text));
    }

    /** Красное кольцо вокруг записываемого квадрата, таймер и подсказка. */
    private static void renderOverlay(GuiGraphics g, DeltaTracker delta) {
        if (!active) {
            return;
        }
        int w = g.guiWidth();
        int h = g.guiHeight();
        int side = Math.min(w, h);
        int cx = w / 2;
        int cy = h / 2;
        int r = side / 2 - 2;
        int color = 0xE0E5534B;
        for (int dy = -r; dy <= r; dy++) {
            int outer = (int) Math.round(Math.sqrt((double) r * r - (double) dy * dy));
            int ir = r - 2;
            int inner = Math.abs(dy) <= ir ? (int) Math.round(Math.sqrt((double) ir * ir - (double) dy * dy)) : 0;
            g.fill(cx - outer, cy + dy, cx - inner, cy + dy + 1, color);
            g.fill(cx + inner, cy + dy, cx + outer, cy + dy + 1, color);
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        boolean blink = (elapsed / 500) % 2 == 0;
        String time = (blink ? "● " : "  ") + elapsed / 1000 + " / " + MAX_MS / 1000 + " c";
        PhoneDraw.centeredText(g, time, cx, cy - r + 8, 0xFFFFFFFF, 1f, true);
        String key = BlockPhoneClient.openPhoneKey.getTranslatedKeyMessage().getString();
        PhoneDraw.centeredText(g, PhoneLang.tr("telegram.video_note_hint", key), cx, cy + r - 14, 0xFFE6ECF5, 0.75f, true);
    }

    private VideoNoteRecorder() {
    }
}
