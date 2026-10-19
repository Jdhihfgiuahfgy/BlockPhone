package dev.blockphone.fabric.client.ui;

import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Поле ввода текста для приложений телефона: однострочное или многострочное с переносом слов,
 * курсором, выделением (мышью и Shift+стрелки), Ctrl+A/C/X/V, прокруткой.
 * Координаты — в системе приложения (как у {@link dev.blockphone.fabric.client.phone.PhoneApp}).
 */
public class TextArea {
    private static final int PAD = 3;

    private int x;
    private int y;
    private int width;
    private int height;
    private final boolean multiline;
    private final float scale;

    private String text = "";
    private int cursor;
    /** Начало выделения; -1 — выделения нет. */
    private int anchor = -1;
    private boolean focused;
    private int maxLength = 10_000;
    private String placeholder = "";
    private double scroll;
    private long lastInput = System.currentTimeMillis();
    private Consumer<String> onChange = s -> {
    };

    private List<int[]> lines = List.of();
    private boolean layoutDirty = true;

    public TextArea(boolean multiline, float scale) {
        this.multiline = multiline;
        this.scale = scale;
    }

    public void setBounds(int x, int y, int width, int height) {
        if (this.width != width) {
            layoutDirty = true;
        }
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public String text() {
        return text;
    }

    public void setText(String value) {
        text = value == null ? "" : value;
        cursor = text.length();
        anchor = -1;
        layoutDirty = true;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public void setOnChange(Consumer<String> onChange) {
        this.onChange = onChange;
    }

    public boolean isFocused() {
        return focused;
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
        if (!focused) {
            anchor = -1;
        }
        lastInput = System.currentTimeMillis();
    }

    public void moveCursorToEnd() {
        cursor = text.length();
        anchor = -1;
        ensureCursorVisible();
    }

    private float lineHeight() {
        return 10 * scale;
    }

    // --- Раскладка строк ---

    /** Строки как пары [начало, конец) индексов в {@link #text}. */
    private List<int[]> lines() {
        if (layoutDirty) {
            lines = layout();
            layoutDirty = false;
        }
        return lines;
    }

    private List<int[]> layout() {
        List<int[]> result = new ArrayList<>();
        float maxWidth = width - PAD * 2;
        if (!multiline) {
            result.add(new int[]{0, text.length()});
            return result;
        }
        int paragraphStart = 0;
        while (true) {
            int newline = text.indexOf('\n', paragraphStart);
            int paragraphEnd = newline < 0 ? text.length() : newline;
            wrapParagraph(paragraphStart, paragraphEnd, maxWidth, result);
            if (newline < 0) {
                break;
            }
            paragraphStart = newline + 1;
        }
        return result;
    }

    private void wrapParagraph(int start, int end, float maxWidth, List<int[]> out) {
        if (start == end) {
            out.add(new int[]{start, end});
            return;
        }
        int lineStart = start;
        while (lineStart < end) {
            int lastSpace = -1;
            int i = lineStart;
            while (i < end && PhoneDraw.width(text.substring(lineStart, i + 1), scale) <= maxWidth) {
                if (text.charAt(i) == ' ') {
                    lastSpace = i;
                }
                i++;
            }
            if (i >= end) {
                out.add(new int[]{lineStart, end});
                return;
            }
            int breakAt = lastSpace > lineStart ? lastSpace + 1 : Math.max(i, lineStart + 1);
            out.add(new int[]{lineStart, breakAt});
            lineStart = breakAt;
        }
    }

    private int lineOf(int index) {
        List<int[]> ls = lines();
        for (int i = ls.size() - 1; i >= 0; i--) {
            if (index >= ls.get(i)[0]) {
                return i;
            }
        }
        return 0;
    }

    private float xOf(int index) {
        int[] line = lines().get(lineOf(index));
        return PhoneDraw.width(text.substring(line[0], Math.min(index, line[1])), scale);
    }

    private int indexAt(double mx, double my) {
        List<int[]> ls = lines();
        int lineIndex = (int) Math.floor((my - y - PAD + scroll) / lineHeight());
        lineIndex = Math.max(0, Math.min(ls.size() - 1, lineIndex));
        int[] line = ls.get(lineIndex);
        double localX = mx - x - PAD + (multiline ? 0 : scroll);
        int best = line[0];
        for (int i = line[0]; i <= line[1]; i++) {
            float w = PhoneDraw.width(text.substring(line[0], i), scale);
            if (w - 2 > localX) {
                break;
            }
            best = i;
        }
        return best;
    }

    // --- Отрисовка ---

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        PhoneDraw.field(g, x, y, x + width, y + height, focused);
        g.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
        List<int[]> ls = lines();
        float lh = lineHeight();
        int textColor = PhoneTheme.text();

        if (text.isEmpty() && !placeholder.isEmpty()) {
            PhoneDraw.text(g, placeholder, x + PAD, y + PAD + 1, PhoneTheme.secondaryText(), scale, false);
        }

        int selStart = anchor < 0 ? cursor : Math.min(anchor, cursor);
        int selEnd = anchor < 0 ? cursor : Math.max(anchor, cursor);
        float offsetX = multiline ? 0 : (float) scroll;

        for (int i = 0; i < ls.size(); i++) {
            int[] line = ls.get(i);
            float ly = (float) (y + PAD + i * lh - (multiline ? scroll : 0));
            if (ly + lh < y || ly > y + height) {
                continue;
            }
            float lx = x + PAD - offsetX;
            if (selEnd > selStart && selEnd > line[0] && selStart <= line[1]) {
                int a = Math.max(selStart, line[0]);
                int b = Math.min(selEnd, line[1]);
                float x1 = lx + PhoneDraw.width(text.substring(line[0], a), scale);
                float x2 = lx + PhoneDraw.width(text.substring(line[0], b), scale);
                if (b == line[1] && selEnd > line[1]) {
                    x2 += 2;
                }
                g.fill((int) x1, (int) ly, (int) Math.ceil(x2), (int) (ly + lh), PhoneTheme.accentTint(0x70));
            }
            String lineText = text.substring(line[0], line[1]).replace("\n", "");
            PhoneDraw.text(g, lineText, lx, ly + 1, textColor, scale, false);
        }

        if (focused && (System.currentTimeMillis() - lastInput) % 1000 < 500) {
            int line = lineOf(cursor);
            float cx = x + PAD - offsetX + xOf(cursor);
            float cy = (float) (y + PAD + line * lh - (multiline ? scroll : 0));
            g.fill((int) cx, (int) cy, (int) cx + 1, (int) (cy + lh), PhoneTheme.accent());
        }
        g.disableScissor();
    }

    // --- Мышь ---

    public boolean isMouseOver(double mx, double my) {
        return PhoneDraw.inside(mx, my, x, y, width, height);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (!isMouseOver(mx, my)) {
            setFocused(false);
            return false;
        }
        setFocused(true);
        cursor = indexAt(mx, my);
        anchor = cursor;
        return true;
    }

    public boolean mouseDragged(double mx, double my) {
        if (!focused || anchor < 0) {
            return false;
        }
        cursor = indexAt(mx, my);
        ensureCursorVisible();
        return true;
    }

    public boolean mouseReleased() {
        if (anchor == cursor) {
            anchor = -1;
        }
        return focused;
    }

    public boolean mouseScrolled(double mx, double my, double amount) {
        if (!multiline || !isMouseOver(mx, my)) {
            return false;
        }
        scroll -= amount * lineHeight() * 2;
        clampScroll();
        return true;
    }

    // --- Клавиатура ---

    public boolean keyPressed(KeyEvent event) {
        if (!focused) {
            return false;
        }
        lastInput = System.currentTimeMillis();
        boolean shift = event.hasShiftDown();
        boolean ctrl = event.hasControlDownWithQuirk();
        if (event.isSelectAll()) {
            anchor = 0;
            cursor = text.length();
            return true;
        }
        if (event.isCopy()) {
            if (hasSelection()) {
                Minecraft.getInstance().keyboardHandler.setClipboard(selectedText());
            }
            return true;
        }
        if (event.isCut()) {
            if (hasSelection()) {
                Minecraft.getInstance().keyboardHandler.setClipboard(selectedText());
                replaceSelection("");
            }
            return true;
        }
        if (event.isPaste()) {
            String clip = Minecraft.getInstance().keyboardHandler.getClipboard();
            insert(multiline ? clip.replace("\r", "") : clip.replace("\r", "").replace("\n", " "));
            return true;
        }
        switch (event.key()) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (hasSelection()) {
                    replaceSelection("");
                } else if (cursor > 0) {
                    int from = ctrl ? wordLeft(cursor) : cursor - 1;
                    delete(from, cursor);
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (hasSelection()) {
                    replaceSelection("");
                } else if (cursor < text.length()) {
                    int to = ctrl ? wordRight(cursor) : cursor + 1;
                    delete(cursor, to);
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                moveTo(ctrl ? wordLeft(cursor) : (hasSelection() && !shift ? selStart() : cursor - 1), shift);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                moveTo(ctrl ? wordRight(cursor) : (hasSelection() && !shift ? selEnd() : cursor + 1), shift);
                return true;
            }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                if (!multiline) {
                    return true;
                }
                int line = lineOf(cursor) + (event.key() == GLFW.GLFW_KEY_UP ? -1 : 1);
                if (line < 0) {
                    moveTo(0, shift);
                } else if (line >= lines().size()) {
                    moveTo(text.length(), shift);
                } else {
                    float targetX = xOf(cursor);
                    int[] l = lines().get(line);
                    int best = l[0];
                    for (int i = l[0]; i <= l[1]; i++) {
                        if (PhoneDraw.width(text.substring(l[0], i), scale) > targetX + 1) {
                            break;
                        }
                        best = i;
                    }
                    moveTo(best, shift);
                }
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                moveTo(ctrl ? 0 : lines().get(lineOf(cursor))[0], shift);
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                moveTo(ctrl ? text.length() : lines().get(lineOf(cursor))[1], shift);
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (multiline) {
                    insert("\n");
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    public boolean charTyped(CharacterEvent event) {
        if (!focused || !event.isAllowedChatCharacter()) {
            return false;
        }
        insert(event.codepointAsString());
        return true;
    }

    // --- Правка текста ---

    private boolean hasSelection() {
        return anchor >= 0 && anchor != cursor;
    }

    private int selStart() {
        return Math.min(anchor, cursor);
    }

    private int selEnd() {
        return Math.max(anchor, cursor);
    }

    private String selectedText() {
        return text.substring(selStart(), selEnd());
    }

    private void insert(String s) {
        if (hasSelection()) {
            replaceSelection(s);
            return;
        }
        int room = maxLength - text.length();
        if (room <= 0) {
            return;
        }
        if (s.length() > room) {
            s = s.substring(0, room);
        }
        text = text.substring(0, cursor) + s + text.substring(cursor);
        cursor += s.length();
        anchor = -1;
        changed();
    }

    private void replaceSelection(String s) {
        int a = selStart();
        int b = selEnd();
        text = text.substring(0, a) + text.substring(b);
        cursor = a;
        anchor = -1;
        if (!s.isEmpty()) {
            insert(s);
        } else {
            changed();
        }
    }

    private void delete(int from, int to) {
        from = Math.max(0, from);
        to = Math.min(text.length(), to);
        text = text.substring(0, from) + text.substring(to);
        cursor = from;
        anchor = -1;
        changed();
    }

    private void moveTo(int index, boolean select) {
        index = Math.max(0, Math.min(text.length(), index));
        if (select) {
            if (anchor < 0) {
                anchor = cursor;
            }
        } else {
            anchor = -1;
        }
        cursor = index;
        ensureCursorVisible();
    }

    private int wordLeft(int from) {
        int i = from;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private int wordRight(int from) {
        int i = from;
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private void changed() {
        layoutDirty = true;
        ensureCursorVisible();
        onChange.accept(text);
    }

    private void ensureCursorVisible() {
        if (multiline) {
            float top = lineOf(cursor) * lineHeight();
            float visible = height - PAD * 2;
            if (top < scroll) {
                scroll = top;
            } else if (top + lineHeight() > scroll + visible) {
                scroll = top + lineHeight() - visible;
            }
            clampScroll();
        } else {
            float cx = xOf(cursor);
            float visible = width - PAD * 2 - 2;
            if (cx < scroll) {
                scroll = cx;
            } else if (cx > scroll + visible) {
                scroll = cx - visible;
            }
        }
    }

    private void clampScroll() {
        float content = lines().size() * lineHeight();
        double max = Math.max(0, content - (height - PAD * 2));
        scroll = Math.max(0, Math.min(max, scroll));
    }
}
