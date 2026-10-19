package dev.blockphone.fabric.client.apps.calculator;

import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Калькулятор в стиле iOS. Поддерживает ввод с клавиатуры: цифры, + - * / . = Enter Backspace. */
public final class CalculatorApp extends PhoneApp {
    private static final int BUTTON = 33;
    private static final int GAP = 4;
    private static final int GRID_LEFT = (160 - (BUTTON * 4 + GAP * 3)) / 2;
    private static final int GRID_TOP = 100;

    private static final int DIGIT_BG = 0xFF333333;
    private static final int FUNC_BG = 0xFFA5A5A5;
    private static final int OP_BG = 0xFFFF9F0A;

    private enum Kind { DIGIT, FUNC, OP }

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
                new Key("=", 3, 4, 1, Kind.OP, calc::equals, null));
    }

    private Key digit(int d, int col, int row) {
        return new Key(String.valueOf(d), col, row, 1, Kind.DIGIT, () -> calc.digit(d), null);
    }

    private Key op(String label, int col, int row, Calculator.Op op) {
        return new Key(label, col, row, 1, Kind.OP, () -> calc.operation(op), op);
    }

    @Override
    public int backgroundColor() {
        return 0xFF000000;
    }

    @Override
    public int headerTextColor() {
        return 0xFFFFFFFF;
    }

    @Override
    public boolean lightStatusBar() {
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int right = contentWidth() - GRID_LEFT - 2;

        PhoneDraw.rightText(g, calc.expression(), right, 40, 0xFF8E8E93, 1f);

        String value = calc.display();
        float scale = 2.5f;
        while (scale > 1f && PhoneDraw.width(value, scale) > right - GRID_LEFT) {
            scale -= 0.25f;
        }
        PhoneDraw.rightText(g, value, right, 88 - 9 * scale, 0xFFFFFFFF, scale);

        for (Key key : keys) {
            int x = GRID_LEFT + key.col * (BUTTON + GAP);
            int y = GRID_TOP + key.row * (BUTTON + GAP);
            int w = BUTTON * key.span + GAP * (key.span - 1);
            boolean active = key.op != null && key.op == calc.pendingOp();
            int bg = switch (key.kind) {
                case DIGIT -> DIGIT_BG;
                case FUNC -> FUNC_BG;
                case OP -> active ? 0xFFFFFFFF : OP_BG;
            };
            int fg = key.kind == Kind.FUNC ? 0xFF000000 : (active ? OP_BG : 0xFFFFFFFF);
            PhoneDraw.roundedRect(g, x, y, x + w, y + BUTTON, BUTTON / 2, bg);
            if (PhoneDraw.inside(mouseX, mouseY, x, y, w, BUTTON)) {
                PhoneDraw.roundedRect(g, x, y, x + w, y + BUTTON, BUTTON / 2, 0x30FFFFFF);
            }
            float textX = key.span > 1 ? x + BUTTON / 2f : x + w / 2f;
            PhoneDraw.centeredText(g, key.label, textX, y + BUTTON / 2f - 6, fg, 1.5f, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) {
            return false;
        }
        for (Key key : keys) {
            int x = GRID_LEFT + key.col * (BUTTON + GAP);
            int y = GRID_TOP + key.row * (BUTTON + GAP);
            int w = BUTTON * key.span + GAP * (key.span - 1);
            if (PhoneDraw.inside(mx, my, x, y, w, BUTTON)) {
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
