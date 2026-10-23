package dev.blockphone.fabric.client.apps.store;

import dev.blockphone.fabric.client.ui.Scrollbar;
import dev.blockphone.common.apps.AppDefinition;
import dev.blockphone.fabric.client.apps.custom.CustomApps;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.AppRegistry;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.store.AppStore;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * «Магазин»: каталог приложений из источников (ссылки на catalog.json), страница приложения (описание, разрешения,
 * «Установить» / «Обновить» / «Открыть» / «Удалить»), установленные приложения, список источников.
 */
public final class StoreApp extends PhoneApp {
    private enum Tab { CATALOG, INSTALLED, SOURCES }

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private static final int TABS_H = 20;
    private static final int ROW_H = 46;
    /** Ширина кнопки в строке каталога (одна для всех — текст слева не налезает). */
    private static final int ROW_BTN = 42;
    private static final int STAR = 0xFFFFC233;

    private final List<Hit> hits = new ArrayList<>();
    private final TextArea search = new TextArea(false, 0.75f);
    private final TextArea sourceInput = new TextArea(false, 0.7f);
    private final TextArea reviewInput = new TextArea(true, 0.65f);
    /** Выбранная оценка в форме отзыва (0 — не выбрана). */
    private int myStars;
    private @Nullable String reviewFor;
    private Tab tab = Tab.CATALOG;
    private @Nullable AppStore.Entry detail;
    private double scroll;
    private String notice = "";
    private long noticeAt;
    private boolean loadedOnce;

    public StoreApp() {
        super("store");
        search.setPlaceholder(PhoneLang.tr("store.search"));
        sourceInput.setPlaceholder("https://raw.githubusercontent.com/…/catalog.json");
        sourceInput.setMaxLength(300);
        reviewInput.setPlaceholder(PhoneLang.tr("store.review_placeholder"));
        reviewInput.setMaxLength(500);
    }

    @Override
    public String title() {
        return detail != null ? detail.name() : PhoneLang.tr("app.store");
    }

    @Override
    public String headerAction() {
        return detail == null ? "⟳" : null;
    }

    @Override
    public void onHeaderAction() {
        AppStore.refresh();
    }

    @Override
    public void onShown() {
        if (!loadedOnce) {
            loadedOnce = true;
            AppStore.refresh();
        }
    }

    @Override
    public boolean onBack() {
        if (detail != null) {
            detail = null;
            reviewInput.setFocused(false);
            scroll = 0;
            return true;
        }
        return false;
    }

    @Override
    public boolean capturesKeyboard() {
        return search.isFocused() || sourceInput.isFocused() || reviewInput.isFocused();
    }

    private void toast(String text) {
        notice = text;
        noticeAt = System.currentTimeMillis();
    }

