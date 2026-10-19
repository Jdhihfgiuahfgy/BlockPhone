package dev.blockphone.fabric.client.phone;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Помощники отрисовки для приложений телефона. Все координаты — в единицах телефона. */
public final class PhoneDraw {

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    /** Прямоугольник со скруглёнными углами (радиус r), рисуется полосками по строкам. */
    public static void roundedRect(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int color) {
        r = Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2));
        if (r <= 0) {
            g.fill(x1, y1, x2, y2, color);
            return;
        }
        g.fill(x1, y1 + r, x2, y2 - r, color);
        for (int i = 0; i < r; i++) {
            int inset = cornerInset(r, i);
            g.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, color);
            g.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, color);
        }
    }

    /**
     * Закрашивает всё, что снаружи скруглённых углов прямоугольника, цветом color.
     * Так экран телефона получает скруглённые углы поверх любого содержимого.
     */
    public static void roundCornersMask(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int color) {
        for (int i = 0; i < r; i++) {
            int inset = cornerInset(r, i);
            if (inset <= 0) {
                continue;
            }
            g.fill(x1, y1 + i, x1 + inset, y1 + i + 1, color);
            g.fill(x2 - inset, y1 + i, x2, y1 + i + 1, color);
            g.fill(x1, y2 - i - 1, x1 + inset, y2 - i, color);
            g.fill(x2 - inset, y2 - i - 1, x2, y2 - i, color);
        }
    }

    private static int cornerInset(int r, int row) {
        double dy = r - row - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0, (double) r * r - dy * dy)));
    }

    public static void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font(), s, x, y, color, false);
    }

    public static void text(GuiGraphics g, String s, float x, float y, int color, float scale, boolean shadow) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.drawString(font(), s, 0, 0, color, shadow);
        g.pose().popMatrix();
    }

    public static void centeredText(GuiGraphics g, String s, float centerX, float y, int color, float scale, boolean shadow) {
        text(g, s, centerX - width(s, scale) / 2f, y, color, scale, shadow);
    }

    public static void rightText(GuiGraphics g, String s, float rightX, float y, int color, float scale) {
        text(g, s, rightX - width(s, scale), y, color, scale, false);
    }

    public static float width(String s, float scale) {
        return font().width(s) * scale;
    }

    /** Обрезает строку с «…», чтобы она влезла в maxWidth (с учётом масштаба). */
    public static String ellipsize(String s, float maxWidth, float scale) {
        if (width(s, scale) <= maxWidth) {
            return s;
        }
        String dots = "…";
        int end = s.length();
        while (end > 0 && width(s.substring(0, end) + dots, scale) > maxWidth) {
            end--;
        }
        return s.substring(0, end) + dots;
    }

    /** Многострочный текст с переносом по словам. Возвращает высоту в единицах телефона. */
    public static int wrapped(GuiGraphics g, String s, float x, float y, float maxWidth, int color, float scale, boolean centered) {
        List<FormattedCharSequence> lines = font().split(FormattedText.of(s), (int) (maxWidth / scale));
        float lh = 10 * scale;
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            float lx = centered ? x + (maxWidth - font().width(line) * scale) / 2f : x;
            g.pose().pushMatrix();
            g.pose().translate(lx, y + i * lh);
            g.pose().scale(scale, scale);
            g.drawString(font(), line, 0, 0, color, false);
            g.pose().popMatrix();
        }
        return (int) Math.ceil(lines.size() * lh);
    }

    public static int wrappedHeight(String s, float maxWidth, float scale) {
        return (int) Math.ceil(font().split(FormattedText.of(s), (int) (maxWidth / scale)).size() * 10 * scale);
    }

    /** Текстура целиком, растянутая в прямоугольник w×h. */
    public static void texture(GuiGraphics g, Identifier texture, int x, int y, int w, int h, int texW, int texH) {
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, w, h, texW, texH, texW, texH);
    }

    /** Кнопка-«таблетка» с подписью; подсвечивается при наведении. */
    public static void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, int bg, int fg) {
        roundedRect(g, x, y, x + w, y + h, Math.min(h / 2, 8), bg);
        if (inside(mx, my, x, y, w, h)) {
            roundedRect(g, x, y, x + w, y + h, Math.min(h / 2, 8), 0x30FFFFFF);
        }
        String text = ellipsize(label, w - 6, 0.75f);
        centeredText(g, text, x + w / 2f, y + (h - 6) / 2f, fg, 0.75f, false);
    }

    public static boolean inside(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private PhoneDraw() {
    }
}
