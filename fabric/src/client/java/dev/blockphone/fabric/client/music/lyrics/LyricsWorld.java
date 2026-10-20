package dev.blockphone.fabric.client.music.lyrics;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Текст песни в мире: строка появляется перед игроком (над блоками) и остаётся на месте («в мире») или следует за взглядом;
 * слова возникают по одному в такт песне, последнее — акцентным цветом; закончившаяся строка гаснет и уплывает вверх.
 * Настройки — {@code PhoneSettings.lyrics*}.
 */
public final class LyricsWorld {
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final long POP_MS = 180;
    private static final long FADE_MS = 700;
    /** Пикселей шрифта на блок при размере 1..4 и расстоянии 6 блоков. */
    private static final float[] SIZES = {0.018f, 0.026f, 0.036f, 0.05f};

    private static int shownLine = -1;
    private static Vec3 anchor = Vec3.ZERO;
    private static float yaw;
    private static float pitch;
    private static int fadingLine = -1;
    private static Vec3 fadingAnchor = Vec3.ZERO;
    private static float fadingYaw;
    private static float fadingPitch;
    private static long fadingSince;
    private static Lyrics shownFor;

    public static void init() {
        WorldRenderEvents.BEFORE_ENTITIES.register(LyricsWorld::render);
    }

    private static void render(WorldRenderContext context) {
        Minecraft mc = Minecraft.getInstance();
        PhoneSettings settings = PhoneSettings.get();
        Lyrics lyrics = LyricsService.current();
        if (!settings.lyricsWorld || lyrics == null || (!lyrics.synced() && !settings.lyricsUnsynced) || mc.player == null
                || mc.options.hideGui
                || MusicPlayer.state() == MusicPlayer.State.STOPPED) {
            shownLine = -1;
            fadingLine = -1;
            return;
        }
        if (lyrics != shownFor) {
            shownFor = lyrics;
            shownLine = -1;
            fadingLine = -1;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        long pos = LyricsService.positionMs();
        long now = System.currentTimeMillis();
        int line = lyrics.lineAt(pos);
        boolean follow = "view".equals(settings.lyricsMode);
        if (line != shownLine) {
            if (shownLine >= 0) {
                fadingLine = shownLine;
                fadingAnchor = anchor;
                fadingYaw = yaw;
                fadingPitch = pitch;
                fadingSince = now;
            }
            shownLine = line;
            if (line >= 0) {
                place(camera, settings);
            }
        }
        if (follow && shownLine >= 0) {
            place(camera, settings);
        }

        Font font = mc.font;
        PoseStack pose = context.matrices();
        Vec3 cam = camera.position();
        if (fadingLine >= 0 && fadingLine < lyrics.lines().size()) {
            float t = (now - fadingSince) / (float) FADE_MS;
            if (t >= 1) {
                fadingLine = -1;
            } else {
                Lyrics.Line l = lyrics.lines().get(fadingLine);
                drawLine(context, pose, font, cam, fadingAnchor.add(0, t * 0.6, 0), fadingYaw, fadingPitch, l, fadingLine,
                        Long.MAX_VALUE, 1 - t, settings);
            }
        }
        if (shownLine >= 0) {
            drawLine(context, pose, font, cam, anchor, yaw, pitch, lyrics.lines().get(shownLine), shownLine, pos, 1, settings);
        }
    }

    /** Точка перед игроком: по направлению взгляда (наклон ограничен), чуть выше глаз. */
    private static void place(Camera camera, PhoneSettings settings) {
        yaw = camera.yRot();
        pitch = Math.max(-25, Math.min(20, camera.xRot()));
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        anchor = camera.position().add(dir.scale(Math.max(2, settings.lyricsDistance))).add(0, 0.4, 0);
    }

    private static void drawLine(WorldRenderContext context, PoseStack pose, Font font, Vec3 cam, Vec3 at, float yaw, float pitch,
                                 Lyrics.Line line, int index, long pos, float alpha, PhoneSettings settings) {
        List<Lyrics.Word> words = line.words();
        int size = Math.max(1, Math.min(4, settings.lyricsSize));
        float scale = SIZES[size - 1] * Math.max(2, settings.lyricsDistance) / 6f;
        boolean scatter = settings.lyricsScatter;

        // Ширина всей строки — чтобы слова не сдвигались по мере появления.
        float[] wordScale = new float[words.size()];
        float[] wordDy = new float[words.size()];
        float total = 0;
        int space = font.width(" ");
        for (int i = 0; i < words.size(); i++) {
            float r = hash(index * 31 + i);
            wordScale[i] = scatter ? 0.8f + r * 1.1f : 1f;
            wordDy[i] = scatter ? (hash(index * 17 + i * 7 + 3) - 0.5f) * 10f : 0f;
            total += (font.width(words.get(i).text()) + space) * wordScale[i];
        }
        int last = -1;
        for (int i = 0; i < words.size(); i++) {
            if (words.get(i).startMs() <= pos) {
                last = i;
            }
        }

        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        // Строка стоит на месте, но всегда повёрнута к игроку (как ник над головой) — читается откуда угодно.
        pose.mulPose(new Quaternionf(Minecraft.getInstance().gameRenderer.getMainCamera().rotation()));
        pose.scale(scale, -scale, scale);
        float x = -total / 2;
        for (int i = 0; i < words.size(); i++) {
            Lyrics.Word word = words.get(i);
            float w = (font.width(word.text()) + space) * wordScale[i];
            if (i <= last) {
                float pop = pos == Long.MAX_VALUE ? 1 : Math.min(1, (pos - word.startMs()) / (float) POP_MS);
                float s = wordScale[i] * (0.6f + 0.4f * easeOut(pop));
                float a = alpha * pop;
                int alphaBits = Math.max(4, Math.round(a * 255)) << 24;
                int rgb = i == last && pos != Long.MAX_VALUE && settings.lyricsAccentWord ? PhoneTheme.accent() & 0xFFFFFF : 0xFFFFFF;
                pose.pushPose();
                pose.translate(x + w / 2, wordDy[i], 0);
                pose.scale(s, s, s);
                FormattedCharSequence text = FormattedCharSequence.forward(word.text(), Style.EMPTY);
                float half = font.width(word.text()) / 2f;
                context.commandQueue().submitText(pose, -half, -font.lineHeight / 2f, text, false, Font.DisplayMode.NORMAL,
                        FULL_BRIGHT, alphaBits | rgb, 0, settings.lyricsOutline && a > 0.95f ? 0xFF101018 : 0);
                pose.popPose();
            }
            x += w;
        }
        pose.popPose();
    }

    private static float easeOut(float t) {
        float u = 1 - t;
        return 1 - u * u * u;
    }

    /** Псевдослучайное 0..1 от числа (одинаковое в каждом кадре). */
    private static float hash(int n) {
        int h = n * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA77;
        h ^= h >>> 13;
        return (h & 0xFFFF) / 65535f;
    }

    private LyricsWorld() {
    }
}
