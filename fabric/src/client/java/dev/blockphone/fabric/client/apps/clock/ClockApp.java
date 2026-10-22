package dev.blockphone.fabric.client.apps.clock;

import dev.blockphone.fabric.client.clock.ClockService;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneNotifications;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.Scrollbar;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * «Часы»: будильники (по реальному времени, повтор по дням недели, подпись), секундомер (круги) и таймер (выбор часов,
 * минут и секунд, готовые варианты). Сама работа — в {@link ClockService}: звенит и при закрытом телефоне.
 */
public final class ClockApp extends PhoneApp {
    private enum Tab { ALARM, STOPWATCH, TIMER }

    private record Hit(int x, int y, int w, int h, Runnable action, int scrollDir) {
    }

    private static final int TABS_H = 20;
    private static final int[] PRESETS_MIN = {1, 3, 5, 10, 15, 30};

    private final List<Hit> hits = new ArrayList<>();
    private final TextArea label = new TextArea(false, 0.7f);
    private Tab tab = Tab.ALARM;
    private double scroll;
    /** Редактируемый будильник (null — список). */
    private PhoneSettings.@Nullable Alarm editing;
    private boolean editingNew;
    // Выбор времени таймера.
    private int pickH;
    private int pickM = 5;
    private int pickS;

    public ClockApp() {
        super("clock");
        label.setPlaceholder(PhoneLang.tr("clock.label_placeholder"));
        label.setMaxLength(40);
    }

    @Override
    public String title() {
        return editing != null ? PhoneLang.tr(editingNew ? "clock.new_alarm" : "clock.edit_alarm") : PhoneLang.tr("app.clock");
    }

    @Override
    public String headerAction() {
        return editing == null && tab == Tab.ALARM ? "+" : null;
    }

    @Override
    public void onHeaderAction() {
        java.time.LocalTime now = java.time.LocalTime.now().plusMinutes(1);
        PhoneSettings.Alarm alarm = new PhoneSettings.Alarm();
        alarm.hour = now.getHour();
        alarm.minute = now.getMinute();
        openEditor(alarm, true);
    }

    @Override
    public void onShown() {
        PhoneNotifications.remove("clock:");
    }

    @Override
    public boolean onBack() {
        if (editing != null) {
            closeEditor(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean capturesKeyboard() {
        return label.isFocused();
    }

    private void openEditor(PhoneSettings.Alarm alarm, boolean isNew) {
        editing = alarm;
        editingNew = isNew;
        label.setText(alarm.label == null ? "" : alarm.label);
        scroll = 0;
    }

    /** Закрыть редактор; save — сохранить (новый — добавить в список). */
    private void closeEditor(boolean save) {
        PhoneSettings.Alarm alarm = editing;
        editing = null;
        label.setFocused(false);
        if (alarm == null || !save) {
            return;
        }
        alarm.label = label.text().strip();
        PhoneSettings settings = PhoneSettings.get();
        if (editingNew) {
            alarm.enabled = true;
            settings.alarms.add(alarm);
        }
        settings.alarms.sort((a, b) -> a.hour * 60 + a.minute - (b.hour * 60 + b.minute));
        settings.save();
    }

    // --- Отрисовка ---

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        hits.clear();
        scrollTargets.clear();
        int w = contentWidth();
        int h = contentHeight();
        if (editing != null) {
            renderEditor(g, editing, mouseX, mouseY, w, h);
        } else {
            switch (tab) {
                case ALARM -> renderAlarms(g, mouseX, mouseY, w, h - TABS_H);
                case STOPWATCH -> renderStopwatch(g, mouseX, mouseY, w, h - TABS_H);
                case TIMER -> renderTimer(g, mouseX, mouseY, w, h - TABS_H);
            }
            renderTabs(g, w, h);
        }
        String ringing = ClockService.ringing();
        if (ringing != null) {
            // Звенит — большая полоса «Стоп» поверх всего.
            int top = 6;
            PhoneDraw.rawRoundedRect(g, 6, top, w - 6, top + 44, 6, PhoneTheme.accent());
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(ringing, w - 20, 0.75f), w / 2f, top + 6, PhoneTheme.onAccent(), 0.75f, false);
            int bw = 70;
            boolean hover = PhoneDraw.inside(mouseX, mouseY, (w - bw) / 2, top + 20, bw, 18);
            PhoneDraw.rawRoundedRect(g, (w - bw) / 2, top + 20, (w + bw) / 2, top + 38, 9, hover ? 0xFFFFFFFF : 0xE6FFFFFF);
            PhoneDraw.centeredText(g, PhoneLang.tr("clock.stop"), w / 2f, top + 25, PhoneTheme.accent(), 0.8f, false);
            hits.add(new Hit(6, top, w - 12, 44, ClockService::stopRinging, 0));
        }
    }

    private void renderTabs(GuiGraphics g, int w, int h) {
        int y = h - TABS_H;
        g.fill(0, y, w, h, PhoneTheme.header());
        g.fill(0, y, w, y + 1, PhoneTheme.separator());
        Tab[] tabs = Tab.values();
        int tw = w / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            boolean on = t == tab;
            PhoneDraw.centeredText(g, PhoneLang.tr("clock.tab." + t.name().toLowerCase(Locale.ROOT)), i * tw + tw / 2f, y + 7,
                    on ? PhoneTheme.accent() : PhoneTheme.secondaryText(), 0.7f, false);
            if (on) {
                g.fill(i * tw + 10, y + 1, (i + 1) * tw - 10, y + 2, PhoneTheme.accent());
            }
            hits.add(new Hit(i * tw, y, tw, TABS_H, () -> {
                tab = t;
                scroll = 0;
            }, 0));
        }
    }

