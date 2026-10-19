package dev.blockphone.fabric.client.apps.calculator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** Логика калькулятора (без UI): ввод числа, операции по цепочке, %, ±. */
public final class Calculator {
    public enum Op {
        ADD("+"), SUBTRACT("-"), MULTIPLY("×"), DIVIDE("÷");

        public final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }
    }

    private static final int MAX_DIGITS = 12;
    private static final MathContext MC = new MathContext(14, RoundingMode.HALF_UP);

    private String entry = "0";
    private BigDecimal accumulator;
    private Op pending;
    /** Следующая цифра начинает новое число (после операции или «=»). */
    private boolean freshEntry = true;
    private boolean error;

    public String display() {
        return error ? "Error" : entry;
    }

    /** Строка над результатом, например «12 ×». */
    public String expression() {
        if (error || pending == null || accumulator == null) {
            return "";
        }
        return format(accumulator) + " " + pending.symbol;
    }

    public Op pendingOp() {
        return pending;
    }

    public void digit(int d) {
        clearErrorIfNeeded();
        if (freshEntry) {
            entry = String.valueOf(d);
            freshEntry = false;
        } else if (entry.equals("0")) {
            entry = String.valueOf(d);
        } else if (entry.equals("-0")) {
            entry = "-" + d;
        } else if (countDigits(entry) < MAX_DIGITS) {
            entry += d;
        }
    }

    public void point() {
        clearErrorIfNeeded();
        if (freshEntry) {
            entry = "0.";
            freshEntry = false;
        } else if (!entry.contains(".")) {
            entry += ".";
        }
    }

    public void backspace() {
        if (error || freshEntry) {
            return;
        }
        entry = entry.substring(0, entry.length() - 1);
        if (entry.isEmpty() || entry.equals("-")) {
            entry = "0";
        }
    }

    public void operation(Op op) {
        if (error) {
            return;
        }
        if (pending != null && !freshEntry) {
            if (!compute()) {
                return;
            }
        } else if (pending == null) {
            accumulator = new BigDecimal(entry);
        }
        pending = op;
        freshEntry = true;
    }

    public void equals() {
        if (error || pending == null) {
            return;
        }
        if (compute()) {
            pending = null;
            freshEntry = true;
        }
    }

    public void negate() {
        if (error) {
            return;
        }
        entry = entry.startsWith("-") ? entry.substring(1) : "-" + entry;
    }

    public void percent() {
        if (error) {
            return;
        }
        entry = format(new BigDecimal(entry).divide(BigDecimal.valueOf(100), MC));
    }

    public void clear() {
        entry = "0";
        accumulator = null;
        pending = null;
        freshEntry = true;
        error = false;
    }

    private boolean compute() {
        BigDecimal right = new BigDecimal(entry);
        BigDecimal result;
        switch (pending) {
            case ADD -> result = accumulator.add(right, MC);
            case SUBTRACT -> result = accumulator.subtract(right, MC);
            case MULTIPLY -> result = accumulator.multiply(right, MC);
            case DIVIDE -> {
                if (right.signum() == 0) {
                    error = true;
                    return false;
                }
                result = accumulator.divide(right, MC);
            }
            default -> throw new IllegalStateException();
        }
        accumulator = result;
        entry = format(result);
        return true;
    }

    private void clearErrorIfNeeded() {
        if (error) {
            clear();
        }
    }

    static String format(BigDecimal value) {
        BigDecimal rounded = value.round(new MathContext(MAX_DIGITS, RoundingMode.HALF_UP)).stripTrailingZeros();
        if (rounded.abs().compareTo(BigDecimal.TEN.pow(MAX_DIGITS)) >= 0
                || (rounded.signum() != 0 && rounded.abs().compareTo(new BigDecimal("1e-9")) < 0)) {
            return rounded.toString().replace("E+", "e").replace("E", "e");
        }
        return rounded.toPlainString();
    }

    private static int countDigits(String s) {
        int n = 0;
        for (char c : s.toCharArray()) {
            if (Character.isDigit(c)) {
                n++;
            }
        }
        return n;
    }
}
