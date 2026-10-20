package dev.blockphone.fabric.client.ui;

import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.photo.RemoteImages;
import net.minecraft.client.gui.GuiGraphics;

/** Отрисовка фото с сервера (в постах и сообщениях) и расчёт их размера. */
public final class Images {
    /** Размер текстуры для ленты и пузырей. */
    public static final int THUMB = 320;
    /** Размер текстуры для полноэкранного просмотра. */
    public static final int FULL = 720;

    /** Высота картинки шириной width с сохранением пропорций, но в пределах [0.5; 1.25] ширины. */
    public static int height(ImageRef image, int width) {
        float aspect = image.width() > 0 ? image.height() / (float) image.width() : 0.75f;
        return Math.round(width * Math.max(0.5f, Math.min(1.25f, aspect)));
    }

    /** Фото, заполняющее прямоугольник (лишнее обрезается); пока грузится — заглушка. */
    public static void draw(GuiGraphics g, ImageRef image, int x, int y, int w, int h, int maxSize) {
        PhotoTextures.Image texture = RemoteImages.get(image.id(), maxSize);
        if (texture == null) {
            g.fill(x, y, x + w, y + h, PhoneTheme.dark() ? 0xFF1F2738 : 0xFFDCE3EE);
            String label = RemoteImages.failed(image.id()) ? "×" : "...";
            PhoneDraw.centeredText(g, label, x + w / 2f, y + h / 2f - 3, PhoneTheme.secondaryText(), 0.75f, false);
            return;
        }
        PhoneDraw.textureCover(g, texture.id(), x, y, w, h, texture.width(), texture.height());
    }

    /** Фото целиком, вписанное в прямоугольник по центру (для просмотра). */
    public static void drawFit(GuiGraphics g, ImageRef image, int x, int y, int w, int h) {
        float k = Math.min(w / (float) Math.max(1, image.width()), h / (float) Math.max(1, image.height()));
        int dw = Math.max(1, Math.round(image.width() * k));
        int dh = Math.max(1, Math.round(image.height() * k));
        PhotoTextures.Image texture = RemoteImages.get(image.id(), FULL);
        if (texture == null) {
            // Пока грузится большая версия — показать миниатюру, если она уже есть.
            texture = RemoteImages.get(image.id(), THUMB);
        }
        int dx = x + (w - dw) / 2;
        int dy = y + (h - dh) / 2;
        if (texture == null) {
            g.fill(dx, dy, dx + dw, dy + dh, 0xFF1F2738);
            PhoneDraw.centeredText(g, RemoteImages.failed(image.id()) ? "×" : "...", x + w / 2f, y + h / 2f - 3, 0xFFB0B8C8, 0.75f, false);
            return;
        }
        PhoneDraw.texture(g, texture.id(), dx, dy, dw, dh, texture.width(), texture.height());
    }

    private Images() {
    }
}
