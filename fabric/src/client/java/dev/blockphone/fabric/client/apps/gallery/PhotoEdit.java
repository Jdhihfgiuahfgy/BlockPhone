package dev.blockphone.fabric.client.apps.gallery;

import dev.blockphone.fabric.client.photo.CameraFilter;
import dev.blockphone.fabric.client.photo.ImageOps;
import dev.blockphone.fabric.client.photo.Picture;

/**
 * Набор правок из редактора галереи. Применяются по порядку:
 * поворот → отражение → кадрирование → фильтр → яркость/контраст/насыщенность.
 * Одинаково для превью (уменьшенная копия) и итогового файла.
 */
final class PhotoEdit {
    /** Пропорции кадра: 0 — как есть. */
    static final float[] CROPS = {0f, 1f, 4f / 3f, 3f / 4f, 16f / 9f, 9f / 16f};
    static final String[] CROP_LABELS = {null, "1:1", "4:3", "3:4", "16:9", "9:16"};
    static final int ADJUST_STEPS = 10;

    int rotation;
    boolean flip;
    int crop;
    CameraFilter filter = CameraFilter.NONE;
    int brightness;
    int contrast;
    int saturation;

    boolean isIdentity() {
        return rotation == 0 && !flip && crop == 0 && filter == CameraFilter.NONE
                && brightness == 0 && contrast == 0 && saturation == 0;
    }

    void reset() {
        rotation = 0;
        flip = false;
        crop = 0;
        filter = CameraFilter.NONE;
        brightness = 0;
        contrast = 0;
        saturation = 0;
    }

    PhotoEdit copy() {
        PhotoEdit e = new PhotoEdit();
        e.rotation = rotation;
        e.flip = flip;
        e.crop = crop;
        e.filter = filter;
        e.brightness = brightness;
        e.contrast = contrast;
        e.saturation = saturation;
        return e;
    }

    Picture apply(Picture src) {
        Picture p = src;
        for (int i = 0; i < rotation; i++) {
            p = ImageOps.rotateClockwise(p);
        }
        if (flip) {
            p = ImageOps.flipHorizontal(p);
        }
        p = ImageOps.cropToAspect(p, CROPS[crop]);
        p = ImageOps.applyFilter(p, filter);
        return ImageOps.adjust(p, brightness / (float) ADJUST_STEPS, contrast / (float) ADJUST_STEPS,
                saturation / (float) ADJUST_STEPS);
    }
}
