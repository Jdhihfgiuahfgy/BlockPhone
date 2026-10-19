package dev.blockphone.fabric.client.camera;

import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.photo.CameraFilter;

/**
 * Параметры камеры и расчёт экспозиции.
 *
 * <p>Модель: 0 ступеней = ISO 400 и выдержка 1/125. Каждое удвоение ISO или выдержки — +1 ступень (вдвое светлее).
 * Если одно из двух — AUTO, оно подстраивается под другое (как приоритет выдержки/диафрагмы на камере).
 * Выдержка от 1/15 и длиннее даёт смаз движения: кадры копятся всё время выдержки.
 */
public final class CameraSettings {
    public static final int[] ISO = {0, 100, 200, 400, 800, 1600, 3200, 6400};
    /** Выдержка в секундах; 0 — авто. */
    public static final double[] SHUTTER = {0, 1 / 1000.0, 1 / 500.0, 1 / 250.0, 1 / 125.0, 1 / 60.0, 1 / 30.0,
            1 / 15.0, 1 / 8.0, 1 / 4.0, 1 / 2.0, 1.0};
    public static final float[] EV = {-2f, -1.5f, -1f, -0.5f, 0f, 0.5f, 1f, 1.5f, 2f};
    public static final int[] TIMER = {0, 3, 10};
    public static final float MIN_ZOOM = 1f;
    public static final float MAX_ZOOM = 10f;
    /** Выдержки не короче этой копят несколько кадров (смаз). */
    public static final double BLUR_THRESHOLD = 1 / 15.0;

    /** Параметры, которые листаются стрелками в нижней панели видоискателя. */
    public enum Param { ISO, SHUTTER, EV, FILTER, GRID, TIMER }

    public int iso;
    public int shutter;
    public int ev = 4;
    public CameraFilter filter = CameraFilter.NONE;
    public boolean grid = true;
    public int timer;
    public float zoom = 1f;
    public Param selected = Param.FILTER;

    public void reset() {
        iso = 0;
        shutter = 0;
        ev = 4;
        filter = CameraFilter.NONE;
        timer = 0;
        zoom = 1f;
    }

    public void change(Param param, int dir) {
        switch (param) {
            case ISO -> iso = Math.floorMod(iso + dir, ISO.length);
            case SHUTTER -> shutter = Math.floorMod(shutter + dir, SHUTTER.length);
            case EV -> ev = Math.max(0, Math.min(EV.length - 1, ev + dir));
            case FILTER -> filter = CameraFilter.values()[Math.floorMod(filter.ordinal() + dir, CameraFilter.values().length)];
            case GRID -> grid = !grid;
            case TIMER -> timer = Math.floorMod(timer + dir, TIMER.length);
        }
    }

    // --- Экспозиция ---

    private static double log2(double v) {
        return Math.log(v) / Math.log(2);
    }

    private double isoStops() {
        if (ISO[iso] != 0) {
            return log2(ISO[iso] / 400.0);
        }
        // Авто-ISO компенсирует ручную выдержку в пределах 100..6400.
        return Math.max(-2, Math.min(4, -shutterStopsManual()));
    }

    private double shutterStopsManual() {
        return SHUTTER[shutter] == 0 ? 0 : log2(SHUTTER[shutter] * 125.0);
    }

    private double shutterStops() {
        if (SHUTTER[shutter] != 0) {
            return shutterStopsManual();
        }
        // Авто-выдержка компенсирует ручной ISO в пределах 1/1000..1/30 (без смаза).
        return ISO[iso] == 0 ? 0 : Math.max(-3, Math.min(2, -isoStops()));
    }

    /** Итоговая яркость снимка в ступенях относительно «нормы». */
    public float exposureStops() {
        return (float) (isoStops() + shutterStops() + EV[ev]);
    }

    public int effectiveIso() {
        return (int) Math.round(400 * Math.pow(2, isoStops()));
    }

    /** Эффективная выдержка, секунды. */
    public double effectiveShutter() {
        return Math.pow(2, shutterStops()) / 125.0;
    }

    /** Сила шума 0..0.35: до ISO 400 шума нет, дальше растёт (800 ≈ 0.09, 6400 = 0.35). */
    public float noiseAmount() {
        double stopsAbove400 = log2(effectiveIso() / 400.0);
        return (float) Math.max(0, Math.min(0.35, stopsAbove400 / 4.0 * 0.35));
    }

    /** Сколько миллисекунд копить кадры (0 — один кадр). */
    public long exposureMillis() {
        double t = effectiveShutter();
        return t >= BLUR_THRESHOLD - 1e-6 ? Math.min(2000, Math.round(t * 1000)) : 0;
    }

    // --- Подписи ---

    public static String shutterLabel(double seconds) {
        if (seconds >= 1) {
            return Math.round(seconds) + "\"";
        }
        return "1/" + Math.round(1 / seconds);
    }

    public String isoLabel() {
        return ISO[iso] == 0 ? "A " + effectiveIso() : String.valueOf(ISO[iso]);
    }

    public String shutterLabel() {
        String value = shutterLabel(effectiveShutter());
        return SHUTTER[shutter] == 0 ? "A " + value : value;
    }

    public String evLabel() {
        float v = EV[ev];
        return (v > 0 ? "+" : v < 0 ? "-" : "±") + String.format(java.util.Locale.ROOT, "%.1f", Math.abs(v));
    }

    public String valueLabel(Param param) {
        return switch (param) {
            case ISO -> isoLabel();
            case SHUTTER -> shutterLabel();
            case EV -> evLabel();
            case FILTER -> filter.displayName();
            case GRID -> PhoneLang.tr(grid ? "settings.on" : "settings.off");
            case TIMER -> TIMER[timer] == 0 ? PhoneLang.tr("settings.off") : TIMER[timer] + PhoneLang.tr("camera.seconds");
        };
    }

    public static String paramLabel(Param param) {
        return PhoneLang.tr("camera.param." + param.name().toLowerCase(java.util.Locale.ROOT));
    }
}