    // --- Будильники ---

    private void renderAlarms(GuiGraphics g, int mx, int my, int w, int h) {
        List<PhoneSettings.Alarm> alarms = PhoneSettings.get().alarms;
        if (alarms.isEmpty()) {
            PhoneDraw.centeredText(g, PhoneLang.tr("clock.no_alarms"), w / 2f, h / 2f - 10, PhoneTheme.secondaryText(), 0.75f, false);
            PhoneDraw.centeredText(g, PhoneLang.tr("clock.no_alarms_hint"), w / 2f, h / 2f + 2, PhoneTheme.secondaryText(), 0.6f, false);
            return;
        }
        int rowH = 44;
        double max = Math.max(0, alarms.size() * rowH + 8 - h);
        scroll = Math.max(0, Math.min(max, scroll));
        Scrollbar.report(0, h, scroll, max, false, v -> scroll = v);
        g.enableScissor(0, 0, w, h);
        for (int i = 0; i < alarms.size(); i++) {
            PhoneSettings.Alarm alarm = alarms.get(i);
            int y = 6 + i * rowH - (int) scroll;
            if (y + rowH < 0 || y > h) {
                continue;
            }
            int ch = rowH - 6;
            boolean hover = my < h && PhoneDraw.inside(mx, my, 6, y, w - 12, ch);
            PhoneDraw.card(g, 6, y, w - 6, y + ch, hover);
            int color = alarm.enabled ? PhoneTheme.text() : PhoneTheme.secondaryText();
            PhoneDraw.text(g, String.format("%02d:%02d", alarm.hour, alarm.minute), 12, y + 5, color, 1.6f, false);
            String sub = days(alarm.days) + (alarm.label == null || alarm.label.isEmpty() ? "" : " · " + alarm.label);
            PhoneDraw.text(g, PhoneDraw.ellipsize(sub, w - 64, 0.55f), 12, y + 26, PhoneTheme.secondaryText(), 0.55f, false);
            // Переключатель.
            int sx = w - 36;
            int sy = y + (ch - 12) / 2;
            PhoneDraw.rawRoundedRect(g, sx, sy, sx + 22, sy + 12, 6, alarm.enabled ? PhoneTheme.accent() : PhoneTheme.field());
            int kx = alarm.enabled ? sx + 11 : sx + 1;
            PhoneDraw.rawRoundedRect(g, kx, sy + 1, kx + 10, sy + 11, 5, 0xFFFFFFFF);
            if (my >= 0 && my < h) {
                hits.add(new Hit(6, Math.max(0, y), w - 50, ch, () -> openEditor(alarm, false), 0));
                hits.add(new Hit(sx - 6, y, 34, ch, () -> {
                    alarm.enabled = !alarm.enabled;
                    PhoneSettings.get().save();
                }, 0));
            }
        }
        g.disableScissor();
    }

    /** «Каждый день», «Пн, Ср, Пт», «Будни», «Один раз». */
    private static String days(int mask) {
        if (mask == 0) {
            return PhoneLang.tr("clock.once");
        }
        if (mask == 0x7F) {
            return PhoneLang.tr("clock.every_day");
        }
        if (mask == 0x1F) {
            return PhoneLang.tr("clock.weekdays");
        }
        if (mask == 0x60) {
            return PhoneLang.tr("clock.weekends");
        }
        List<String> names = new ArrayList<>();
        for (int d = 0; d < 7; d++) {
            if ((mask & (1 << d)) != 0) {
                names.add(PhoneLang.tr("clock.day." + d));
            }
        }
        return String.join(", ", names);
    }

