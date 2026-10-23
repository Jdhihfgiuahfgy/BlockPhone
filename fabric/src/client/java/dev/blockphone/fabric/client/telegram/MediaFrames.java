package dev.blockphone.fabric.client.telegram;

import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.photo.ImageOps;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.photo.Picture;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import org.jcodec.api.FrameGrab;
import org.jcodec.api.PictureWithMetadata;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Анимация из MP4 (GIF в Telegram — это видео без звука, «кружочки», видео): JCodec декодирует в фоне не больше
 * {@link #MAX_SECONDS} с по {@link #FPS} кадров в секунду, кадры уменьшаются и становятся текстурами. Не вышло (профиль
 * H.264, который JCodec не умеет) — {@link #frame} возвращает null, и показывается обложка.
 */
public final class MediaFrames {
    public static final int FPS = 15;
    public static final int MAX_SECONDS = 8;
    private static final int MAX_CLIPS = 10;
    private static final int MAX_POSTERS = 40;

    /** Кадры одного ролика (уже уменьшенные). */
    public record Decoded(List<Picture> frames, int frameMs) {
    }

    private record Clip(List<PhotoTextures.Image> frames, int frameMs, long startedAt) {
    }

    /** Своя очередь: декодирование долгое и не должно задерживать загрузку фото ({@link ClientFiles#IO}). */
    private static final java.util.concurrent.ExecutorService DECODER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BlockPhone Animation decoder");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final Map<String, Clip> CLIPS = new LinkedHashMap<>(8, 0.75f, true);
    private static final Set<String> LOADING = new HashSet<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static final Map<String, PhotoTextures.Image> POSTERS = new LinkedHashMap<>(16, 0.75f, true);

    /** Первый кадр ролика (для панели GIF: много роликов сразу, анимировать все — дорого) или null. */
    public static @Nullable PhotoTextures.Image poster(Path file, int maxSide) {
        return poster(file, maxSide, false);
    }

    /** Первый кадр; circle — квадрат по центру с прозрачными углами (обложка «кружочка»). */
    public static @Nullable PhotoTextures.Image poster(Path file, int maxSide, boolean circle) {
        String key = "poster|" + file + "|" + maxSide + (circle ? "|o" : "");
        PhotoTextures.Image image = POSTERS.get(key);
        if (image != null || FAILED.contains(key) || !LOADING.add(key)) {
            return image;
        }
        DECODER.execute(() -> {
            Decoded decoded;
            try {
                decoded = decode(file, maxSide, FPS, 1, 1, circle);
            } catch (Throwable e) {
                decoded = null;
            }
            Decoded result = decoded;
            Minecraft.getInstance().execute(() -> {
                LOADING.remove(key);
                if (result == null || result.frames().isEmpty()) {
                    FAILED.add(key);
                    return;
                }
                POSTERS.put(key, PhotoTextures.upload(result.frames().get(0), "poster"));
                Iterator<Map.Entry<String, PhotoTextures.Image>> it = POSTERS.entrySet().iterator();
                while (POSTERS.size() > MAX_POSTERS && it.hasNext()) {
                    PhotoTextures.release(it.next().getValue());
                    it.remove();
                }
            });
        });
        return null;
    }

    /** Текущий кадр анимации (поток рендера) или null — ещё декодируется / не получилось. */
    public static @Nullable PhotoTextures.Image frame(Path file, int maxSide) {
        return frame(file, maxSide, false);
    }

    /** Кадр анимации; circle — круглый кадр с прозрачными краями («кружочек» без квадратного фона). */
    public static @Nullable PhotoTextures.Image frame(Path file, int maxSide, boolean circle) {
        String key = file + "|" + maxSide + (circle ? "|o" : "");
        Clip clip = CLIPS.get(key);
        if (clip != null) {
            int index = (int) ((System.currentTimeMillis() - clip.startedAt()) / clip.frameMs() % clip.frames().size());
            return clip.frames().get(index);
        }
        if (FAILED.contains(key) || !LOADING.add(key)) {
            return null;
        }
        DECODER.execute(() -> {
            Decoded decoded;
            try {
                decoded = decode(file, maxSide, FPS, MAX_SECONDS, FPS * MAX_SECONDS, circle);
            } catch (Throwable e) {
                BlockPhoneFabric.LOGGER.info("Animation {} is not decodable: {}", file.getFileName(), e.toString());
                decoded = null;
            }
            Decoded result = decoded;
            Minecraft.getInstance().execute(() -> {
                LOADING.remove(key);
                if (result == null || result.frames().isEmpty()) {
                    FAILED.add(key);
                    return;
                }
                List<PhotoTextures.Image> textures = new ArrayList<>();
                for (Picture p : result.frames()) {
                    textures.add(PhotoTextures.upload(p, "anim"));
                }
                CLIPS.put(key, new Clip(textures, result.frameMs(), System.currentTimeMillis()));
                trim();
            });
        });
        return null;
    }

    private static void trim() {
        Iterator<Map.Entry<String, Clip>> it = CLIPS.entrySet().iterator();
        while (CLIPS.size() > MAX_CLIPS && it.hasNext()) {
            it.next().getValue().frames().forEach(PhotoTextures::release);
            it.remove();
        }
    }

    /** Декодировать ролик: кадры по времени (шаг 1/fps), не дольше maxSeconds, сторона не больше maxSide. */
    public static Decoded decode(Path file, int maxSide, int fps, int maxSeconds) throws Exception {
        return decode(file, maxSide, fps, maxSeconds, fps * maxSeconds, false);
    }

    private static Decoded decode(Path file, int maxSide, int fps, int maxSeconds, int maxFrames, boolean circle) throws Exception {
        List<Picture> frames = new ArrayList<>();
        try (SeekableByteChannel channel = NIOUtils.readableChannel(file.toFile())) {
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            double step = 1.0 / fps;
            double next = 0;
            Transform transform = null;
            org.jcodec.common.model.Picture rgb = null;
            while (frames.size() < maxFrames) {
                PictureWithMetadata pm = grab.getNativeFrameWithMetadata();
                if (pm == null || pm.getPicture() == null) {
                    break;
                }
                double ts = pm.getTimestamp();
                if (ts + 1e-6 < next && !frames.isEmpty()) {
                    continue; // кадров больше, чем нужно — пропускаем
                }
                next = Math.max(next + step, ts + step * 0.5);
                org.jcodec.common.model.Picture src = pm.getPicture();
                if (transform == null) {
                    transform = ColorUtil.getTransform(src.getColor(), ColorSpace.RGB);
                    rgb = org.jcodec.common.model.Picture.create(src.getWidth(), src.getHeight(), ColorSpace.RGB);
                }
                transform.transform(src, rgb);
                Picture frame = ImageOps.downscale(toPicture(rgb, src.getCroppedWidth(), src.getCroppedHeight()), maxSide, maxSide);
                frames.add(circle ? circle(frame) : frame);
            }
        }
        return new Decoded(frames, 1000 / fps);
    }

    /** Квадрат по центру, всё за пределами вписанного круга — прозрачно (край сглажен на 1 пиксель). */
    static Picture circle(Picture p) {
        int side = Math.min(p.width(), p.height());
        int ox = (p.width() - side) / 2;
        int oy = (p.height() - side) / 2;
        int[] out = new int[side * side];
        float r = side / 2f;
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                float dx = x + 0.5f - r;
                float dy = y + 0.5f - r;
                float a = Math.max(0, Math.min(1, r - (float) Math.sqrt(dx * dx + dy * dy) + 0.5f));
                int c = p.get(ox + x, oy + y);
                out[y * side + x] = Math.round(a * (c >>> 24)) << 24 | (c & 0xFFFFFF);
            }
        }
        return new Picture(side, side, out);
    }

    private static Picture toPicture(org.jcodec.common.model.Picture rgb, int w, int h) {
        byte[] data = rgb.getPlaneData(0);
        int stride = rgb.getWidth() * 3;
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int o = y * stride + x * 3;
                argb[y * w + x] = 0xFF000000 | (data[o] + 128) << 16 | (data[o + 1] + 128) << 8 | (data[o + 2] + 128);
            }
        }
        return new Picture(w, h, argb);
    }

    private MediaFrames() {
    }
}
