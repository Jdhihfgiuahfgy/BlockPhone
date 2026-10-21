package dev.blockphone.fabric.client.apps.store;

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
    private static final int ROW_H = 34;

    private final List<Hit> hits = new ArrayList<>();
    private final TextArea search = new TextArea(false, 0.75f);
    private final TextArea sourceInput = new TextArea(false, 0.7f);
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
            return true;
        }
        return false;
    }

    @Override
    public boolean capturesKeyboard() {
        return search.isFocused() || sourceInput.isFocused();
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
        if (PhoneDraw.inside(mx, my, 0, y, w, ROW_H) && my >= top && my < bottom) {
            g.fill(0, y, w, y + ROW_H, PhoneTheme.cardHover());
        }
        drawIcon(g, e, 6, y + 5, 24);
        String installed = AppStore.installed(e.id());
        String state = AppStore.busy(e.id()) != null ? PhoneLang.tr("store.installing")
                : installed == null ? PhoneLang.tr("store.get")
                : installed.equals(e.version()) ? PhoneLang.tr("store.open") : PhoneLang.tr("store.update");
        int bw = (int) PhoneDraw.width(state, 0.65f) + 12;
        PhoneDraw.text(g, PhoneDraw.ellipsize(e.name(), w - 40 - bw, 0.8f), 36, y + 6, PhoneTheme.text(), 0.8f, false);
        String sub = (e.author().isEmpty() ? "" : e.author() + " · ") + e.version();
        PhoneDraw.text(g, PhoneDraw.ellipsize(sub, w - 40 - bw, 0.6f), 36, y + 18, PhoneTheme.secondaryText(), 0.6f, false);
        PhoneDraw.button(g, w - 6 - bw, y + 9, bw, 15, state, mx, my,
                installed == null || !installed.equals(e.version()) ? PhoneDraw.ButtonStyle.PRIMARY : PhoneDraw.ButtonStyle.NORMAL);
        if (my >= top && my < bottom) {
            hits.add(new Hit(0, Math.max(top, y), w, ROW_H, () -> {
                detail = e;
                scroll = 0;
            }));
            hits.add(new Hit(w - 6 - bw, y + 9, bw, 15, () -> primary(e)));
        }
        g.fill(36, y + ROW_H - 1, w, y + ROW_H, PhoneTheme.separator());
    }

    private void drawIcon(GuiGraphics g, AppStore.Entry e, int x, int y, int size) {
        Path icon = AppStore.icon(e);
        PhotoTextures.Image image = icon != null ? PhotoTextures.preview(new PhotoStore.Photo(icon, 0), 64) : null;
        if (image != null) {
            PhoneDraw.textureCover(g, image.id(), x, y, size, size, image.width(), image.height());
            PhoneDraw.roundCornersMask(g, x, y, x + size, y + size, 5, PhoneTheme.background());
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
        drawIcon(g, e, 8, y, 40);
        PhoneDraw.text(g, PhoneDraw.ellipsize(e.name(), w - 60, 1f), 54, y + 4, PhoneTheme.text(), 1f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(e.author().isEmpty() ? e.catalog() : e.author(), w - 60, 0.65f), 54, y + 17,
                PhoneTheme.secondaryText(), 0.65f, false);
        PhoneDraw.text(g, PhoneLang.tr("store.version", e.version()) + (e.size() > 0 ? "  ·  " + (e.size() + 1023) / 1024 + " KB" : ""),
                54, y + 27, PhoneTheme.secondaryText(), 0.6f, false);
        y += 48;
        String installed = AppStore.installed(e.id());
        int half = (w - 20) / 2;
        String label = AppStore.busy(e.id()) != null ? PhoneLang.tr("store.installing")
                : installed == null ? PhoneLang.tr("store.get")
                : installed.equals(e.version()) ? PhoneLang.tr("store.open") : PhoneLang.tr("store.update");
        int mainW = installed != null ? half : w - 16;
        PhoneDraw.button(g, 8, y, mainW, 18, label, mx, my, PhoneDraw.ButtonStyle.PRIMARY);
        hits.add(new Hit(8, y, mainW, 18, () -> primary(e)));
        if (installed != null) {
            PhoneDraw.button(g, 12 + half, y, half, 18, PhoneLang.tr("store.remove"), mx, my, PhoneDraw.ButtonStyle.DANGER);
            hits.add(new Hit(12 + half, y, half, 18, () -> askRemove(e.id(), e.name())));
        }
        y += 26;
        PhoneDraw.text(g, PhoneLang.tr("store.permissions").toUpperCase(Locale.ROOT), 8, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 10;
        String perms = e.permissions().isEmpty() ? PhoneLang.tr("store.no_permissions")
                : String.join("\n", e.permissions().stream().map(p -> "• " + PhoneLang.tr("app.perm." + p)).toList());
        y += PhoneDraw.wrapped(g, perms, 10, y, w - 20, PhoneTheme.text(), 0.7f, false) + 8;
        if (!e.description().isEmpty()) {
            PhoneDraw.text(g, PhoneLang.tr("store.about").toUpperCase(Locale.ROOT), 8, y, PhoneTheme.secondaryText(), 0.6f, false);
            y += 10;
            y += PhoneDraw.wrapped(g, e.description(), 10, y, w - 20, PhoneTheme.text(), 0.7f, false) + 8;
        }
        PhoneDraw.wrapped(g, PhoneLang.tr("store.source", e.catalog()), 10, y, w - 20, PhoneTheme.secondaryText(), 0.55f, false);
        scroll = Math.max(0, Math.min(Math.max(0, y + (int) scroll + 30 - h), scroll));
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
        g.enableScissor(0, 0, w, h);
        for (int i = 0; i < apps.size(); i++) {
            AppDefinition app = apps.get(i);
            int y = i * ROW_H - (int) scroll;
            AppRegistry.Entry entry = AppRegistry.get(CustomApps.PREFIX + app.id());
            if (entry != null) {
                entry.drawIcon(g, 6, y + 5, 24);
            }
            PhoneDraw.text(g, PhoneDraw.ellipsize(CustomApps.name(app), w - 120, 0.8f), 36, y + 6, PhoneTheme.text(), 0.8f, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize((app.author().isEmpty() ? "" : app.author() + " · ") + app.version(), w - 120, 0.6f), 36, y + 18,
                    PhoneTheme.secondaryText(), 0.6f, false);
            int ow = (int) PhoneDraw.width(PhoneLang.tr("store.open"), 0.75f) + 10;
            PhoneDraw.button(g, w - 26 - ow, y + 9, ow, 15, PhoneLang.tr("store.open"), mx, my, PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(w - 26 - ow, y + 9, ow, 15, () -> {
                if (phone != null) {
                    phone.openApp(CustomApps.PREFIX + app.id());
                }
            }));
            int pw = 16;
            PhoneDraw.button(g, w - 44 - ow, y + 9, pw, 15, "↑", mx, my, PhoneDraw.ButtonStyle.PRIMARY);
            hits.add(new Hit(w - 44 - ow, y + 9, pw, 15, () -> askPublish(app)));
            PhoneDraw.button(g, w - 22, y + 9, 16, 15, "✕", mx, my, PhoneDraw.ButtonStyle.DANGER);
            hits.add(new Hit(w - 22, y + 9, 16, 15, () -> askRemove(app.id(), CustomApps.name(app))));
            g.fill(36, y + ROW_H - 1, w, y + ROW_H, PhoneTheme.separator());
        }
        g.disableScissor();
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
        boolean field = false;
        for (TextArea t : new TextArea[]{search, sourceInput}) {
            if (t.mouseClicked(x, y, button)) {
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
        return sourceInput.isFocused() && sourceInput.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (search.isFocused()) {
            scroll = 0;
            return search.charTyped(event);
        }
        return sourceInput.isFocused() && sourceInput.charTyped(event);
    }

    /** Для автоснимков: открыть страницу первого приложения каталога или вкладку. */
    public void debug(String what) {
        switch (what) {
            case "detail" -> detail = AppStore.entries().isEmpty() ? null : AppStore.entries().get(0);
            case "installed" -> {
                detail = null;
                tab = Tab.INSTALLED;
            }
            case "sources" -> tab = Tab.SOURCES;
            default -> {
            }
        }
    }
}