    private void renderEditor(GuiGraphics g, PhoneSettings.Alarm alarm, int mx, int my, int w, int h) {
        int y = 10;
        // Часы и минуты: «▲» сверху, «▼» снизу, колесо мыши над числом.
        y = picker(g, mx, my, w / 2 - 34, y, alarm.hour, 24, v -> alarm.hour = v, 2f);
        PhoneDraw.centeredText(g, ":", w / 2f, 31, PhoneTheme.text(), 2f, false);
        picker(g, mx, my, w / 2 + 6, 10, alarm.minute, 60, v -> alarm.minute = v, 2f);
        y += 8;
        PhoneDraw.text(g, PhoneLang.tr("clock.repeat").toUpperCase(Locale.ROOT), 10, y, PhoneTheme.secondaryText(), 0.55f, false);
        y += 10;
        int cw = (w - 20 - 6 * 3) / 7;
        for (int d = 0; d < 7; d++) {
            int x = 10 + d * (cw + 3);
            boolean on = (alarm.days & (1 << d)) != 0;
            boolean hover = PhoneDraw.inside(mx, my, x, y, cw, cw);
            PhoneDraw.rawRoundedRect(g, x, y, x + cw, y + cw, cw / 2, on ? PhoneTheme.accent() : hover ? PhoneTheme.cardHover() : PhoneTheme.field());
            PhoneDraw.centeredText(g, PhoneLang.tr("clock.day." + d).substring(0, Math.min(2, PhoneLang.tr("clock.day." + d).length())),
                    x + cw / 2f, y + cw / 2f - 3, on ? PhoneTheme.onAccent() : PhoneTheme.text(), 0.55f, false);
            int bit = 1 << d;
            hits.add(new Hit(x, y, cw, cw, () -> alarm.days ^= bit, 0));
        }
        y += cw + 4;
        PhoneDraw.text(g, days(alarm.days), 10, y, PhoneTheme.secondaryText(), 0.55f, false);
        y += 12;
        PhoneDraw.text(g, PhoneLang.tr("clock.label").toUpperCase(Locale.ROOT), 10, y, PhoneTheme.secondaryText(), 0.55f, false);
        y += 10;
        label.setBounds(8, y, w - 16, 16);
        label.render(g, mx, my);
        y += 26;
        int half = (w - 22) / 2;
        PhoneDraw.button(g, 8, y, editingNew ? w - 16 : half, 18, PhoneLang.tr("clock.save"), mx, my, PhoneDraw.ButtonStyle.PRIMARY);
        hits.add(new Hit(8, y, editingNew ? w - 16 : half, 18, () -> closeEditor(true), 0));
        if (!editingNew) {
            PhoneDraw.button(g, 14 + half, y, half, 18, PhoneLang.tr("clock.delete"), mx, my, PhoneDraw.ButtonStyle.DANGER);
            hits.add(new Hit(14 + half, y, half, 18, () -> {
                if (phone != null) {
                    phone.showDialog(PhoneDialog.confirmDelete(PhoneLang.tr("clock.delete"), PhoneLang.tr("clock.delete_confirm"), () -> {
                        PhoneSettings.get().alarms.remove(alarm);
                        PhoneSettings.get().save();
                        editing = null;
                    }));
                }
            }, 0));
        }
    }

    /**
     * Число со стрелками: «▲» прибавляет, «▼» убавляет (по кругу до max), колесо мыши над числом — тоже.
     * Возвращает y под ним.
     */
    private int picker(GuiGraphics g, int mx, int my, int x, int y, int value, int max, java.util.function.IntConsumer set, float scale) {
        int bw = 28;
        boolean upHover = PhoneDraw.inside(mx, my, x, y, bw, 12);
        PhoneDraw.centeredText(g, "▲", x + bw / 2f, y + 2, upHover ? PhoneTheme.accent() : PhoneTheme.secondaryText(), 1.0f, false);
        hits.add(new Hit(x, y, bw, 12, () -> set.accept((value + 1) % max), 0));
        int ny = y + 14;
        int nh = Math.round(9 * scale) + 4;
        PhoneDraw.rawRoundedRect(g, x - 2, ny - 2, x + bw + 2, ny + nh, 4, PhoneTheme.field());
        PhoneDraw.centeredText(g, String.format("%02d", value), x + bw / 2f, ny + 1, PhoneTheme.text(), scale, false);
        hits.add(new Hit(x - 2, ny - 2, bw + 4, nh + 2, () -> {
        }, 1));
        scrollTargets.add(new ScrollTarget(x - 2, ny - 2, bw + 4, nh + 2, value, max, set));
        int dy = ny + nh + 2;
        boolean downHover = PhoneDraw.inside(mx, my, x, dy, bw, 12);
        PhoneDraw.centeredText(g, "▼", x + bw / 2f, dy + 2, downHover ? PhoneTheme.accent() : PhoneTheme.secondaryText(), 1.0f, false);
        hits.add(new Hit(x, dy, bw, 12, () -> set.accept((value - 1 + max) % max), 0));
        return dy + 14;
    }

