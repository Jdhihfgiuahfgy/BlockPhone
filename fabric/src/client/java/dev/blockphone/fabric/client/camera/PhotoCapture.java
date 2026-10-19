package dev.blockphone.fabric.client.camera;

import com.mojang.blaze3d.platform.NativeImage;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.photo.ImageOps;
import dev.blockphone.fabric.client.photo.Picture;
import dev.blockphone.fabric.client.photo.PhotoMeta;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.function.Consumer;

/**
 * Один снимок. Кадры берутся из основного буфера в момент, когда мир уже нарисован (с фильтром),
 * а интерфейс ещё нет — см. {@link CameraMode#renderOverlay}. При длинной выдержке кадры
 * копятся и усредняются (смаз движения). Затем на фоновом потоке: экспозиция, шум, сохранение.
 */
final class PhotoCapture {
    private static final int MAX_FRAMES = 60;

    private final long endTime;
    private final float stops;
    private final float noise;
    private final PhotoMeta meta;
    private final Consumer<PhotoStore.Photo> onSaved;

    private int requested;
    private int received;
    private boolean stopped;
    private int width;
    private int height;
    private int[] sumR;
    private int[] sumG;
    private int[] sumB;
    private int frames;

    PhotoCapture(long durationMs, float stops, float noise, PhotoMeta meta, Consumer<PhotoStore.Photo> onSaved) {
        this.endTime = System.currentTimeMillis() + durationMs;
        this.stops = stops;
        this.noise = noise;
        this.meta = meta;
        this.onSaved = onSaved;
    }

    /** Ещё идёт съёмка (копятся кадры) или ждём данные с видеокарты. */
    boolean busy() {
        return !stopped || received < requested;
    }

    boolean exposing() {
        return !stopped;
    }

    /** Вызывается каждый кадр между отрисовкой мира и интерфейса. */
    void onFrame(Minecraft mc) {
        if (stopped) {
            return;
        }
        requested++;
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), this::accept);
        if (System.currentTimeMillis() >= endTime || requested >= MAX_FRAMES) {
            stopped = true;
        }
    }

    private void accept(NativeImage image) {
        try (image) {
            if (sumR == null) {
                width = image.getWidth();
                height = image.getHeight();
                sumR = new int[width * height];
                sumG = new int[width * height];
                sumB = new int[width * height];
            }
            if (image.getWidth() == width && image.getHeight() == height) {
                int[] pixels = image.getPixels();
                for (int i = 0; i < pixels.length; i++) {
                    int c = pixels[i];
                    sumR[i] += (c >> 16) & 255;
                    sumG[i] += (c >> 8) & 255;
                    sumB[i] += c & 255;
                }
                frames++;
            }
        } finally {
            received++;
        }
        if (stopped && received == requested) {
            finish();
        }
    }

    private void finish() {
        if (frames == 0) {
            return;
        }
        int[] argb = new int[width * height];
        int n = frames;
        for (int i = 0; i < argb.length; i++) {
            argb[i] = 0xFF000000 | (sumR[i] / n) << 16 | (sumG[i] / n) << 8 | (sumB[i] / n);
        }
        sumR = sumG = sumB = null;
        Picture raw = new Picture(width, height, argb);
        String name = PhotoStore.newName("");
        ClientFiles.IO.execute(() -> {
            try {
                Picture processed = ImageOps.noise(ImageOps.exposure(raw, stops), noise, meta.takenAt);
                PhotoStore.Photo photo = PhotoStore.save(processed, meta, name);
                Minecraft.getInstance().execute(() -> onSaved.accept(photo));
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Failed to save photo", e);
                Minecraft.getInstance().execute(() -> onSaved.accept(null));
            }
        });
    }
}
