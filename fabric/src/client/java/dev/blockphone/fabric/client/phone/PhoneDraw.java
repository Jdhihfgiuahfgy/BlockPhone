package dev.blockphone.fabric.client.phone;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Помощники отрисовки для приложений телефона. Все координаты — в единицах телефона.
 * Стиль: панели с тонкой рамкой и скруглением {@link PhoneTheme#RADIUS}, плоские кнопки, акцентный цвет.
 */
public final class PhoneDraw {

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    private static final net.minecraft.network.chat.Style SMOOTH = net.minecraft.network.chat.Style.EMPTY.withFont(
            new net.minecraft.network.chat.FontDescription.Resource(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath(dev.blockphone.common.BlockPhone.MOD_ID, "smooth")));

    /** Гладкий шрифт (DejaVu Sans, assets/blockphone/font/smooth.json) — включает Telegram на время своей отрисовки и ввода. */
    public static boolean smoothText;

    /** Стиль текста: гладкий внутри Telegram, иначе обычный шрифт игры. */
    public static net.minecraft.network.chat.Style style() {
        return smoothText ? SMOOTH : net.minecraft.network.chat.Style.EMPTY;
    }

    /** Текст в стиле телефона — для font().split / width. */
    public static FormattedText styled(String s) {
        return FormattedText.of(s, style());
    }

    private static FormattedCharSequence seq(String s) {
        return FormattedCharSequence.forward(s, style());
    }

    // --- Формы ---

    /**
     * Прямоугольник со скруглёнными углами. Большие радиусы у прямоугольников уменьшаются до
     * {@link PhoneTheme#RADIUS} (стиль телефона), а «круги» и «таблетки» (радиус ≥ половины стороны) остаются круглыми.
     */
    public static void roundedRect(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int color) {
        int half = Math.min((x2 - x1) / 2, (y2 - y1) / 2);
        if (r < half - 1) {
            r = Math.min(r, PhoneTheme.RADIUS);
        }
        rawRoundedRect(g, x1, y1, x2, y2, Math.min(r, half), color);
    }

    /** Сглаженные углы: белый круг и «обратный» круг 256×256 с линейной фильтрацией (gen_icons.py, aa_shapes). */
    private static final net.minecraft.resources.Identifier CIRCLE = net.minecraft.resources.Identifier.fromNamespaceAndPath(
            dev.blockphone.common.BlockPhone.MOD_ID, "textures/gui/shape/circle.png");
    private static final net.minecraft.resources.Identifier CIRCLE_INV = net.minecraft.resources.Identifier.fromNamespaceAndPath(
            dev.blockphone.common.BlockPhone.MOD_ID, "textures/gui/shape/circle_inv.png");
    /** Углы и круги — гладкой текстурой (true) или «лесенкой» по единицам телефона (false). */
    public static boolean smoothShapes = true;

    /** Четыре четверти круга-текстуры в углах прямоугольника (радиус r) цветом color. */
    private static void corners(GuiGraphics g, net.minecraft.resources.Identifier tex, int x1, int y1, int x2, int y2, int r, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x1, y1, 0, 0, r, r, 128, 128, 256, 256, color);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x2 - r, y1, 128, 0, r, r, 128, 128, 256, 256, color);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x1, y2 - r, 0, 128, r, r, 128, 128, 256, 256, color);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x2 - r, y2 - r, 128, 128, r, r, 128, 128, 256, 256, color);
    }

    /** Скругление ровно с радиусом r (без приведения к стилю) — для корпуса телефона. */
    public static void rawRoundedRect(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int color) {
        r = Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2));
        if (r <= 0) {
            g.fill(x1, y1, x2, y2, color);
            return;
        }
        if (smoothShapes && r >= 2) {
            g.fill(x1, y1 + r, x2, y2 - r, color);
            g.fill(x1 + r, y1, x2 - r, y1 + r, color);
            g.fill(x1 + r, y2 - r, x2 - r, y2, color);
            corners(g, CIRCLE, x1, y1, x2, y2, r, color);
            return;
        }
        g.fill(x1, y1 + r, x2, y2 - r, color);
        for (int i = 0; i < r; i++) {
            int inset = cornerInset(r, i);
            g.fill(x1 + inset, y1 + i, x2 - inset, y1 + i + 1, color);
            g.fill(x1 + inset, y2 - i - 1, x2 - inset, y2 - i, color);
        }
    }

    /** Панель: заливка fill и тонкая рамка цвета {@link PhoneTheme#border()}. */
    public static void panel(GuiGraphics g, int x1, int y1, int x2, int y2, int fill) {
        rawRoundedRect(g, x1, y1, x2, y2, PhoneTheme.RADIUS, PhoneTheme.border());
        rawRoundedRect(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, PhoneTheme.RADIUS - 1, fill);
    }

    /** Панель-карточка в цветах темы (под курсором — светлее). */
    /**
     * Контур в 1 пиксель четырьмя заливками — учитывает сдвиг и масштаб телефона. (Встроенный контур Minecraft в 1.21.10
     * рисуется отдельно и без масштаба — оказывался в углу экрана.)
     */
    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    public static void card(GuiGraphics g, int x1, int y1, int x2, int y2, boolean hovered) {
        panel(g, x1, y1, x2, y2, hovered ? PhoneTheme.cardHover() : PhoneTheme.card());
    }

    /** Подложка поля ввода. */
    public static void field(GuiGraphics g, int x1, int y1, int x2, int y2, boolean focused) {
        rawRoundedRect(g, x1, y1, x2, y2, PhoneTheme.RADIUS, focused ? PhoneTheme.accent() : PhoneTheme.border());
        rawRoundedRect(g, x1 + 1, y1 + 1, x2 - 1, y2 - 1, PhoneTheme.RADIUS - 1, PhoneTheme.field());
    }

    /** Горизонтальный градиент (по столбцам) со скруглёнными краями — «таблетка» как «Моя волна». */
    public static void gradientPill(GuiGraphics g, int x1, int y1, int x2, int y2, int left, int right) {
        int w = x2 - x1;
        int r = PhoneTheme.RADIUS;
        for (int i = 0; i < w; i++) {
            int color = PhoneTheme.mix(left, right, w <= 1 ? 0 : i / (float) (w - 1));
            int inset = 0;
            if (i < r) {
                inset = cornerInset(r, i);
            } else if (i >= w - r) {
                inset = cornerInset(r, w - 1 - i);
            }
            g.fill(x1 + i, y1 + inset, x1 + i + 1, y2 - inset, color);
        }
    }

    /**
     * Закрашивает всё, что снаружи скруглённых углов прямоугольника, цветом color.
     * Так экран телефона получает скруглённые углы поверх любого содержимого.
     */
    public static void roundCornersMask(GuiGraphics g, int x1, int y1, int x2, int y2, int r, int color) {
        if (smoothShapes && r >= 2) {
            corners(g, CIRCLE_INV, x1, y1, x2, y2, Math.min(r, Math.min((x2 - x1) / 2, (y2 - y1) / 2)), color);
            return;
        }
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

    // --- Кнопки ---

    public enum ButtonStyle {
        /** Обычная: панель с рамкой. */
        NORMAL,
        /** Главное действие: залита акцентом. */
        PRIMARY,
        /** Выбранная вкладка/переключатель: подложка и текст акцентного цвета. */
        SELECTED,
        /** Опасное действие: красный текст. */
        DANGER
    }

    /** Плоская кнопка с подписью по центру (текст 0.75). */
    public static void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, ButtonStyle style) {
        boolean hovered = inside(mx, my, x, y, w, h);
        int fg;
        switch (style) {
            case PRIMARY -> {
                rawRoundedRect(g, x, y, x + w, y + h, PhoneTheme.RADIUS, hovered ? PhoneTheme.accentHover() : PhoneTheme.accent());
                fg = PhoneTheme.onAccent();
            }
            case SELECTED -> {
                rawRoundedRect(g, x, y, x + w, y + h, PhoneTheme.RADIUS, PhoneTheme.accentTint(hovered ? 0x80 : 0x60));
                rawRoundedRect(g, x + 1, y + 1, x + w - 1, y + h - 1, PhoneTheme.RADIUS - 1, PhoneTheme.accentTint(hovered ? 0x40 : 0x30));
                fg = PhoneTheme.dark() ? PhoneTheme.mix(PhoneTheme.accent(), PhoneTheme.WHITE, 0.35f) : PhoneTheme.accent();
            }
            default -> {
                card(g, x, y, x + w, y + h, hovered);
                fg = style == ButtonStyle.DANGER ? PhoneTheme.danger() : PhoneTheme.text();
            }
        }
        String text = ellipsize(label, w - 6, 0.75f);
        centeredText(g, text, x + w / 2f, y + (h - 6) / 2f, fg, 0.75f, false);
    }

    /** Совместимость: кнопка с явными цветами фона и текста. */
    public static void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, int bg, int fg) {
        boolean hovered = inside(mx, my, x, y, w, h);
        rawRoundedRect(g, x, y, x + w, y + h, PhoneTheme.RADIUS, hovered ? PhoneTheme.mix(bg, PhoneTheme.WHITE, 0.12f) : bg);
        String text = ellipsize(label, w - 6, 0.75f);
        centeredText(g, text, x + w / 2f, y + (h - 6) / 2f, fg, 0.75f, false);
    }

    // --- Текст ---

    public static void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font(), seq(s), x, y, color, false);
    }

    public static void text(GuiGraphics g, String s, float x, float y, int color, float scale, boolean shadow) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.drawString(font(), seq(s), 0, 0, color, shadow);
        g.pose().popMatrix();
    }

    public static void centeredText(GuiGraphics g, String s, float centerX, float y, int color, float scale, boolean shadow) {
        text(g, s, centerX - width(s, scale) / 2f, y, color, scale, shadow);
    }

    public static void rightText(GuiGraphics g, String s, float rightX, float y, int color, float scale) {
        text(g, s, rightX - width(s, scale), y, color, scale, false);
    }

    public static float width(String s, float scale) {
        return font().width(styled(s)) * scale;
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
        List<FormattedCharSequence> lines = font().split(styled(s), (int) (maxWidth / scale));
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
        return (int) Math.ceil(font().split(styled(s), (int) (maxWidth / scale)).size() * 10 * scale);
    }

    // --- Текстуры ---

    /** Текстура целиком, растянутая в прямоугольник w×h. */
    public static void texture(GuiGraphics g, Identifier texture, int x, int y, int w, int h, int texW, int texH) {
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, w, h, texW, texH, texW, texH);
    }

    /** Белая маска-символ, окрашенная в color (иконки приложений). */
    public static void tinted(GuiGraphics g, Identifier texture, int x, int y, int w, int h, int texW, int texH, int color) {
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0, w, h, texW, texH, texW, texH, color);
    }

    /**
     * Картинка, заполняющая прямоугольник с обрезкой по центру (как обои): лишнее по ширине или высоте отрезается.
     */
    public static void textureCover(GuiGraphics g, Identifier texture, int x, int y, int w, int h, int texW, int texH) {
        float target = w / (float) h;
        float source = texW / (float) texH;
        float u = 0;
        float v = 0;
        int rw = texW;
        int rh = texH;
        if (source > target) {
            rw = Math.round(texH * target);
            u = (texW - rw) / 2f;
        } else {
            rh = Math.round(texW / target);
            v = (texH - rh) / 2f;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, w, h, rw, rh, texW, texH);
    }

    public static boolean inside(double mx, double my, double x, double y, double w, double h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    private PhoneDraw() {
    }
}