    private record ScrollTarget(int x, int y, int w, int h, int value, int max, java.util.function.IntConsumer set) {
    }

    private final List<ScrollTarget> scrollTargets = new ArrayList<>();

    // --- Секундомер ---

    private void renderStopwatch(GuiGraphics g, int mx, int my, int w, int h) {
        long ms = ClockService.stopwatchMs();
        PhoneDraw.centeredText(g, ClockService.format(ms, true), w / 2f, 30, PhoneTheme.text(), 2.2f, false);
        int y = 66;
        int half = (w - 22) / 2;
        boolean running = ClockService.stopwatchRunning();
        PhoneDraw.button(g, 8, y, half, 20, PhoneLang.tr(running ? "clock.pause" : ms > 0 ? "clock.resume" : "clock.start"), mx, my,
                running ? PhoneDraw.ButtonStyle.NORMAL : PhoneDraw.ButtonStyle.PRIMARY);
        hits.add(new Hit(8, y, half, 20, ClockService::toggleStopwatch, 0));
        String second = running ? "clock.lap" : "clock.reset";
        boolean enabled = running || ms > 0;
        PhoneDraw.button(g, 14 + half, y, half, 20, PhoneLang.tr(second), mx, my, PhoneDraw.ButtonStyle.NORMAL);
        if (enabled) {
            hits.add(new Hit(14 + half, y, half, 20, running ? ClockService::lap : ClockService::resetStopwatch, 0));
        }
        y += 30;
        List<Long> laps = ClockService.laps();
        if (laps.isEmpty()) {
            return;
        }
        int rowH = 16;
        int top = y;
        double max = Math.max(0, laps.size() * rowH - (h - top));
        scroll = Math.max(0, Math.min(max, scroll));
        Scrollbar.report(top, h, scroll, max, false, v -> scroll = v);
        g.enableScissor(0, top, w, h);
        // Лучший и худший круг — зелёным и красным, как на телефонах.
        long best = Long.MAX_VALUE;
        long worst = 0;
        for (int i = 0; i < laps.size(); i++) {
            long d = laps.get(i) - (i + 1 < laps.size() ? laps.get(i + 1) : 0);
            best = Math.min(best, d);
            worst = Math.max(worst, d);
        }
        for (int i = 0; i < laps.size(); i++) {
            int ry = top + i * rowH - (int) scroll;
            long d = laps.get(i) - (i + 1 < laps.size() ? laps.get(i + 1) : 0);
            int color = laps.size() > 1 && d == best ? PhoneTheme.online() : laps.size() > 1 && d == worst ? PhoneTheme.danger() : PhoneTheme.text();
            PhoneDraw.text(g, PhoneLang.tr("clock.lap_n", laps.size() - i), 12, ry + 4, color, 0.65f, false);
            PhoneDraw.rightText(g, ClockService.format(d, true), w - 70, ry + 4, color, 0.65f);
            PhoneDraw.rightText(g, ClockService.format(laps.get(i), true), w - 12, ry + 4, PhoneTheme.secondaryText(), 0.65f);
            g.fill(10, ry + rowH - 1, w - 10, ry + rowH, PhoneTheme.separator());
        }
        g.disableScissor();
    }

    // --- Таймер ---

