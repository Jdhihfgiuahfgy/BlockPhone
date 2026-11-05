package dev.blockphone.fabric.client.music.lyrics;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.compat.WorldRenderHook;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Текст песни в мире: строка появляется перед игроком и остаётся на месте («в мире») или следует за взглядом; всегда
 * повёрнута к игроку. Слова возникают по одному в такт песне, последнее — акцентным цветом; закончившаяся строка гаснет
 * и уплывает вверх. Длинная строка делится на две.
 * <p>
 * Место выбирается так, чтобы текст не попал в блоки: от заданного расстояния ближе (шаг 0,5 блока) и выше, пока весь
 * прямоугольник текста свободен и до него видно (в пещере текст встанет ближе). Настройки — {@code PhoneSettings.lyrics*}.
 */
public final class LyricsWorld {
    private static final int FULL_BRIGHT = 0xF000F0;
    private static final long POP_MS = 180;
    private static final long FADE_MS = 700;
    /** Пикселей шрифта на блок при размере 1..4 и расстоянии 6 блоков. */
    private static final float[] SIZES = {0.018f, 0.026f, 0.036f, 0.05f};
    /** Строка шире (в пикселях шрифта, с разбросом размеров) — делится на две. */
    private static final float MAX_ROW = 190;
    private static final float ROW_GAP = 1.7f;
    public static final int MAX_DISTANCE = 30;
    private static final float[] LIFTS = {0.4f, 0.9f, 1.5f, 2.2f, 3.0f};

    private static int shownLine = -1;
    private static Vec3 anchor = Vec3.ZERO;
    private static float anchorDist = 6;
    private static long placedAt;
    private static int fadingLine = -1;
    private static Vec3 fadingAnchor = Vec3.ZERO;
    private static float fadingDist = 6;
    private static long fadingSince;
    private static Lyrics shownFor;

    /** Раскладка строки: размеры и сдвиги слов, номер строки (ряда) для каждого слова, ширины рядов. */
    record Layout(float[] scale, float[] dy, int[] row, float[] rowWidth, int rows) {
    }

    public static void init() {
        WorldRenderHook.beforeEntities(LyricsWorld::render);
    }

