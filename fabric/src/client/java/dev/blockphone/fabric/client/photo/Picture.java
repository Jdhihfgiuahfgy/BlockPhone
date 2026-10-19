package dev.blockphone.fabric.client.photo;

/** Изображение в памяти: пиксели ARGB построчно (как у {@link java.awt.image.BufferedImage#getRGB}). */
public record Picture(int width, int height, int[] argb) {

    public Picture {
        if (argb.length != width * height) {
            throw new IllegalArgumentException("Pixel array size mismatch");
        }
    }

    public static Picture blank(int width, int height) {
        return new Picture(width, height, new int[width * height]);
    }

    public int get(int x, int y) {
        return argb[y * width + x];
    }

    public Picture copy() {
        return new Picture(width, height, argb.clone());
    }
}