    private void renderTimer(GuiGraphics g, int mx, int my, int w, int h) {
        boolean active = ClockService.timerRunning() || ClockService.timerPaused();
        if (active) {
            long left = ClockService.timerLeft();
            long total = Math.max(1, ClockService.timerTotal());
            int cx = w / 2;
            int cy = 62;
            ring(g, cx, cy, 46, 1f - left / (float) total);
            PhoneDraw.centeredText(g, ClockService.format(left + 999, false), cx, cy - 8, PhoneTheme.text(), 1.7f, false);
            PhoneDraw.centeredText(g, PhoneLang.tr("clock.of", ClockService.format(total, false)), cx, cy + 10, PhoneTheme.secondaryText(), 0.55f, false);
            int y = cy + 56;
            int half = (w - 22) / 2;
            boolean running = ClockService.timerRunning();
            PhoneDraw.button(g, 8, y, half, 20, PhoneLang.tr("clock.cancel"), mx, my, PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(8, y, half, 20, ClockService::cancelTimer, 0));
            PhoneDraw.button(g, 14 + half, y, half, 20, PhoneLang.tr(running ? "clock.pause" : "clock.resume"), mx, my,
                    PhoneDraw.ButtonStyle.PRIMARY);
            hits.add(new Hit(14 + half, y, half, 20, running ? ClockService::pauseTimer : ClockService::resumeTimer, 0));
            return;
        }
        int y = 12;
        int col = 34;
        int x0 = (w - col * 3 - 16) / 2;
        picker(g, mx, my, x0, y, pickH, 24, v -> pickH = v, 1.8f);
        PhoneDraw.centeredText(g, ":", x0 + col + 4, y + 18, PhoneTheme.text(), 1.6f, false);
        picker(g, mx, my, x0 + col + 8, y, pickM, 60, v -> pickM = v, 1.8f);
        PhoneDraw.centeredText(g, ":", x0 + col * 2 + 12, y + 18, PhoneTheme.text(), 1.6f, false);
        int end = picker(g, mx, my, x0 + col * 2 + 16, y, pickS, 60, v -> pickS = v, 1.8f);
        String[] units = {"clock.h", "clock.m", "clock.s"};
        for (int i = 0; i < 3; i++) {
            PhoneDraw.centeredText(g, PhoneLang.tr(units[i]), x0 + i * (col + 8) + 14, end, PhoneTheme.secondaryText(), 0.5f, false);
        }
        y = end + 12;
        // Готовые варианты.
        int pw = (w - 16 - 2 * 4) / 3;
        for (int i = 0; i < PRESETS_MIN.length; i++) {
            int px = 8 + (i % 3) * (pw + 4);
            int py = y + (i / 3) * 20;
            int minutes = PRESETS_MIN[i];
            boolean hover = PhoneDraw.inside(mx, my, px, py, pw, 16);
            PhoneDraw.rawRoundedRect(g, px, py, px + pw, py + 16, 8, hover ? PhoneTheme.cardHover() : PhoneTheme.field());
            PhoneDraw.centeredText(g, PhoneLang.tr("clock.minutes", minutes), px + pw / 2f, py + 5, PhoneTheme.text(), 0.6f, false);
            hits.add(new Hit(px, py, pw, 16, () -> {
                pickH = minutes / 60;
                pickM = minutes % 60;
                pickS = 0;
            }, 0));
        }
        y += 46;
        long ms = (pickH * 3600L + pickM * 60L + pickS) * 1000L;
        PhoneDraw.button(g, 8, y, w - 16, 20, PhoneLang.tr("clock.start"), mx, my,
                ms > 0 ? PhoneDraw.ButtonStyle.PRIMARY : PhoneDraw.ButtonStyle.NORMAL);
        if (ms > 0) {
            hits.add(new Hit(8, y, w - 16, 20, () -> ClockService.startTimer(ms), 0));
        }
    }

    /** Кольцо хода таймера из точек (без шейдеров): пройдено — акцентом. */
    private static void ring(GuiGraphics g, int cx, int cy, int r, float done) {
        int dots = 72;
        for (int i = 0; i < dots; i++) {
            double a = -Math.PI / 2 + i * 2 * Math.PI / dots;
            int x = cx + (int) Math.round(Math.cos(a) * r);
            int y = cy + (int) Math.round(Math.sin(a) * r);
            boolean on = i / (float) dots >= done;
            g.fill(x - 1, y - 1, x + 2, y + 2, on ? PhoneTheme.accent() : PhoneTheme.field());
        }
    }

    // --- Ввод ---

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (editing != null) {
            if (label.mouseClicked(x, y, button)) {
                return true;
            }
            label.setFocused(false);
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        for (ScrollTarget t : scrollTargets) {
            if (PhoneDraw.inside(x, y, t.x(), t.y(), t.w(), t.h())) {
                int step = amount > 0 ? 1 : -1;
                t.set().accept(Math.floorMod(t.value() + step, t.max()));
                return true;
            }
        }
        scroll -= amount * 16;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return label.isFocused() && label.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return label.isFocused() && label.charTyped(event);
    }

    /** Для автоснимков. */
    public void debug(String what) {
        switch (what) {
            case "stopwatch" -> tab = Tab.STOPWATCH;
            case "timer" -> tab = Tab.TIMER;
            case "alarm" -> tab = Tab.ALARM;
            case "edit" -> {
                if (!PhoneSettings.get().alarms.isEmpty()) {
                    openEditor(PhoneSettings.get().alarms.get(0), false);
                }
            }
            case "close" -> editing = null;
            default -> {
            }
        }
    }
}