    private static void render(PoseStack pose, SubmitNodeCollector queue) {
        Minecraft mc = Minecraft.getInstance();
        PhoneSettings settings = PhoneSettings.get();
        Lyrics lyrics = LyricsService.current();
        if (!settings.lyricsWorld || LyricsHud.screenMode(settings.lyricsMode) || lyrics == null || (!lyrics.synced() && !settings.lyricsUnsynced) || mc.player == null
                || mc.level == null || mc.options.hideGui || MusicPlayer.state() == MusicPlayer.State.STOPPED) {
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
        Font font = mc.font;
        long pos = LyricsService.positionMs();
        long now = System.currentTimeMillis();
        int line = lyrics.lineAt(pos);
        boolean follow = "view".equals(settings.lyricsMode);
        if (line != shownLine) {
            if (shownLine >= 0) {
                fadingLine = shownLine;
                fadingAnchor = anchor;
                fadingDist = anchorDist;
                fadingSince = now;
            }
            shownLine = line;
            if (line >= 0) {
                place(mc, camera, font, lyrics.lines().get(line), line, settings);
                placedAt = now;
            }
        }
        // «Перед глазами» — место пересчитывается 20 раз в секунду (с той же проверкой блоков).
        if (follow && shownLine >= 0 && now - placedAt >= 50) {
            place(mc, camera, font, lyrics.lines().get(shownLine), shownLine, settings);
            placedAt = now;
        }

        Vec3 cam = camera.position();
        if (fadingLine >= 0 && fadingLine < lyrics.lines().size()) {
            float t = (now - fadingSince) / (float) FADE_MS;
            if (t >= 1) {
                fadingLine = -1;
            } else {
                drawLine(queue, pose, font, cam, fadingAnchor.add(0, t * 0.6, 0), fadingDist, lyrics.lines().get(fadingLine),
                        fadingLine, Long.MAX_VALUE, 1 - t, settings);
            }
        }
        if (shownLine >= 0) {
            drawLine(queue, pose, font, cam, anchor, anchorDist, lyrics.lines().get(shownLine), shownLine, pos, 1, settings);
        }
    }

    private static float scaleAt(PhoneSettings settings, float distance) {
        int size = Math.max(1, Math.min(4, settings.lyricsSize));
        return SIZES[size - 1] * distance / 6f;
    }

    /** Разложить строку: размеры слов (разброс), сдвиги по высоте и деление на два ряда, если строка длинная. */
    static Layout layout(Font font, Lyrics.Line line, int index, boolean scatter) {
        List<Lyrics.Word> words = line.words();
        int n = words.size();
        float[] scale = new float[n];
        float[] dy = new float[n];
        float[] width = new float[n];
        int space = font.width(" ");
        float total = 0;
        for (int i = 0; i < n; i++) {
            scale[i] = scatter ? 0.8f + hash(index * 31 + i) * 1.1f : 1f;
            dy[i] = scatter ? (hash(index * 17 + i * 7 + 3) - 0.5f) * 8f : 0f;
            width[i] = (font.width(words.get(i).text()) + space) * scale[i];
            total += width[i];
        }
        int[] row = new int[n];
        if (total <= MAX_ROW || n < 2) {
            return new Layout(scale, dy, row, new float[]{total}, 1);
        }
        // Делим там, где ряды получаются ближе всего по ширине.
        int split = 1;
        float best = Float.MAX_VALUE;
        float left = 0;
        for (int i = 1; i < n; i++) {
            left += width[i - 1];
            float diff = Math.abs(left - (total - left));
            if (diff < best) {
                best = diff;
                split = i;
            }
        }
        float first = 0;
        for (int i = 0; i < n; i++) {
            row[i] = i < split ? 0 : 1;
            if (i < split) {
                first += width[i];
            }
        }
        return new Layout(scale, dy, row, new float[]{first, total - first}, 2);
    }

    /**
     * Место для строки: направление взгляда (наклон −25..20°), от заданного расстояния ближе, на высоте глаз и выше —
     * первое, где прямоугольник текста не задевает блоки и между ним и игроком нет стены.
     */
    private static void place(Minecraft mc, Camera camera, Font font, Lyrics.Line line, int index, PhoneSettings settings) {
        Layout layout = layout(font, line, index, settings.lyricsScatter);
        float widest = 0;
        for (float w : layout.rowWidth()) {
            widest = Math.max(widest, w);
        }
        float maxWordScale = settings.lyricsScatter ? 1.9f : 1f;
        float textH = (layout.rows() * ROW_GAP) * font.lineHeight * maxWordScale;
        float yaw = camera.yRot();
        float pitch = Math.max(-25, Math.min(20, camera.xRot()));
        Vec3 dir = Vec3.directionFromRotation(pitch, yaw);
        Vec3 right = Vec3.directionFromRotation(0, yaw + 90);
        Vec3 eye = camera.position();
        float wanted = Math.max(2, Math.min(MAX_DISTANCE, settings.lyricsDistance));
        if (!"view".equals(settings.lyricsMode)) {
            // «Остаётся в мире»: над поверхностью блока под этим местом, на выбранной высоте (lyricsHeight).
            Vec3 flat = Vec3.directionFromRotation(0, yaw);
            for (float d = wanted; d >= 1.5f; d -= 0.5f) {
                float scale = scaleAt(settings, d);
                double halfW = widest * scale / 2 + 0.2;
                double halfH = textH * scale / 2 + 0.15;
                Vec3 spot = eye.add(flat.scale(d));
                Double ground = groundBelow(mc.level, spot);
                if (ground == null) {
                    continue;
                }
                for (float extra = 0; extra <= 2.5f; extra += 0.5f) {
                    Vec3 center = new Vec3(spot.x, ground + settings.lyricsHeight + extra + halfH, spot.z);
                    if (free(mc.level, center, right, flat, halfW, halfH) && visible(mc, eye, center)) {
                        anchor = center;
                        anchorDist = (float) eye.distanceTo(center);
                        return;
                    }
                }
            }
        }
        for (float d = wanted; d >= 1.5f; d -= 0.5f) {
            float scale = scaleAt(settings, d);
            double halfW = widest * scale / 2 + 0.2;
            double halfH = textH * scale / 2 + 0.15;
            for (float lift : LIFTS) {
                Vec3 center = eye.add(dir.scale(d)).add(0, lift, 0);
                if (free(mc.level, center, right, dir, halfW, halfH) && visible(mc, eye, center)) {
                    anchor = center;
                    anchorDist = d;
                    return;
                }
            }
        }
        // Совсем тесно (узкий лаз) — вплотную перед глазами.
        anchor = eye.add(dir.scale(1.5));
        anchorDist = 1.5f;
    }

    /** Верх ближайшего твёрдого блока под точкой (до 2 блоков выше глаз и 10 ниже) или null — земли нет (обрыв). */
    private static Double groundBelow(Level level, Vec3 spot) {
        int x = net.minecraft.util.Mth.floor(spot.x);
        int z = net.minecraft.util.Mth.floor(spot.z);
        int top = net.minecraft.util.Mth.floor(spot.y) + 2;
        for (int y = top; y >= top - 12; y--) {
            BlockPos bp = new BlockPos(x, y, z);
            var shape = level.getBlockState(bp).getCollisionShape(level, bp);
            if (!shape.isEmpty()) {
                BlockPos above = bp.above();
                if (level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
                    return y + shape.max(net.minecraft.core.Direction.Axis.Y);
                }
                return null; // упёрлись в стену/потолок — тут места нет
            }
        }
        return null;
    }

    /** Прямоугольник текста (с небольшой толщиной) не задевает блоки с хитбоксом. */
    private static boolean free(Level level, Vec3 center, Vec3 right, Vec3 forward, double halfW, double halfH) {
        int stepsW = Math.max(2, (int) Math.ceil(halfW / 0.5));
        int stepsH = Math.max(1, (int) Math.ceil(halfH / 0.5));
        for (int iw = -stepsW; iw <= stepsW; iw++) {
            for (int ih = -stepsH; ih <= stepsH; ih++) {
                for (double depth : new double[]{-0.3, 0.3}) {
                    Vec3 p = center.add(right.scale(halfW * iw / stepsW)).add(0, halfH * ih / stepsH, 0).add(forward.scale(depth));
                    BlockPos bp = BlockPos.containing(p);
                    if (!level.getBlockState(bp).getCollisionShape(level, bp).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Между глазами и текстом нет стены (иначе текст «за стеной» — его не видно). */
    private static boolean visible(Minecraft mc, Vec3 eye, Vec3 center) {
        HitResult hit = mc.level.clip(new ClipContext(eye, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static void drawLine(SubmitNodeCollector queue, PoseStack pose, Font font, Vec3 cam, Vec3 at, float distance,
                                 Lyrics.Line line, int index, long pos, float alpha, PhoneSettings settings) {
        List<Lyrics.Word> words = line.words();
        Layout layout = layout(font, line, index, settings.lyricsScatter);
        float scale = scaleAt(settings, distance);
        int space = font.width(" ");
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
        float rowStep = font.lineHeight * ROW_GAP * (settings.lyricsScatter ? 1.4f : 1f);
        float top = -(layout.rows() - 1) * rowStep / 2f;
        float x = 0;
        int currentRow = -1;
        for (int i = 0; i < words.size(); i++) {
            Lyrics.Word word = words.get(i);
            int row = layout.row()[i];
            if (row != currentRow) {
                currentRow = row;
                x = -layout.rowWidth()[row] / 2;
            }
            float w = (font.width(word.text()) + space) * layout.scale()[i];
            if (i <= last) {
                float pop = pos == Long.MAX_VALUE ? 1 : Math.min(1, (pos - word.startMs()) / (float) POP_MS);
                float s = layout.scale()[i] * (0.6f + 0.4f * easeOut(pop));
                float a = alpha * pop;
                int alphaBits = Math.max(4, Math.round(a * 255)) << 24;
                int rgb = i == last && pos != Long.MAX_VALUE && settings.lyricsAccentWord ? PhoneTheme.accent() & 0xFFFFFF : 0xFFFFFF;
                pose.pushPose();
                pose.translate(x + w / 2, top + row * rowStep + layout.dy()[i], 0);
                pose.scale(s, s, s);
                FormattedCharSequence text = FormattedCharSequence.forward(word.text(), Style.EMPTY);
                float half = font.width(word.text()) / 2f;
                queue.submitText(pose, -half, -font.lineHeight / 2f, text, false, Font.DisplayMode.NORMAL,
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