    // --- Отрисовка ---

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        hits.clear();
        int w = contentWidth();
        int h = contentHeight();
        if (detail != null) {
            renderDetail(g, detail, mouseX, mouseY, w, h);
        } else {
            int listH = h - TABS_H;
            switch (tab) {
                case CATALOG -> renderCatalog(g, mouseX, mouseY, w, listH);
                case INSTALLED -> renderInstalled(g, mouseX, mouseY, w, listH);
                case SOURCES -> renderSources(g, mouseX, mouseY, w, listH);
            }
            renderTabs(g, mouseX, mouseY, w, h);
        }
        if (!notice.isEmpty() && System.currentTimeMillis() - noticeAt < 3500) {
            int th = PhoneDraw.wrappedHeight(notice, w - 28, 0.7f) + 8;
            int ty = h - TABS_H - th - 6;
            PhoneDraw.rawRoundedRect(g, 8, ty, w - 8, ty + th, 4, 0xF0202838);
            PhoneDraw.wrapped(g, notice, 14, ty + 4, w - 28, 0xFFFFFFFF, 0.7f, false);
        }
    }

    private void renderTabs(GuiGraphics g, int mx, int my, int w, int h) {
        int y = h - TABS_H;
        g.fill(0, y, w, h, PhoneTheme.header());
        g.fill(0, y, w, y + 1, PhoneTheme.separator());
        Tab[] tabs = Tab.values();
        int tw = w / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            Tab t = tabs[i];
            boolean on = t == tab;
            PhoneDraw.centeredText(g, PhoneLang.tr("store.tab." + t.name().toLowerCase(Locale.ROOT)), i * tw + tw / 2f, y + 7,
                    on ? PhoneTheme.accent() : PhoneTheme.secondaryText(), 0.7f, false);
            if (on) {
                g.fill(i * tw + 10, y + 1, (i + 1) * tw - 10, y + 2, PhoneTheme.accent());
            }
            hits.add(new Hit(i * tw, y, tw, TABS_H, () -> {
                tab = t;
                scroll = 0;
            }));
        }
    }

    private void renderCatalog(GuiGraphics g, int mx, int my, int w, int h) {
        search.setBounds(6, 4, w - 12, 16);
        search.render(g, mx, my);
        int top = 24;
        if (AppStore.state() == AppStore.State.LOADING && AppStore.entries().isEmpty()) {
            PhoneDraw.centeredText(g, PhoneLang.tr("messenger.loading"), w / 2f, top + 30, PhoneTheme.secondaryText(), 0.75f, false);
            return;
        }
        String q = search.text().strip().toLowerCase(Locale.ROOT);
        List<AppStore.Entry> list = new ArrayList<>();
        for (AppStore.Entry e : AppStore.entries()) {
            if (q.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(q) || e.description().toLowerCase(Locale.ROOT).contains(q)
                    || e.author().toLowerCase(Locale.ROOT).contains(q)) {
                list.add(e);
            }
        }
        if (list.isEmpty()) {
            String text = !AppStore.errors().isEmpty() ? PhoneLang.tr("store.error", AppStore.errors().get(0)) : PhoneLang.tr("store.empty");
            PhoneDraw.wrapped(g, text, 12, top + 20, w - 24, PhoneTheme.secondaryText(), 0.65f, true);
            return;
        }
        scroll = Math.max(0, Math.min(Math.max(0, list.size() * ROW_H - (h - top)), scroll));
        Scrollbar.report(top, h, scroll, Math.max(0, list.size() * ROW_H - (h - top)), false, v -> scroll = v);
        g.enableScissor(0, top, w, h);
        for (int i = 0; i < list.size(); i++) {
            int y = top + i * ROW_H - (int) scroll;
            if (y + ROW_H < top || y > h) {
                continue;
            }
            AppStore.Entry e = list.get(i);
            row(g, e, y, w, mx, my, top, h);
        }
        g.disableScissor();
    }

    private void row(GuiGraphics g, AppStore.Entry e, int y, int w, int mx, int my, int top, int bottom) {
        boolean visible = my >= top && my < bottom;
        int cy = y + 2;
        int ch = ROW_H - 4;
        boolean hover = visible && PhoneDraw.inside(mx, my, 6, cy, w - 12, ch);
        PhoneDraw.card(g, 6, cy, w - 6, cy + ch, hover);
        drawIcon(g, e, 10, cy + (ch - 28) / 2, 28, hover ? PhoneTheme.cardHover() : PhoneTheme.card());
        int bx = w - 10 - ROW_BTN;
        int by = cy + (ch - 16) / 2;
        int tx = 43;
        int tw = bx - 4 - tx;
        PhoneDraw.text(g, PhoneDraw.ellipsize(e.name(), tw, 0.72f), tx, cy + 6, PhoneTheme.text(), 0.72f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(e.author().isEmpty() ? e.catalog() : e.author(), tw, 0.6f), tx, cy + 16,
                PhoneTheme.secondaryText(), 0.6f, false);
        meta(g, e, tx, cy + 27, tw, 0.55f);
        // Клики проверяются с конца: строку — раньше кнопки, иначе «Установить» открывала страницу приложения.
        if (visible) {
            hits.add(new Hit(6, Math.max(top, cy), w - 12, ch, () -> {
                detail = e;
                scroll = 0;
            }));
        }
        actionButton(g, e, bx, by, ROW_BTN, 16, mx, my, visible && by >= top && by + 16 <= bottom);
    }

    /** Строка «★ 4.6 · 1.2 MB · v1.0». */
    private void meta(GuiGraphics g, AppStore.Entry e, int x, int y, int maxW, float scale) {
        AppStore.Rating r = AppStore.rating(e.id());
        if (r != null && r.count() > 0) {
            String stars = "★ " + String.format(Locale.ROOT, "%.1f", r.average());
            PhoneDraw.text(g, stars, x, y, STAR, scale, false);
            x += (int) PhoneDraw.width(stars + "  ", scale);
            maxW -= (int) PhoneDraw.width(stars + "  ", scale);
        }
        String size = AppStore.formatSize(e.size());
        String rest = (size.isEmpty() ? "" : size + " · ") + "v" + e.version();
        PhoneDraw.text(g, PhoneDraw.ellipsize(rest, maxW, scale), x, y, PhoneTheme.secondaryText(), scale, false);
    }

    /** Подпись главного действия. */
    private static String actionLabel(AppStore.Entry e) {
        String installed = AppStore.installed(e.id());
        return installed == null ? PhoneLang.tr("store.get")
                : installed.equals(e.version()) ? PhoneLang.tr("store.open") : PhoneLang.tr("store.update");
    }

    /** Кнопка «Установить/Обновить/Открыть»; во время установки — полоса хода с процентами. */
    private void actionButton(GuiGraphics g, AppStore.Entry e, int x, int y, int w, int h, int mx, int my, boolean clickable) {
        float progress = AppStore.progress(e.id());
        if (progress >= 0) {
            PhoneDraw.rawRoundedRect(g, x, y, x + w, y + h, h / 2, PhoneTheme.accentTint(0x40));
            int fill = Math.max(h, Math.round(w * progress));
            PhoneDraw.rawRoundedRect(g, x, y, x + fill, y + h, h / 2, PhoneTheme.accent());
            String label = progress >= 1 ? PhoneLang.tr("store.unpacking") : Math.round(progress * 100) + "%";
            // Текст — цветом, который виден и на заливке, и на пустой части полосы.
            int color = progress >= 0.5f ? PhoneTheme.onAccent() : PhoneTheme.text();
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(label, w - 6, 0.6f), x + w / 2f, y + (h - 6) / 2f, color, 0.6f, false);
            return;
        }
        String installed = AppStore.installed(e.id());
        boolean primary = installed == null || !installed.equals(e.version());
        String label = actionLabel(e);
        boolean hover = clickable && PhoneDraw.inside(mx, my, x, y, w, h);
        int bg = primary ? (hover ? PhoneTheme.accentHover() : PhoneTheme.accent()) : (hover ? PhoneTheme.cardHover() : PhoneTheme.field());
        PhoneDraw.rawRoundedRect(g, x, y, x + w, y + h, h / 2, bg);
        float scale = h >= 18 ? 0.75f : 0.55f;
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(label, w - 8, scale), x + w / 2f, y + (h - 8 * scale) / 2f,
                primary ? PhoneTheme.onAccent() : PhoneTheme.accent(), scale, false);
        if (clickable) {
            hits.add(new Hit(x, y, w, h, () -> primary(e)));
        }
    }

    private void drawIcon(GuiGraphics g, AppStore.Entry e, int x, int y, int size) {
        drawIcon(g, e, x, y, size, PhoneTheme.background());
    }

    /** under — цвет под иконкой (им закрашиваются углы картинки). */
    private void drawIcon(GuiGraphics g, AppStore.Entry e, int x, int y, int size, int under) {
        Path icon = AppStore.icon(e);
        PhotoTextures.Image image = icon != null ? PhotoTextures.preview(new PhotoStore.Photo(icon, 0), 64) : null;
        if (image != null) {
            PhoneDraw.textureCover(g, image.id(), x, y, size, size, image.width(), image.height());
            PhoneDraw.roundCornersMask(g, x, y, x + size, y + size, Math.max(5, size / 6), under);
        } else {
            AppDefinition def = CustomApps.get(e.id());
            int color = def != null ? def.color() : 0xFF4C8DF6;
            PhoneDraw.rawRoundedRect(g, x, y, x + size, y + size, 5, color);
            PhoneDraw.centeredText(g, e.name().isEmpty() ? "?" : e.name().substring(0, 1).toUpperCase(Locale.ROOT), x + size / 2f,
                    y + size / 2f - 5, 0xFFFFFFFF, 1.2f, false);
        }
    }

    /** Главная кнопка: установить / обновить (с подтверждением разрешений) или открыть. */
    private void primary(AppStore.Entry e) {
        String installed = AppStore.installed(e.id());
        if (AppStore.busy(e.id()) != null) {
            return;
        }
        if (installed != null && installed.equals(e.version())) {
            if (phone != null) {
                phone.openApp(CustomApps.PREFIX + e.id());
            }
            return;
        }
        String perms = e.permissions().isEmpty() ? PhoneLang.tr("store.no_permissions")
                : String.join(", ", e.permissions().stream().map(p -> PhoneLang.tr("app.perm." + p)).toList());
        String message = PhoneLang.tr("store.confirm", e.name(), e.author().isEmpty() ? e.catalog() : e.author(), perms);
        if (phone != null) {
            phone.showDialog(new PhoneDialog(e.name(), message, PhoneLang.tr(installed == null ? "store.get" : "store.update"), false,
                    () -> AppStore.install(e, error -> toast(error == null ? PhoneLang.tr("store.installed", e.name())
                            : PhoneLang.tr("store.failed", error)))));
        }
    }

    private void renderDetail(GuiGraphics g, AppStore.Entry e, int mx, int my, int w, int h) {
        int y = 8 - (int) scroll;
        drawIcon(g, e, 10, y, 48);
        int tx = 66;
        PhoneDraw.text(g, PhoneDraw.ellipsize(e.name(), w - tx - 8, 0.95f), tx, y + 4, PhoneTheme.text(), 0.95f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(e.author().isEmpty() ? PhoneLang.tr("store.unknown_author") : e.author(), w - tx - 8, 0.65f),
                tx, y + 17, PhoneTheme.accent(), 0.65f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(e.catalog(), w - tx - 8, 0.55f), tx, y + 28, PhoneTheme.secondaryText(), 0.55f, false);
        y += 56;

        // Сводка: оценка · размер · версия.
        AppStore.Rating rating = AppStore.rating(e.id());
        String[][] stats = {
                {rating != null && rating.count() > 0 ? String.format(Locale.ROOT, "%.1f ★", rating.average()) : "—",
                        rating != null && rating.count() > 0 ? PhoneLang.tr("store.reviews_count", rating.count()) : PhoneLang.tr("store.no_rating")},
                {e.size() > 0 ? AppStore.formatSize(e.size()) : "—", PhoneLang.tr("store.size")},
                {e.version(), PhoneLang.tr("store.version_label")}};
        PhoneDraw.card(g, 8, y, w - 8, y + 30, false);
        int cw = (w - 16) / 3;
        for (int i = 0; i < 3; i++) {
            float cx = 8 + cw * i + cw / 2f;
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(stats[i][0], cw - 6, 0.8f), cx, y + 6,
                    i == 0 && rating != null && rating.count() > 0 ? STAR : PhoneTheme.text(), 0.8f, false);
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(stats[i][1], cw - 6, 0.55f), cx, y + 19, PhoneTheme.secondaryText(), 0.55f, false);
            if (i > 0) {
                g.fill(8 + cw * i, y + 6, 9 + cw * i, y + 24, PhoneTheme.separator());
            }
        }
        y += 38;

        // Кнопки.
        String installed = AppStore.installed(e.id());
        boolean busy = AppStore.progress(e.id()) >= 0;
        if (installed != null && !busy) {
            int half = (w - 22) / 2;
            actionButton(g, e, 8, y, half, 20, mx, my, true);
            PhoneDraw.button(g, 14 + half, y, half, 20, PhoneLang.tr("store.remove"), mx, my, PhoneDraw.ButtonStyle.DANGER);
            hits.add(new Hit(14 + half, y, half, 20, () -> askRemove(e.id(), e.name())));
        } else {
            actionButton(g, e, 8, y, w - 16, 20, mx, my, true);
        }
        y += 28;

        if (!e.description().isEmpty()) {
            y = section(g, PhoneLang.tr("store.about"), y);
            y += PhoneDraw.wrapped(g, e.description(), 10, y, w - 20, PhoneTheme.text(), 0.7f, false) + 10;
        }
        y = section(g, PhoneLang.tr("store.permissions"), y);
        if (e.permissions().isEmpty()) {
            y += PhoneDraw.wrapped(g, PhoneLang.tr("store.no_permissions"), 10, y, w - 20, PhoneTheme.text(), 0.65f, false) + 10;
        } else {
            // «Таблетки» разрешений, переносятся по ширине.
            int px = 10;
            for (String perm : e.permissions()) {
                String label = PhoneLang.tr("app.perm." + perm);
                int pw = (int) PhoneDraw.width(label, 0.6f) + 12;
                if (px + pw > w - 10 && px > 10) {
                    px = 10;
                    y += 16;
                }
                PhoneDraw.rawRoundedRect(g, px, y, px + pw, y + 13, 6, PhoneTheme.accentTint(0x30));
                PhoneDraw.text(g, label, px + 6, y + 3, PhoneTheme.accent(), 0.6f, false);
                px += pw + 4;
            }
            y += 22;
        }
        if (AppStore.reviewable(e)) {
            y = renderReviews(g, e, mx, my, w, y);
        }
        y += 4;
        y += PhoneDraw.wrapped(g, PhoneLang.tr("store.source", e.catalog()), 10, y, w - 20, PhoneTheme.secondaryText(), 0.5f, false);
        double max = Math.max(0, y + (int) scroll + 12 - h);
        scroll = Math.max(0, Math.min(max, scroll));
        Scrollbar.report(0, h, scroll, max, false, v -> scroll = v);
    }

    /** Заголовок раздела страницы приложения; возвращает y под ним. */
    private static int section(GuiGraphics g, String title, int y) {
        PhoneDraw.text(g, title.toUpperCase(Locale.ROOT), 10, y, PhoneTheme.secondaryText(), 0.55f, false);
        return y + 10;
    }

    /** Оценки и отзывы: средняя и распределение, своя оценка (звёзды + текст), список отзывов. */
    private int renderReviews(GuiGraphics g, AppStore.Entry e, int mx, int my, int w, int y) {
        y = section(g, PhoneLang.tr("store.reviews"), y);
        List<AppStore.Review> reviews = AppStore.reviews(e.id());
        AppStore.Rating rating = AppStore.rating(e.id());
        int[] counts = new int[6];
        if (reviews != null) {
            for (AppStore.Review r : reviews) {
                counts[r.stars()]++;
            }
        }
        int total = reviews != null && !reviews.isEmpty() ? reviews.size() : rating != null ? rating.count() : 0;
        double avg = reviews != null && !reviews.isEmpty() ? reviews.stream().mapToInt(AppStore.Review::stars).average().orElse(0)
                : rating != null ? rating.average() : 0;
        PhoneDraw.card(g, 8, y, w - 8, y + 50, false);
        PhoneDraw.centeredText(g, total > 0 ? String.format(Locale.ROOT, "%.1f", avg) : "—", 30, y + 9, PhoneTheme.text(), 1.6f, false);
        drawStars(g, 13, y + 30, (int) Math.round(avg), 0.55f);
        PhoneDraw.centeredText(g, PhoneLang.tr("store.reviews_count", total), 30, y + 39, PhoneTheme.secondaryText(), 0.45f, false);
        int bx = 56;
        int bw = w - 16 - bx;
        for (int s = 5; s >= 1; s--) {
            int ry = y + 6 + (5 - s) * 8;
            PhoneDraw.text(g, String.valueOf(s), bx, ry, PhoneTheme.secondaryText(), 0.45f, false);
            g.fill(bx + 6, ry + 1, bx + bw, ry + 4, PhoneTheme.field());
            int max = 0;
            for (int c : counts) {
                max = Math.max(max, c);
            }
            if (counts[s] > 0) {
                g.fill(bx + 6, ry + 1, bx + 6 + Math.max(2, (bw - 6) * counts[s] / Math.max(1, max)), ry + 4, STAR);
            }
        }
        y += 56;

        // Своя оценка.
        if (!e.id().equals(reviewFor)) {
            reviewFor = e.id();
            AppStore.Review mine = AppStore.myReview(e.id());
            myStars = mine != null ? mine.stars() : 0;
            reviewInput.setText(mine != null ? mine.text() : "");
        }
        if (AppStore.installed(e.id()) == null) {
            y += PhoneDraw.wrapped(g, PhoneLang.tr("store.review_install_first"), 10, y, w - 20, PhoneTheme.secondaryText(), 0.6f, false) + 8;
        } else {
            PhoneDraw.text(g, PhoneLang.tr(AppStore.myReview(e.id()) != null ? "store.review_yours" : "store.review_rate"), 10, y,
                    PhoneTheme.text(), 0.65f, false);
            y += 11;
            for (int s = 1; s <= 5; s++) {
                int sx = 10 + (s - 1) * 18;
                boolean hover = PhoneDraw.inside(mx, my, sx, y, 16, 16);
                boolean on = s <= myStars;
                PhoneDraw.text(g, on ? "★" : "☆", sx + 2, y + 2, on || hover ? STAR : PhoneTheme.secondaryText(), 1.3f, false);
                int value = s;
                hits.add(new Hit(sx, y, 16, 16, () -> myStars = value));
            }
            y += 20;
            reviewInput.setBounds(8, y, w - 16, 32);
            reviewInput.render(g, mx, my);
            y += 36;
            boolean sending = AppStore.sendingReview(e.id());
            boolean ready = myStars > 0 && !sending;
            String label = PhoneLang.tr(sending ? "store.review_sending" : "store.review_send");
            int sw = (int) PhoneDraw.width(label, 0.7f) + 16;
            PhoneDraw.button(g, w - 8 - sw, y, sw, 16, label, mx, my, ready ? PhoneDraw.ButtonStyle.PRIMARY : PhoneDraw.ButtonStyle.NORMAL);
            if (ready) {
                hits.add(new Hit(w - 8 - sw, y, sw, 16, () -> sendReview(e)));
            }
            y += 24;
        }

        // Отзывы.
        if (reviews == null) {
            PhoneDraw.text(g, PhoneLang.tr("messenger.loading"), 10, y, PhoneTheme.secondaryText(), 0.6f, false);
            return y + 14;
        }
        if (reviews.isEmpty()) {
            PhoneDraw.text(g, PhoneLang.tr("store.no_reviews"), 10, y, PhoneTheme.secondaryText(), 0.6f, false);
            return y + 14;
        }
        java.time.format.DateTimeFormatter date = java.time.format.DateTimeFormatter.ofPattern("d.MM.yyyy");
        for (AppStore.Review r : reviews.subList(0, Math.min(30, reviews.size()))) {
            int th = r.text().isEmpty() ? 0 : PhoneDraw.wrappedHeight(r.text(), w - 32, 0.6f) + 3;
            int ch = 22 + th;
            PhoneDraw.card(g, 8, y, w - 8, y + ch, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(r.player(), w - 90, 0.65f), 14, y + 5, PhoneTheme.text(), 0.65f, false);
            String when = r.time() > 0 ? java.time.Instant.ofEpochMilli(r.time()).atZone(java.time.ZoneId.systemDefault()).format(date) : "";
            PhoneDraw.rightText(g, when, w - 14, y + 5, PhoneTheme.secondaryText(), 0.5f);
            drawStars(g, 14, y + 13, r.stars(), 0.5f);
            if (!r.version().isEmpty()) {
                PhoneDraw.text(g, "v" + r.version(), 48, y + 14, PhoneTheme.secondaryText(), 0.45f, false);
            }
            if (th > 0) {
                PhoneDraw.wrapped(g, r.text(), 14, y + 22, w - 32, PhoneTheme.text(), 0.6f, false);
            }
            y += ch + 4;
        }
        return y;
    }

    private static void drawStars(GuiGraphics g, int x, int y, int stars, float scale) {
        int step = Math.round(12 * scale) + 1;
        for (int i = 1; i <= 5; i++) {
            PhoneDraw.text(g, i <= stars ? "★" : "☆", x + (i - 1) * step, y, i <= stars ? STAR : PhoneTheme.secondaryText(), scale, false);
        }
    }

    private void sendReview(AppStore.Entry e) {
        reviewInput.setFocused(false);
        AppStore.sendReview(e, myStars, reviewInput.text().strip(), error -> toast(error == null ? PhoneLang.tr("store.review_sent")
                : error.equals("one_per_ip") ? PhoneLang.tr("store.review_one_per_ip")
                : error.equals("not_configured") ? PhoneLang.tr("store.review_off")
                : PhoneLang.tr("store.review_failed", error)));
    }

    private void askRemove(String id, String name) {
        if (phone != null) {
            phone.showDialog(PhoneDialog.confirmDelete(name, PhoneLang.tr("store.remove_confirm", name), () -> {
                AppStore.uninstall(id);
                toast(PhoneLang.tr("store.removed", name));
            }));
        }
    }

    /** Отправить своё приложение в официальный каталог (на проверку владельцу каталога). */
    private void askPublish(AppDefinition app) {
        if (phone == null) {
            return;
        }
        String name = CustomApps.name(app);
        phone.showDialog(new PhoneDialog(PhoneLang.tr("store.publish"), PhoneLang.tr("store.publish_confirm", name, app.version()),
                PhoneLang.tr("store.publish_send"), false, () -> {
                    toast(PhoneLang.tr("store.publishing", name));
                    AppStore.publish(app.id(), error -> toast(error == null ? PhoneLang.tr("store.published", name)
                            : error.equals("not_configured") ? PhoneLang.tr("store.publish_off")
                            : error.equals("downloaded") ? PhoneLang.tr("store.publish_not_author")
                            : PhoneLang.tr("store.publish_failed", error)));
                }));
    }

    /** Установленные из папки apps (в т. ч. не из магазина). */
    private void renderInstalled(GuiGraphics g, int mx, int my, int w, int h) {
        List<AppDefinition> apps = CustomApps.all();
        if (apps.isEmpty()) {
            PhoneDraw.wrapped(g, PhoneLang.tr("store.none_installed"), 12, 30, w - 24, PhoneTheme.secondaryText(), 0.7f, true);
            return;
        }
        scroll = Math.max(0, Math.min(Math.max(0, apps.size() * ROW_H - h), scroll));
        Scrollbar.report(0, h, scroll, Math.max(0, apps.size() * ROW_H - h), false, v -> scroll = v);
        g.enableScissor(0, 0, w, h);
        for (int i = 0; i < apps.size(); i++) {
            AppDefinition app = apps.get(i);
            int y = i * ROW_H - (int) scroll;
            int cy = y + 2;
            int ch = ROW_H - 4;
            PhoneDraw.card(g, 6, cy, w - 6, cy + ch, PhoneDraw.inside(mx, my, 6, cy, w - 12, ch));
            AppRegistry.Entry entry = AppRegistry.get(CustomApps.PREFIX + app.id());
            if (entry != null) {
                entry.drawIcon(g, 11, cy + (ch - 28) / 2, 28);
            }
            int by = cy + (ch - 16) / 2;
            int bx = w - 10 - 16 - 4 - 16;
            int tw = bx - 4 - 45;
            PhoneDraw.text(g, PhoneDraw.ellipsize(CustomApps.name(app), tw, 0.72f), 45, cy + 7, PhoneTheme.text(), 0.72f, false);
            Path folder = CustomApps.folder().resolve(app.id());
            PhoneDraw.text(g, PhoneDraw.ellipsize((app.author().isEmpty() ? "" : app.author() + " · ") + "v" + app.version(), tw, 0.55f),
                    45, cy + 18, PhoneTheme.secondaryText(), 0.55f, false);
            String size = AppStore.formatSize(folderSize(folder));
            if (!size.isEmpty()) {
                PhoneDraw.text(g, size, 45, cy + 27, PhoneTheme.secondaryText(), 0.5f, false);
            }
            // Нажатие на карточку — открыть; «↑» — опубликовать, «✕» — удалить.
            hits.add(new Hit(6, cy, w - 12, ch, () -> {
                if (phone != null) {
                    phone.openApp(CustomApps.PREFIX + app.id());
                }
            }));
            PhoneDraw.button(g, bx, by, 16, 16, "↑", mx, my, PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(bx, by, 16, 16, () -> askPublish(app)));
            PhoneDraw.button(g, w - 10 - 16, by, 16, 16, "✕", mx, my, PhoneDraw.ButtonStyle.DANGER);
            hits.add(new Hit(w - 10 - 16, by, 16, 16, () -> askRemove(app.id(), CustomApps.name(app))));
        }
        g.disableScissor();
    }

    /** Размер папки приложения (кэш на 10 с — считается при отрисовке). */
    private final java.util.Map<Path, long[]> sizes = new java.util.HashMap<>();

    private long folderSize(Path folder) {
        long[] cached = sizes.get(folder);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached[1] < 10_000) {
            return cached[0];
        }
        long total = 0;
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(folder)) {
            for (Path f : walk.filter(java.nio.file.Files::isRegularFile).toList()) {
                total += java.nio.file.Files.size(f);
            }
        } catch (java.io.IOException ignored) {
            // нет папки
        }
        sizes.put(folder, new long[]{total, now});
        return total;
    }

    private void renderSources(GuiGraphics g, int mx, int my, int w, int h) {
        int y = 6;
        PhoneDraw.wrapped(g, PhoneLang.tr("store.sources_hint"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.6f, false);
        y += PhoneDraw.wrappedHeight(PhoneLang.tr("store.sources_hint"), w - 16, 0.6f) + 6;
        sourceInput.setBounds(6, y, w - 40, 16);
        sourceInput.render(g, mx, my);
        PhoneDraw.button(g, w - 30, y, 24, 16, "+", mx, my, PhoneDraw.ButtonStyle.PRIMARY);
        hits.add(new Hit(w - 30, y, 24, 16, this::addSource));
        y += 24;
        PhoneDraw.card(g, 6, y, w - 6, y + 18, false);
        PhoneDraw.text(g, PhoneLang.tr("store.official"), 10, y + 6, PhoneTheme.accent(), 0.6f, false);
        y += 22;
        for (String src : List.copyOf(AppStore.sources())) {
            PhoneDraw.card(g, 6, y, w - 6, y + 18, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(src.replaceFirst("^https://", ""), w - 36, 0.6f), 10, y + 6, PhoneTheme.text(), 0.6f, false);
            PhoneDraw.button(g, w - 22, y + 2, 14, 14, "✕", mx, my, PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(w - 22, y + 2, 14, 14, () -> AppStore.removeSource(src)));
            y += 22;
        }
        for (String err : AppStore.errors()) {
            y += PhoneDraw.wrapped(g, err, 8, y, w - 16, PhoneTheme.danger(), 0.55f, false) + 4;
        }
    }

    private void addSource() {
        String url = sourceInput.text().strip();
        if (!url.startsWith("https://")) {
            toast(PhoneLang.tr("store.bad_source"));
            return;
        }
        AppStore.addSource(url);
        sourceInput.setText("");
        sourceInput.setFocused(false);
        tab = Tab.CATALOG;
    }

    // --- Ввод ---

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        // Поле ввода — только своей вкладки (иначе невидимое поле поиска «съедало» клики по кнопкам «Установленных»).
        TextArea active = detail != null ? (AppStore.installed(detail.id()) != null && AppStore.reviewable(detail) ? reviewInput : null)
                : tab == Tab.CATALOG ? search : tab == Tab.SOURCES ? sourceInput : null;
        boolean field = false;
        for (TextArea t : new TextArea[]{search, sourceInput, reviewInput}) {
            if (t == active && t.mouseClicked(x, y, button)) {
                field = true;
            } else {
                t.setFocused(false);
            }
        }
        if (field) {
            return true;
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
        scroll -= amount * 20;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (sourceInput.isFocused() && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            addSource();
            return true;
        }
        if (search.isFocused()) {
            return search.keyPressed(event);
        }
        if (reviewInput.isFocused()) {
            return reviewInput.keyPressed(event);
        }
        return sourceInput.isFocused() && sourceInput.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (search.isFocused()) {
            scroll = 0;
            return search.charTyped(event);
        }
        if (reviewInput.isFocused()) {
            return reviewInput.charTyped(event);
        }
        return sourceInput.isFocused() && sourceInput.charTyped(event);
    }

    /** Для автоснимков: открыть страницу первого приложения каталога или вкладку. */
    public void debug(String what) {
        switch (what) {
            case "detail" -> detail = AppStore.entries().isEmpty() ? null : AppStore.entries().get(0);
            case "detail:tictactoe" -> {
                detail = AppStore.entries().stream().filter(e -> e.id().equals("tictactoe")).findFirst().orElse(null);
                scroll = 0;
            }
            case "scroll" -> scroll = 170;
            case "installed" -> {
                detail = null;
                tab = Tab.INSTALLED;
                scroll = 0;
            }
            case "sources" -> tab = Tab.SOURCES;
            default -> {
            }
        }
    }
}
