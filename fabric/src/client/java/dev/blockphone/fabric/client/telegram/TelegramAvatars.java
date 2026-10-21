package dev.blockphone.fabric.client.telegram;

import dev.blockphone.fabric.client.music.Covers;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import it.tdlight.jni.TdApi;
import net.minecraft.client.gui.GuiGraphics;

import java.nio.file.Path;

/** Аватар чата Telegram: маленькое фото (скачивается TDLib) или круг цвета чата с первой буквой. */
public final class TelegramAvatars {
    private static final int[] COLORS = {0xFFE17076, 0xFFFAA774, 0xFFA695E7, 0xFF7BC862, 0xFF6EC9CB, 0xFF65AADD, 0xFFEE7AAE};

    /** Круглый аватар: углы закрашиваются цветом фона bg (как в Telegram). */
    public static void drawRound(GuiGraphics g, TdApi.Chat chat, int x, int y, int size, int bg) {
        draw(g, chat, x, y, size);
        PhoneDraw.roundCornersMask(g, x, y, x + size, y + size, size / 2, bg);
    }

    public static void draw(GuiGraphics g, TdApi.Chat chat, int x, int y, int size) {
        Path file = chat.photo != null ? Telegram.localFile(size > 48 && chat.photo.big != null ? chat.photo.big : chat.photo.small) : null;
        PhotoTextures.Image image = file != null ? Covers.file(file) : null;
        if (image != null) {
            PhoneDraw.textureCover(g, image.id(), x, y, size, size, image.width(), image.height());
            return;
        }
        int color = COLORS[(int) Math.floorMod(chat.id, (long) COLORS.length)];
        PhoneDraw.roundedRect(g, x, y, x + size, y + size, size / 2, color);
        String title = Telegram.title(chat);
        String letter = title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase();
        float scale = size >= 24 ? 1.5f : size >= 16 ? 1f : 0.75f;
        PhoneDraw.centeredText(g, letter, x + size / 2f, y + size / 2f - 4 * scale, 0xFFFFFFFF, scale, false);
    }

    private TelegramAvatars() {
    }
}
