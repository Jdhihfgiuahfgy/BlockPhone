package dev.blockphone.fabric.client.apps.calculator;

import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Калькулятор: дисплей-панель и сетка кнопок (цифры — панели, функции — приглушённые,
 * операции — с подсветкой акцентом, «=» — залита акцентом). Ввод с клавиатуры: цифры, + - * / . = Enter Backspace.
 */
public final class CalculatorApp extends PhoneApp {
    private static final int BUTTON = 33;
    private static final int GAP = 4;
    private static final int GRID_LEFT = (160 - (BUTTON * 4 + GAP * 3)) / 2;
    private static final int DISPLAY_TOP = 6;
    private static final int DISPLAY_H = 74;
    private static final int GRID_TOP = DISPLAY_TOP + DISPLAY_H + 10;

    private enum Kind { DIGIT, FUNC, OP, EQUALS }

    private record Key(String label, int col, int row, int span, Kind kind, Runnable action, Calculator.Op op) {
    }

    private final Calculator calc = new Calculator();
    private final List<Key> keys;

    public CalculatorApp() {
        super("calculator");
        keys = List.of(
                new Key("AC", 0, 0, 1, Kind.FUNC, calc::clear, null),
                new Key("±", 1, 0, 1, Kind.FUNC, calc::negate, null),
                new Key("%", 2, 0, 1, Kind.FUNC, calc::percent, null),
                op("÷", 3, 0, Calculator.Op.DIVIDE),
                digit(7, 0, 1), digit(8, 1, 1), digit(9, 2, 1),
                op("×", 3, 1, Calculator.Op.MULTIPLY),
                digit(4, 0, 2), digit(5, 1, 2), digit(6, 2, 2),
                op("-", 3, 2, Calculator.Op.SUBTRACT),
                digit(1, 0, 3), digit(2, 1, 3), digit(3, 2, 3),
                op("+", 3, 3, Calculator.Op.ADD),
                new Key("0", 0, 4, 2, Kind.DIGIT, () -> calc.digit(0), null),
                new Key(".", 2, 4, 1, Kind.DIGIT, calc::point, null),
                new Key("=", 3, 4, 1, Kind.EQUALS, calc::equals, null));
    }

    private Key digit(int d, int col, int row) {
        return new Key(String.valueOf(d), col, row, 1, Kind.DIGIT, () -> calc.digit(d), null);
    }

    private Key op(String label, int col, int row, Calculator.Op op) {
        return new Key(label, col, row, 1, Kind.OP, () -> calc.operation(op), op);
    }

    private static int keyX(Key key) {
        return GRID_LEFT + key.col * (BUTTON + GAP);
    }

    private static int keyY(Key key) {
        return GRID_TOP + key.row * (BUTTON + GAP);
    }

    private static int keyW(Key key) {
        return BUTTON * key.span + GAP * (key.span - 1);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int w = contentWidth();
        PhoneDraw.panel(g, GRID_LEFT, DISPLAY_TOP, w - GRID_LEFT, DISPLAY_TOP + DISPLAY_H, PhoneTheme.field());
        int right = w - GRID_LEFT - 6;
        PhoneDraw.rightText(g, calc.expression(), right, DISPLAY_TOP + 8, PhoneTheme.secondaryText(), 1f);

        String value = calc.display();
        float scale = 2.5f;
        while (scale > 1f && PhoneDraw.width(value, scale) > w - 2 * GRID_LEFT - 12) {
            scale -= 0.25f;
        }
        PhoneDraw.rightText(g, value, right, DISPLAY_TOP + DISPLAY_H - 6 - 8 * scale, PhoneTheme.text(), scale);

        for (Key key : keys) {
            int x = keyX(key);
            int y = keyY(key);
            int kw = keyW(key);
            boolean hovered = PhoneDraw.inside(mouseX, mouseY, x, y, kw, BUTTON);
            boolean active = key.op != null && key.op == calc.pendingOp();
            int fg;
            switch (key.kind) {
                case DIGIT -> {
                    PhoneDraw.card(g, x, y, x + kw, y + BUTTON, hovered);
                    fg = PhoneTheme.text();
                }
                case FUNC -> {
                    PhoneDraw.panel(g, x, y, x + kw, y + BUTTON, hovered ? PhoneTheme.border() : PhoneTheme.cardHover());
                    fg = PhoneTheme.secondaryText();
                }
                case OP -> {
                    if (active) {
                        PhoneDraw.roundedRect(g, x, y, x + kw, y + BUTTON, PhoneTheme.RADIUS, PhoneTheme.accent());
                        fg = PhoneTheme.onAccent();
                    } else {
                        PhoneDraw.roundedRect(g, x, y, x + kw, y + BUTTON, PhoneTheme.RADIUS, PhoneTheme.accentTint(hovered ? 0x70 : 0x45));
                        fg = PhoneTheme.dark() ? PhoneTheme.mix(PhoneTheme.accent(), PhoneTheme.WHITE, 0.35f) : PhoneTheme.accent();
                    }
                }
                default -> {
                    PhoneDraw.roundedRect(g, x, y, x + kw, y + BUTTON, PhoneTheme.RADIUS, hovered ? PhoneTheme.accentHover() : PhoneTheme.accent());
                    fg = PhoneTheme.onAccent();
                }
            }
            float textX = key.span > 1 ? x + BUTTON / 2f : x + kw / 2f;
            PhoneDraw.centeredText(g, key.label, textX, y + BUTTON / 2f - 6, fg, 1.5f, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        for (Key key : keys) {
            if (PhoneDraw.inside(mx, my, keyX(key), keyY(key), keyW(key), BUTTON)) {
                key.action.run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            calc.equals();
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            calc.backspace();
            return true;
        }
        if (key == GLFW.GLFW_KEY_DELETE) {
            calc.clear();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        int c = event.codepoint();
        if (c >= '0' && c <= '9') {
            calc.digit(c - '0');
        } else if (c == '.' || c == ',') {
            calc.point();
        } else if (c == '+') {
            calc.operation(Calculator.Op.ADD);
        } else if (c == '-') {
            calc.operation(Calculator.Op.SUBTRACT);
        } else if (c == '*') {
            calc.operation(Calculator.Op.MULTIPLY);
        } else if (c == '/') {
            calc.operation(Calculator.Op.DIVIDE);
        } else if (c == '=') {
            calc.equals();
        } else if (c == '%') {
            calc.percent();
        } else {
            return false;
        }
        return true;
    }
}
