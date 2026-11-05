package dev.blockphone.fabric.client.apps.screens;

import dev.blockphone.fabric.client.browser.WebEngine;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.screens.ScreenData;
import dev.blockphone.fabric.client.screens.ScreenPicker;
import dev.blockphone.fabric.client.screens.ScreenStore;
import dev.blockphone.fabric.client.screens.Screens;
import dev.blockphone.fabric.client.ui.Scrollbar;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * «Экраны»: свои экраны и колонки (создать прицелом, ссылка, пауза и перемотка, звук «в мире» или «с телефона», радиус,
 * громкость, колонки) и экраны других игроков рядом (подключиться телефоном — слушать). Сами экраны — {@code screens/}.
 */
public final class ScreensApp extends PhoneApp {
    private static final int M = 8;
    private static final int ROW = 18;

    /** Открыта вкладка «Приложение» у экрана (пока приложение не выбрано). */
    private boolean appTab;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    /** Ползунок: где нарисован, пределы и что менять при перетаскивании. */
    private record Slider(int x, int y, int w, int min, int max, IntConsumer set) {
        void apply(double mx) {
            double t = Math.max(0, Math.min(1, (mx - x) / (double) w));
            set.accept((int) Math.round(min + t * (max - min)));
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final List<Slider> sliders = new ArrayList<>();
    private @Nullable Slider dragging;
    private final TextArea name = new TextArea(false, 0.7f);
    private final TextArea link = new TextArea(false, 0.65f);
    /** Открытый экран: свой (id) или чужой (ключ). */
    private @Nullable String openId;
    private @Nullable String openRemote;
    /** Открытая колонка (id); openId при этом — экран, с которого пришли (назад — к нему). */
    private @Nullable String openSpeaker;
    /** Открытая группа (id). */
    private @Nullable String openGroup;
    private double scroll;
    private double maxScroll;

    public ScreensApp() {
        super("screens");
        name.setMaxLength(ScreenData.MAX_NAME);
        name.setPlaceholder(PhoneLang.tr("screens.name"));
        link.setMaxLength(ScreenData.MAX_URL);
        link.setPlaceholder(PhoneLang.tr("screens.link_hint"));
    }

    @Override
    public void onShown() {
        String done = ScreenPicker.takeDone();
        if (done != null && done.startsWith(ScreenPicker.SPEAKER_PREFIX)) {
            openSpeaker(done.substring(ScreenPicker.SPEAKER_PREFIX.length()));
        } else if (done != null) {
            open(done);
        }
    }

    @Override
    public String title() {
        ScreenData.Group grp = group();
        if (grp != null) {
            return grp.name.isEmpty() ? PhoneLang.tr(grp.screens() ? "screens.group_screens" : "screens.group_speakers") : grp.name;
        }
        ScreenData.Speaker sp = speaker();
        if (sp != null) {
            return sp.name.isEmpty() ? PhoneLang.tr("screens.speaker") : sp.name;
        }
        ScreenData.Screen s = own();
        if (s != null) {
            return s.name.isEmpty() ? PhoneLang.tr("app.screens") : s.name;
        }
        return super.title();
    }

    /** Страница экрана открыта в телефоне для ручного управления (принять куки, нажать «Смотреть», войти). */
    private boolean pageMode;
    private double pageX;
    private double pageY;
    private double pageScale = 1;

    @Override
    public boolean capturesKeyboard() {
        return name.isFocused() || link.isFocused() || pageMode;
    }

    @Override
    public boolean landscape() {
        return pageMode;
    }

    /** Свой экран, страницей которого сейчас управляют (для записи действий). */
    private @Nullable Screens.Live pageLive() {
        ScreenData.Screen s = own();
        return s == null ? null : live(Minecraft.getInstance().getUser().getName().toLowerCase(java.util.Locale.ROOT) + "/" + s.id);
    }

    /** Записать действие на странице — оно повторится у всех игроков. */
    private void recordPage(char kind, double x, double y, int c) {
        Screens.Live l = pageLive();
        if (l != null && Screens.record(l, kind, x, y, c)) {
            changedScreen(l.def());
        }
    }

    private @Nullable dev.blockphone.fabric.client.browser.WebEngine.View pageView() {
        ScreenData.Screen s = own();
        if (s == null) {
            return null;
        }
        Screens.Live l = live(Minecraft.getInstance().getUser().getName().toLowerCase(java.util.Locale.ROOT) + "/" + s.id);
        return l == null ? null : Screens.view(l);
    }

    /** Страница экрана во весь телефон (набок): нажатия и клавиши идут на неё. */
    private void renderPage(GuiGraphics g, int mx, int my, int w, int h) {
        var view = pageView();
        if (view == null) {
            PhoneDraw.wrapped(g, PhoneLang.tr("screens.page_not_playing"), M, 10, w - 2 * M, PhoneTheme.secondaryText(), 0.65f, false);
            return;
        }
        int pw = view.pageWidth();
        int ph = view.pageHeight();
        pageScale = Math.min(w / (double) pw, h / (double) ph);
        double dw = pw * pageScale;
        double dh = ph * pageScale;
        pageX = (w - dw) / 2;
        pageY = (h - dh) / 2;
        g.fill(0, 0, w, h, 0xFF000000);
        view.draw(g, (float) pageX, (float) pageY, (float) dw, (float) dh, pw, ph);
        if (PhoneDraw.inside(mx, my, pageX, pageY, dw, dh)) {
            view.mouseMove(pageCoordX(mx), pageCoordY(my));
        }
    }

    private int pageCoordX(double x) {
        return (int) Math.round((x - pageX) / pageScale);
    }

    private int pageCoordY(double y) {
        return (int) Math.round((y - pageY) / pageScale);
    }

    @Override
    public boolean onBack() {
        if (pageMode) {
            pageMode = false;
            var view = pageView();
            if (view != null) {
                view.setFocus(false);
            }
            return true;
        }
        if (openGroup != null && openSpeaker == null && openId == null) {
            saveName();
            openGroup = null;
            scroll = 0;
            return true;
        }
        if (openSpeaker != null) {
            saveName();
            openSpeaker = null;
            scroll = 0;
            ScreenData.Screen s = own();
            if (s != null) {
                name.setText(s.name);
            }
            return true;
        }
        if (openId != null || openRemote != null) {
            saveName();
            openId = null;
            openRemote = null;
            scroll = 0;
            return true;
        }
        return false;
    }

    /** Для DevShots. */
    public void debugOpen(String id) {
        open(id);
    }

    /** Для DevShots. */
    public void debugPage() {
        pageMode = true;
    }

    /** Для DevShots. */
    public void debugOpenGroup(String id) {
        openGroup(id);
    }

    /** Для DevShots. */
    public void debugOpenSpeaker(String id) {
        openSpeaker(id);
    }

    private void open(String id) {
        appTab = false;
        ScreenData.Screen s = ScreenStore.screen(id);
        if (s == null) {
            return;
        }
        openId = id;
        openRemote = null;
        scroll = 0;
        name.setText(s.name);
        link.setText(s.url);
    }

    private void openSpeaker(String id) {
        ScreenData.Speaker sp = speakerById(id);
        if (sp == null) {
            return;
        }
        saveName();
        openSpeaker = id;
        openRemote = null;
        scroll = 0;
        name.setText(sp.name);
    }

    private static @Nullable ScreenData.Speaker speakerById(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (ScreenData.Speaker sp : ScreenStore.data().speakers) {
            if (sp.id.equals(id)) {
                return sp;
            }
        }
        return null;
    }

    private @Nullable ScreenData.Speaker speaker() {
        return speakerById(openSpeaker);
    }

    private @Nullable ScreenData.Group group() {
        return openGroup == null ? null : ScreenStore.data().group(openGroup);
    }

    private void openGroup(String id) {
        ScreenData.Group grp = ScreenStore.data().group(id);
        if (grp == null) {
            return;
        }
        saveName();
        openGroup = id;
        openId = null;
        openSpeaker = null;
        openRemote = null;
        scroll = 0;
        name.setText(grp.name);
    }

    /** Экран изменился: общее — и на остальные экраны его объединённой группы. */
    private static void changedScreen(ScreenData.Screen s) {
        ScreenStore.data().propagate(s);
        ScreenStore.changed();
    }

    private static void newGroup(boolean screens) {
        ScreenData.Set data = ScreenStore.data();
        if (data.groups.size() >= ScreenData.MAX_GROUPS) {
            return;
        }
        ScreenData.Group grp = new ScreenData.Group();
        grp.id = ScreenStore.newId();
        grp.kind = screens ? "screens" : "speakers";
        long count = data.groups.stream().filter(x -> x.screens() == screens).count();
        grp.name = PhoneLang.tr(screens ? "screens.group_screens_n" : "screens.group_speakers_n", count + 1);
        data.groups.add(grp);
        ScreenStore.changed();
    }

    /** Подпись источника колонки: «Музыка телефона» или название экрана. */
    private static String sourceName(String source) {
        if (source.equals(ScreenData.PHONE)) {
            return PhoneLang.tr("screens.source_phone");
        }
        if (source.startsWith(ScreenData.GROUP_PREFIX)) {
            ScreenData.Group grp = ScreenStore.data().group(source.substring(ScreenData.GROUP_PREFIX.length()));
            return grp == null ? PhoneLang.tr("screens.source_none")
                    : PhoneLang.tr("screens.source_group", grp.name.isEmpty() ? PhoneLang.tr("screens.group_screens") : grp.name);
        }
        ScreenData.Screen s = ScreenStore.screen(source);
        return s == null ? PhoneLang.tr("screens.source_none") : s.name.isEmpty() ? PhoneLang.tr("app.screens") : s.name;
    }

    private @Nullable ScreenData.Screen own() {
        return openId == null ? null : ScreenStore.screen(openId);
    }

    private @Nullable Screens.Live live(String key) {
        for (Screens.Live l : Screens.all()) {
            if (l.key().equals(key)) {
                return l;
            }
        }
        return null;
    }

    // --- Отрисовка ---

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        hits.clear();
        sliders.clear();
        int w = contentWidth();
        int h = contentHeight();
        if (openId != null && own() == null) {
            openId = null; // удалён
        }
        if (pageMode && openId != null) {
            name.setBounds(-1000, -1000, 10, 10);
            link.setBounds(-1000, -1000, 10, 10);
            renderPage(g, mouseX, mouseY, w, h);
            return;
        }
        pageMode = false;
        if (openSpeaker != null && speaker() == null) {
            openSpeaker = null;
        }
        // Поля ввода — только на своих страницах (иначе невидимое поле ловило бы нажатия).
        name.setBounds(-1000, -1000, 10, 10);
        link.setBounds(-1000, -1000, 10, 10);
        int y = 6 - (int) Math.round(scroll);
        int end;
        if (!Screens.available()) {
            // 1.21.9: экраны не нарисовать, но колонки телефона (только звук) работают.
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.unavailable"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.6f, false) + 8;
            end = renderPhone(g, mouseX, mouseY, w, y);
            end = renderAllSpeakers(g, mouseX, mouseY, w, end + 6);
        } else if (openSpeaker != null) {
            end = renderSpeaker(g, mouseX, mouseY, w, y, speaker());
        } else if (openGroup != null && group() != null && openId == null) {
            end = renderGroup(g, mouseX, mouseY, w, y, group());
        } else if (openId != null) {
            end = renderOwn(g, mouseX, mouseY, w, y, own());
        } else if (openRemote != null) {
            Screens.Live l = live(openRemote);
            if (l == null) {
                openRemote = null;
                return;
            }
            end = renderRemote(g, mouseX, mouseY, w, y, l);
        } else {
            end = renderList(g, mouseX, mouseY, w, y);
        }
        maxScroll = Math.max(0, end + scroll - h + 6);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        Scrollbar.report(0, h, scroll, maxScroll, v -> scroll = v);
    }

    private int renderList(GuiGraphics g, int mx, int my, int w, int y) {
        Minecraft mc = Minecraft.getInstance();
        y = browserState(g, w, y);
        PhoneDraw.button(g, M, y, (w - 2 * M - 4) / 2, ROW, "+ " + PhoneLang.tr("screens.new"), mx, my, PhoneDraw.ButtonStyle.PRIMARY);
        hits.add(new Hit(M, y, (w - 2 * M - 4) / 2, ROW, () -> ScreenPicker.start(ScreenPicker.Mode.SCREEN, "")));
        int hx = M + (w - 2 * M - 4) / 2 + 4;
        PhoneDraw.button(g, hx, y, w - M - hx, ROW, PhoneLang.tr("screens.help"), mx, my, PhoneDraw.ButtonStyle.NORMAL);
        hits.add(new Hit(hx, y, w - M - hx, ROW, () -> phone.showDialog(new PhoneDialog(PhoneLang.tr("screens.help"),
                PhoneLang.tr("screens.help_text"), PhoneLang.tr("screens.got_it"), false, () -> {
        }))));
        y += ROW + 8;
        y = quickControls(g, mx, my, w, y);
        section(g, PhoneLang.tr("screens.mine"), y);
        y += 12;
        List<Screens.Live> mine = new ArrayList<>();
        List<Screens.Live> others = new ArrayList<>();
        for (Screens.Live l : Screens.all()) {
            (l.mine() ? mine : others).add(l);
        }
        // Свои — и в других измерениях (Screens.all — только это измерение).
        for (ScreenData.Screen s : ScreenStore.data().screens) {
            if (mine.stream().noneMatch(l -> l.def() == s)) {
                mine.add(new Screens.Live(s, mc.getUser().getName(), true, 0, List.of(), ScreenStore.data()));
            }
        }
        if (mine.isEmpty()) {
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.none_mine"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.6f, false) + 6;
        }
        for (Screens.Live l : mine) {
            y = card(g, mx, my, w, y, l, () -> open(l.def().id));
        }
        y += 6;
        y = renderGroups(g, mx, my, w, y, true) + 6;
        y = renderAllSpeakers(g, mx, my, w, y) + 6;
        y = renderGroups(g, mx, my, w, y, false) + 6;
        y = renderPhone(g, mx, my, w, y) + 6;
        section(g, PhoneLang.tr("screens.nearby"), y);
        y += 12;
        Vec3 eye = mc.player != null ? mc.player.getEyePosition() : Vec3.ZERO;
        others.sort(Comparator.comparingDouble(l -> l.def().center().distanceTo(eye)));
        if (!PhoneSettings.get().screensOthers) {
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.others_hidden"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.6f, false) + 6;
        } else if (others.isEmpty()) {
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.none_nearby"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.6f, false) + 6;
        }
        for (Screens.Live l : others) {
            y = card(g, mx, my, w, y, l, () -> {
                openRemote = l.key();
                openId = null;
                scroll = 0;
            });
        }
        y += 6;
        section(g, PhoneLang.tr("screens.settings"), y);
        y += 12;
        PhoneSettings s = PhoneSettings.get();
        y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.show_others"), s.screensOthers, () -> {
            s.screensOthers = !s.screensOthers;
            s.save();
        });
        y = slider(g, mx, my, w, y, PhoneLang.tr("screens.others_volume"), s.screensOthersVolume + "%", 0, 100, s.screensOthersVolume, v -> {
            s.screensOthersVolume = v;
            s.save();
        });
        return y;
    }

    /** Состояние Chromium (скачивание при первом запуске). */
    private int browserState(GuiGraphics g, int w, int y) {
        WebEngine.State st = WebEngine.state();
        String text = switch (st) {
            case DOWNLOADING, EXTRACTING -> PhoneLang.tr("screens.browser_download",
                    WebEngine.progress() < 0 ? "…" : Math.round(WebEngine.progress() * 100) + "%");
            case STARTING -> PhoneLang.tr("screens.browser_start");
            case FAILED -> PhoneLang.tr("screens.browser_failed", WebEngine.error());
            default -> null;
        };
        if (text == null) {
            return y;
        }
        int end = PhoneDraw.wrapped(g, text, M, y, w - 2 * M, st == WebEngine.State.FAILED ? PhoneTheme.danger() : PhoneTheme.secondaryText(),
                0.6f, false);
        return y + end + 6;
    }

    private int card(GuiGraphics g, int mx, int my, int w, int y, Screens.Live l, Runnable action) {
        ScreenData.Screen s = l.def();
        int h = 30;
        boolean hover = PhoneDraw.inside(mx, my, M, y, w - 2 * M, h);
        PhoneDraw.card(g, M, y, w - M, y + h, hover);
        int dot = !s.on ? PhoneTheme.secondaryText() : s.url.isEmpty() ? PhoneTheme.border() : s.paused ? 0xFFF2C14E : PhoneTheme.online();
        PhoneDraw.rawRoundedRect(g, M + 6, y + 7, M + 11, y + 12, 2, dot);
        String title = s.name.isEmpty() ? PhoneLang.tr("app.screens") : s.name;
        PhoneDraw.text(g, PhoneDraw.ellipsize(title, w - 2 * M - 60, 0.7f), M + 15, y + 6, PhoneTheme.text(), 0.7f, false);
        String size = s.widthBlocks() + "×" + s.heightBlocks();
        PhoneDraw.rightText(g, size, w - M - 6, y + 6, PhoneTheme.secondaryText(), 0.6f);
        String sub = l.mine() ? (!s.app.isEmpty() ? (s.appTitle.isEmpty() ? s.app : s.appTitle)
                : s.mirror ? PhoneLang.tr("screens.content_phone") : s.url.isEmpty() ? PhoneLang.tr("screens.no_link") : host(s.url))
                : PhoneLang.tr("screens.owner", l.owner()) + (Screens.listening(l) ? " · " + PhoneLang.tr("screens.listening") : "");
        if (!s.on) {
            sub = PhoneLang.tr("screens.off") + " · " + sub;
        }
        PhoneDraw.text(g, PhoneDraw.ellipsize(sub, w - 2 * M - 14, 0.55f), M + 6, y + 18, PhoneTheme.secondaryText(), 0.55f, false);
        hits.add(new Hit(M, y, w - 2 * M, h, action));
        return y + h + 4;
    }

    private static String host(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? url : host.replaceFirst("^www\\.", "");
        } catch (RuntimeException e) {
            return url;
        }
    }

    private int renderOwn(GuiGraphics g, int mx, int my, int w, int y, ScreenData.Screen s) {
        long now = System.currentTimeMillis();
        Screens.Live live = live(Minecraft.getInstance().getUser().getName().toLowerCase(java.util.Locale.ROOT) + "/" + s.id);
        // Название и ссылка.
        name.setBounds(M, y, w - 2 * M, 16);
        name.render(g, mx, my);
        y += 20;
        ScreenData.Group syncGroup = ScreenStore.data().syncGroup(s);
        if (syncGroup != null) {
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.in_sync_group", syncGroup.name), M, y, w - 2 * M, PhoneTheme.accent(), 0.55f, false) + 4;
        }
        // Что показывать: ссылку или экран своего телефона.
        int third = (w - 2 * M - 8) / 3;
        boolean gameMode = !s.app.isEmpty() || appTab;
        String[] modes = {"screens.content_link", "screens.content_phone", "screens.content_app"};
        for (int i = 0; i < 3; i++) {
            int mode = i;
            int x = M + i * (third + 4);
            boolean sel = mode == 2 ? gameMode : !gameMode && s.mirror == (mode == 1);
            PhoneDraw.button(g, x, y, third, 16, PhoneLang.tr(modes[i]), mx, my, sel ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(x, y, third, 16, () -> {
                appTab = mode == 2;
                if (mode == 2) {
                    return; // приложение выбирается ниже
                }
                s.mirror = mode == 1;
                s.app = "";
                s.appTitle = "";
                s.on = true;
                changedScreen(s);
            }));
        }
        y += 20;
        if (gameMode) {
            link.setBounds(-1000, -1000, 10, 10);
            link.setFocused(false);
            return renderAppMode(g, mx, my, w, y, s);
        }
        if (s.mirror) {
            link.setBounds(-1000, -1000, 10, 10); // поле спрятано — не ловить нажатия
            link.setFocused(false);
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.mirror_note"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.55f, false) + 6;
            y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.power"), s.on, () -> {
                s.on = !s.on;
                changedScreen(s);
            });
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.mirror_sound"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.5f, false) + 10;
            return deleteButton(g, mx, my, w, y, s);
        }
        int bw = 38;
        link.setBounds(M, y, w - 2 * M - bw - 4, 16);
        link.render(g, mx, my);
        PhoneDraw.button(g, w - M - bw, y, bw, 16, PhoneLang.tr("screens.play_link"), mx, my, PhoneDraw.ButtonStyle.PRIMARY);
        hits.add(new Hit(w - M - bw, y, bw, 16, this::applyLink));
        y += 20;
        y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.link_note"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.5f, false) + 6;
        // Сайт не подстроился сам (куки, кнопка «Смотреть», вход) — открыть его страницу в телефоне и нажать самому.
        y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.fit"), s.fit, () -> {
            s.fit = !s.fit;
            changedScreen(s);
        });
        if (!s.url.isEmpty()) {
            PhoneDraw.button(g, M, y, w - 2 * M, ROW, PhoneLang.tr("screens.page_control"), mx, my, PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(M, y, w - 2 * M, ROW, () -> {
                saveName();
                link.setFocused(false);
                pageMode = true;
                var view = pageView();
                if (view != null) {
                    view.setFocus(true);
                }
            }));
            y += ROW + 3;
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.page_note"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.5f, false) + 4;
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.actions_note", s.actions.size()), M, y, w - 2 * M, PhoneTheme.accent(), 0.5f,
                    false) + 4;
            if (!s.actions.isEmpty()) {
                PhoneDraw.button(g, M, y, w - 2 * M, 16, PhoneLang.tr("screens.actions_reset"), mx, my, PhoneDraw.ButtonStyle.NORMAL);
                hits.add(new Hit(M, y, w - 2 * M, 16, () -> {
                    s.actions.clear();
                    s.rev++;
                    changedScreen(s);
                }));
                y += 19;
            }
            y += 4;
        }
        // Пульт: −10 с, пауза, +10 с, время и полоса.
        double[] page = live != null ? Screens.pageTime(live) : new double[]{-1, -1};
        long posMs = s.positionAt(now);
        long durMs = page[1] > 0 ? Math.round(page[1] * 1000) : -1;
        if (durMs > 0 && !s.loop) {
            posMs = Math.min(posMs, durMs);
        } else if (durMs > 0) {
            posMs = posMs % durMs;
        }
        int cw = (w - 2 * M - 8) / 3;
        String[] labels = {"« 10", s.paused ? "▶" : "❚❚", "10 »"};
        Runnable[] actions = {() -> seek(s, -10_000, durMs), () -> togglePause(s, durMs), () -> seek(s, 10_000, durMs)};
        for (int i = 0; i < 3; i++) {
            int x = M + i * (cw + 4);
            PhoneDraw.button(g, x, y, cw, ROW, labels[i], mx, my, i == 1 ? PhoneDraw.ButtonStyle.PRIMARY : PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(x, y, cw, ROW, actions[i]));
        }
        y += ROW + 5;
        String time = clock(posMs) + (durMs > 0 ? " / " + clock(durMs) : page[1] == -1 && page[0] >= 0 ? " · " + PhoneLang.tr("screens.live") : "");
        PhoneDraw.text(g, time, M, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 9;
        if (durMs > 0) {
            int bx = M;
            int bwid = w - 2 * M;
            PhoneDraw.rawRoundedRect(g, bx, y + 2, bx + bwid, y + 5, 1, PhoneTheme.field());
            int filled = (int) Math.round(bwid * Math.min(1, posMs / (double) durMs));
            PhoneDraw.rawRoundedRect(g, bx, y + 2, bx + filled, y + 5, 1, PhoneTheme.accent());
            long dur = durMs;
            sliders.add(new Slider(bx, y - 2, bwid, 0, 1000, v -> {
                s.setPosition(dur * v / 1000, System.currentTimeMillis());
                changedScreen(s);
            }));
            y += 12;
        } else {
            y += 3;
        }
        // Настройки экрана.
        y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.power"), s.on, () -> {
            s.on = !s.on;
            changedScreen(s);
        });
        y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.loop"), s.loop, () -> {
            s.loop = !s.loop;
            changedScreen(s);
        });
        // Звук: в мире (колонки / сам экран) или только у подключившихся телефоном.
        PhoneDraw.text(g, PhoneLang.tr("screens.sound"), M, y + 2, PhoneTheme.secondaryText(), 0.6f, false);
        y += 11;
        int half = (w - 2 * M - 4) / 2;
        for (int i = 0; i < 2; i++) {
            boolean phoneMode = i == 1;
            int x = M + i * (half + 4);
            boolean on = s.phone == phoneMode;
            PhoneDraw.button(g, x, y, half, 16, PhoneLang.tr(phoneMode ? "screens.sound_phone" : "screens.sound_world"), mx, my,
                    on ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(x, y, half, 16, () -> {
                s.phone = phoneMode;
                changedScreen(s);
            }));
        }
        y += 20;
        y += PhoneDraw.wrapped(g, PhoneLang.tr(s.phone ? "screens.sound_phone_note" : "screens.sound_world_note"), M, y, w - 2 * M,
                PhoneTheme.secondaryText(), 0.5f, false) + 4;
        if (!s.phone) {
            y = slider(g, mx, my, w, y, PhoneLang.tr("screens.radius"), PhoneLang.tr("screens.blocks", s.radius), ScreenData.MIN_RADIUS,
                    ScreenData.MAX_RADIUS, s.radius, v -> {
                        s.radius = v;
                        changedScreen(s);
                    });
        }
        y = slider(g, mx, my, w, y, PhoneLang.tr("screens.volume"), s.volume + "%", 0, 100, s.volume, v -> {
            s.volume = v;
            changedScreen(s);
        });
        if (live != null) {
            boolean listen = Screens.listening(live);
            y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.listen"), listen, () -> Screens.setListening(live, !listen));
        }
        // Колонки.
        y += 4;
        List<ScreenData.Speaker> speakers = new ArrayList<>();
        for (ScreenData.Speaker sp : ScreenStore.data().speakers) {
            if (sp.screen.equals(s.id)) {
                speakers.add(sp);
            }
        }
        section(g, PhoneLang.tr("screens.speakers", speakers.size()), y);
        y += 12;
        if (speakers.isEmpty()) {
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.no_speakers"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.55f, false) + 4;
        }
        y = speakerRows(g, mx, my, w, y, speakers, s.id, null);
        return deleteButton(g, mx, my, w, y + 10, s);
    }

    /** Приложение на экране («режим экрана»): что сейчас показано, приложения с этим режимом, как играть. */
    private int renderAppMode(GuiGraphics g, int mx, int my, int w, int y, ScreenData.Screen s) {
        Minecraft mc = Minecraft.getInstance();
        String key = dev.blockphone.fabric.client.screens.AppDisplays.key(mc.getUser().getName(), s.id);
        if (!s.app.isEmpty()) {
            section(g, PhoneLang.tr("screens.app_now"), y);
            y += 12;
            PhoneDraw.text(g, s.appTitle.isEmpty() ? s.app : s.appTitle, M, y, PhoneTheme.text(), 0.8f, false);
            y += 12;
            int half = (w - 2 * M - 4) / 2;
            PhoneDraw.button(g, M, y, half, ROW, PhoneLang.tr("screens.app_open"), mx, my, PhoneDraw.ButtonStyle.PRIMARY);
            hits.add(new Hit(M, y, half, ROW, () -> {
                saveName();
                dev.blockphone.fabric.client.screens.AppDisplays.invite(s.app, key);
                phone.openApp(dev.blockphone.fabric.client.apps.custom.CustomApps.PREFIX + s.app);
            }));
            PhoneDraw.button(g, M + half + 4, y, w - 2 * M - half - 4, ROW, PhoneLang.tr("screens.app_remove"), mx, my, PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(M + half + 4, y, w - 2 * M - half - 4, ROW, () -> {
                s.app = "";
                s.appTitle = "";
                appTab = true;
                changedScreen(s);
            }));
            y += ROW + 8;
        }
        y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.app_note", dev.blockphone.fabric.client.screens.AppDisplays.keyName()), M, y,
                w - 2 * M, PhoneTheme.secondaryText(), 0.55f, false) + 6;
        section(g, PhoneLang.tr("screens.app_list"), y);
        y += 12;
        List<dev.blockphone.common.apps.AppDefinition> apps = new ArrayList<>();
        for (var app : dev.blockphone.fabric.client.apps.custom.CustomApps.all()) {
            if (app.script() != null && app.has(dev.blockphone.common.apps.AppPermission.DISPLAY)) {
                apps.add(app);
            }
        }
        if (apps.isEmpty()) {
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.app_none"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.55f, false) + 4;
            PhoneDraw.button(g, M, y, w - 2 * M, ROW, PhoneLang.tr("screens.app_store"), mx, my, PhoneDraw.ButtonStyle.PRIMARY);
            hits.add(new Hit(M, y, w - 2 * M, ROW, () -> phone.openApp("store")));
            y += ROW + 4;
        }
        for (var app : apps) {
            boolean on = app.id().equals(s.app);
            PhoneDraw.button(g, M, y, w - 2 * M, 18, PhoneDraw.ellipsize((on ? "\u2714 " : "\u25B6 ")
                    + dev.blockphone.fabric.client.apps.custom.CustomApps.name(app), w - 2 * M - 8, 0.7f), mx, my,
                    on ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(M, y, w - 2 * M, 18, () -> {
                // Приложение само решает, что показать (лобби): ему — приглашение на этот экран (onDisplayInvite).
                saveName();
                dev.blockphone.fabric.client.screens.AppDisplays.invite(app.id(), key);
                phone.openApp(dev.blockphone.fabric.client.apps.custom.CustomApps.PREFIX + app.id());
            }));
            y += 21;
        }
        y += 6;
        y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.power"), s.on, () -> {
            s.on = !s.on;
            changedScreen(s);
        });
        return deleteButton(g, mx, my, w, y + 6, s);
    }

    private int deleteButton(GuiGraphics g, int mx, int my, int w, int y, ScreenData.Screen s) {
        PhoneDraw.button(g, M, y, w - 2 * M, ROW, PhoneLang.tr("screens.delete"), mx, my, PhoneDraw.ButtonStyle.DANGER);
        hits.add(new Hit(M, y, w - 2 * M, ROW, () -> phone.showDialog(PhoneDialog.confirmDelete(PhoneLang.tr("screens.delete_title"),
                PhoneLang.tr("screens.delete_text"), () -> {
                    ScreenStore.remove(s);
                    openId = null;
                }))));
        return y + ROW;
    }

    /** Колонки телефона: музыка телефона для всех рядом с ними. */
    private int renderPhone(GuiGraphics g, int mx, int my, int w, int y) {
        ScreenData.PhoneOut out = ScreenStore.data().phone;
        List<ScreenData.Speaker> speakers = new ArrayList<>();
        for (ScreenData.Speaker sp : ScreenStore.data().speakers) {
            if (sp.screen.equals(ScreenData.PHONE)) {
                speakers.add(sp);
            }
        }
        section(g, PhoneLang.tr("screens.phone_speakers", speakers.size()), y);
        y += 12;
        if (speakers.isEmpty()) {
            return y + PhoneDraw.wrapped(g, PhoneLang.tr("screens.phone_none"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.55f, false);
        }
        y += PhoneDraw.wrapped(g, PhoneLang.tr(dev.blockphone.fabric.client.screens.PhoneSpeakers.sending()
                ? "screens.phone_note_on" : "screens.phone_note"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.55f, false) + 4;
        {
            y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.phone_on"), out.on, () -> {
                out.on = !out.on;
                ScreenStore.changed();
            });
            y = slider(g, mx, my, w, y, PhoneLang.tr("screens.radius"), PhoneLang.tr("screens.blocks", out.radius), ScreenData.MIN_RADIUS,
                    ScreenData.MAX_RADIUS, out.radius, v -> {
                        out.radius = v;
                        ScreenStore.changed();
                    });
            y = slider(g, mx, my, w, y, PhoneLang.tr("screens.volume"), out.volume + "%", 0, 100, out.volume, v -> {
                out.volume = v;
                ScreenStore.changed();
            });
            y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.phone_local"), out.local, () -> {
                out.local = !out.local;
                ScreenStore.changed();
            });
        }
        return y;
    }

    /** Все свои колонки: имя, источник, вкл/выкл; нажатие — страница колонки. */
    private int renderAllSpeakers(GuiGraphics g, int mx, int my, int w, int y) {
        List<ScreenData.Speaker> all = ScreenStore.data().speakers;
        List<ScreenData.Speaker> loose = new ArrayList<>();
        for (ScreenData.Speaker sp : all) {
            if (ScreenStore.data().groups.stream().noneMatch(grp -> !grp.screens() && grp.members.contains(sp.id))) {
                loose.add(sp);
            }
        }
        section(g, PhoneLang.tr(loose.size() == all.size() ? "screens.all_speakers" : "screens.loose_speakers", loose.size()), y);
        y += 12;
        if (!all.isEmpty()) {
            y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.show_speakers"), Screens.showSpeakers(),
                    () -> Screens.setShowSpeakers(!Screens.showSpeakers()));
        }
        if (all.isEmpty()) {
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.no_speakers_all"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.55f, false) + 4;
        }
        return speakerRows(g, mx, my, w, y, loose, ScreenData.PHONE, null);
    }

    /** Одним нажатием: все экраны вкл/выкл, все колонки вкл/выкл, удалить все колонки. */
    private int quickControls(GuiGraphics g, int mx, int my, int w, int y) {
        ScreenData.Set data = ScreenStore.data();
        if (data.screens.isEmpty() && data.speakers.isEmpty()) {
            return y;
        }
        int half = (w - 2 * M - 4) / 2;
        boolean anyScreen = data.screens.stream().anyMatch(s -> s.on);
        boolean anySpeaker = data.speakers.stream().anyMatch(sp -> sp.on);
        int screensW = data.speakers.isEmpty() ? w - 2 * M : half;
        if (!data.screens.isEmpty()) {
            PhoneDraw.button(g, M, y, screensW, ROW, PhoneLang.tr(anyScreen ? "screens.all_screens_off" : "screens.all_screens_on"), mx, my,
                    anyScreen ? PhoneDraw.ButtonStyle.NORMAL : PhoneDraw.ButtonStyle.PRIMARY);
            hits.add(new Hit(M, y, screensW, ROW, () -> {
                long now = System.currentTimeMillis();
                for (ScreenData.Screen s : data.screens) {
                    if (s.on == anyScreen) {
                        s.on = !anyScreen;
                        if (s.on) {
                            s.paused = false;
                            s.at = now;
                        }
                    }
                }
                ScreenStore.changed();
            }));
        }
        if (!data.speakers.isEmpty()) {
            int sx = data.screens.isEmpty() ? M : M + half + 4;
            int sw = data.screens.isEmpty() ? w - 2 * M : half;
            PhoneDraw.button(g, sx, y, sw, ROW, PhoneLang.tr(anySpeaker ? "screens.all_speakers_off" : "screens.all_speakers_on"), mx, my,
                    anySpeaker ? PhoneDraw.ButtonStyle.NORMAL : PhoneDraw.ButtonStyle.PRIMARY);
            hits.add(new Hit(sx, y, sw, ROW, () -> {
                for (ScreenData.Speaker sp : data.speakers) {
                    sp.on = !anySpeaker;
                }
                ScreenStore.changed();
            }));
            y += ROW + 4;
            PhoneDraw.button(g, M, y, w - 2 * M, 16, PhoneLang.tr("screens.delete_all_speakers", data.speakers.size()), mx, my,
                    PhoneDraw.ButtonStyle.DANGER);
            hits.add(new Hit(M, y, w - 2 * M, 16, () -> phone.showDialog(PhoneDialog.confirmDelete(
                    PhoneLang.tr("screens.delete_all_speakers_title"), PhoneLang.tr("screens.delete_all_speakers_text", data.speakers.size()), () -> {
                        for (ScreenData.Speaker sp : List.copyOf(data.speakers)) {
                            data.forget(sp.id);
                        }
                        data.speakers.clear();
                        data.groups.removeIf(grp -> !grp.screens() && grp.members.isEmpty());
                        ScreenStore.changed();
                    }))));
            y += 16;
        } else {
            y += ROW;
        }
        return y + 8;
    }

    /** Страница колонки: имя, откуда звук, вкл/выкл, громкость, переставить, удалить. */
    private int renderSpeaker(GuiGraphics g, int mx, int my, int w, int y, ScreenData.Speaker sp) {
        name.setBounds(M, y, w - 2 * M, 16);
        name.render(g, mx, my);
        y += 20;
        PhoneDraw.text(g, sp.x + ", " + sp.y + ", " + sp.z, M, y, PhoneTheme.secondaryText(), 0.55f, false);
        y += 10;
        y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.speaker_on"), sp.on, () -> {
            sp.on = !sp.on;
            ScreenStore.changed();
        });
        y = slider(g, mx, my, w, y, PhoneLang.tr("screens.speaker_volume"), sp.volume + "%", 0, 100, sp.volume, v -> {
            sp.volume = v;
            ScreenStore.changed();
        });
        y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.speaker_pulse"), sp.pulse, () -> {
            sp.pulse = !sp.pulse;
            ScreenStore.changed();
        });
        // Канал дорожки: всё в моно или своя часть (левый/правый, центр, тыловые, сабвуфер) — объёмный звук.
        section(g, PhoneLang.tr("screens.speaker_channel"), y);
        y += 12;
        int ch = sp.screen.equals(ScreenData.PHONE) ? dev.blockphone.fabric.client.screens.PhoneSpeakers.localChannels()
                : Screens.channels(sp.screen);
        String[][] rows = {{"MIX"}, {"L", "R"}, {"SL", "SR"}, {"C", "LFE"}};
        int half = (w - 2 * M - 4) / 2;
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                String pick = row[i];
                int bx = row.length == 1 ? M : M + i * (half + 4);
                int bw = row.length == 1 ? w - 2 * M : half;
                boolean on = dev.blockphone.fabric.client.screens.ScreenAudio.Pick.of(sp.channel).name().equals(pick);
                PhoneDraw.button(g, bx, y, bw, 16, PhoneLang.tr("screens.channel." + pick.toLowerCase(java.util.Locale.ROOT)), mx, my,
                        on ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
                hits.add(new Hit(bx, y, bw, 16, () -> {
                    sp.channel = pick;
                    ScreenStore.changed();
                }));
            }
            y += 19;
        }
        String now = ch >= 6 ? PhoneLang.tr("screens.track_surround") : ch > 0 ? PhoneLang.tr("screens.track_stereo") : "";
        if (!now.isEmpty()) {
            PhoneDraw.text(g, now, M, y, PhoneTheme.accent(), 0.55f, false);
            y += 9;
        }
        y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.channel_note"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.5f, false) + 8;
        // Откуда звук: музыка телефона или любой свой экран.
        section(g, PhoneLang.tr("screens.speaker_source"), y);
        y += 12;
        List<String> sources = sources();
        for (String source : sources) {
            boolean on = sp.screen.equals(source);
            PhoneDraw.button(g, M, y, w - 2 * M, 16, PhoneDraw.ellipsize(sourceName(source), w - 2 * M - 8, 0.65f), mx, my,
                    on ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(M, y, w - 2 * M, 16, () -> {
                sp.screen = source;
                ScreenStore.changed();
            }));
            y += 19;
        }
        y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.speaker_source_note"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.5f, false) + 8;
        PhoneDraw.button(g, M, y, w - 2 * M, ROW, PhoneLang.tr("screens.speaker_move"), mx, my, PhoneDraw.ButtonStyle.NORMAL);
        hits.add(new Hit(M, y, w - 2 * M, ROW, () -> {
            saveName();
            ScreenPicker.startMove(sp);
        }));
        y += ROW + 4;
        PhoneDraw.button(g, M, y, w - 2 * M, ROW, PhoneLang.tr("screens.speaker_delete"), mx, my, PhoneDraw.ButtonStyle.DANGER);
        hits.add(new Hit(M, y, w - 2 * M, ROW, () -> {
            ScreenStore.data().speakers.remove(sp);
            ScreenStore.data().forget(sp.id);
            ScreenStore.changed();
            openSpeaker = null;
        }));
        return y + ROW;
    }

    /** Откуда может звучать колонка: музыка телефона, свои экраны, объединённые группы экранов. */
    private static List<String> sources() {
        List<String> out = new ArrayList<>();
        out.add(ScreenData.PHONE);
        for (ScreenData.Screen s : ScreenStore.data().screens) {
            out.add(s.id);
        }
        for (ScreenData.Group grp : ScreenStore.data().groups) {
            if (grp.screens() && grp.sync) {
                out.add(ScreenData.GROUP_PREFIX + grp.id);
            }
        }
        return out;
    }

    /** Группы экранов или колонок в списке и кнопка «+ Группа». */
    private int renderGroups(GuiGraphics g, int mx, int my, int w, int y, boolean screens) {
        List<ScreenData.Group> list = ScreenStore.data().groups.stream().filter(x -> x.screens() == screens).toList();
        section(g, PhoneLang.tr(screens ? "screens.groups_screens" : "screens.groups_speakers", list.size()), y);
        y += 12;
        for (ScreenData.Group grp : list) {
            boolean hover = PhoneDraw.inside(mx, my, M, y, w - 2 * M, 24);
            PhoneDraw.card(g, M, y, w - M, y + 24, hover);
            PhoneDraw.rawRoundedRect(g, M + 6, y + 6, M + 11, y + 11, 2, grp.on ? PhoneTheme.online() : PhoneTheme.secondaryText());
            String title = grp.name.isEmpty() ? PhoneLang.tr(screens ? "screens.group_screens" : "screens.group_speakers") : grp.name;
            PhoneDraw.text(g, PhoneDraw.ellipsize(title, w - 2 * M - 50, 0.65f), M + 15, y + 5, PhoneTheme.text(), 0.65f, false);
            PhoneDraw.rightText(g, grp.volume + "%", w - M - 6, y + 5, PhoneTheme.secondaryText(), 0.55f);
            String sub = PhoneLang.tr("screens.group_members", grp.members.size()) + (grp.sync ? " · " + PhoneLang.tr("screens.group_synced") : "");
            PhoneDraw.text(g, PhoneDraw.ellipsize(sub, w - 2 * M - 14, 0.5f), M + 6, y + 15, PhoneTheme.secondaryText(), 0.5f, false);
            hits.add(new Hit(M, y, w - 2 * M, 24, () -> openGroup(grp.id)));
            y += 27;
        }
        PhoneDraw.button(g, M, y, w - 2 * M, 16, "+ " + PhoneLang.tr(screens ? "screens.new_group_screens" : "screens.new_group_speakers"),
                mx, my, PhoneDraw.ButtonStyle.NORMAL);
        hits.add(new Hit(M, y, w - 2 * M, 16, () -> newGroup(screens)));
        return y + 19;
    }

    /** Страница группы: имя, вкл/выкл всех, общая громкость, объединение экранов, состав, источник для всех колонок. */
    private int renderGroup(GuiGraphics g, int mx, int my, int w, int y, ScreenData.Group grp) {
        ScreenData.Set data = ScreenStore.data();
        name.setBounds(M, y, w - 2 * M, 16);
        name.render(g, mx, my);
        y += 20;
        y = toggle(g, mx, my, w, y, PhoneLang.tr(grp.screens() ? "screens.group_on_screens" : "screens.group_on_speakers"), grp.on, () -> {
            grp.on = !grp.on;
            ScreenStore.changed();
        });
        y = slider(g, mx, my, w, y, PhoneLang.tr("screens.group_volume"), grp.volume + "%", 0, 100, grp.volume, v -> {
            grp.volume = v;
            ScreenStore.changed();
        });
        if (grp.screens()) {
            y = toggle(g, mx, my, w, y, PhoneLang.tr("screens.group_sync"), grp.sync, () -> {
                grp.sync = !grp.sync;
                if (grp.sync) {
                    // Объединили — всем экранам содержимое первого.
                    for (String id : grp.members) {
                        ScreenData.Screen first = data.screen(id);
                        if (first != null) {
                            data.propagate(first);
                            break;
                        }
                    }
                }
                ScreenStore.changed();
            });
            y += PhoneDraw.wrapped(g, PhoneLang.tr("screens.group_sync_note"), M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.5f, false) + 4;
            if (grp.sync && !grp.members.isEmpty()) {
                String lead = grp.members.get(0);
                PhoneDraw.button(g, M, y, w - 2 * M, ROW, PhoneLang.tr("screens.group_control"), mx, my, PhoneDraw.ButtonStyle.PRIMARY);
                hits.add(new Hit(M, y, w - 2 * M, ROW, () -> {
                    saveName();
                    open(lead);
                }));
                y += ROW + 4;
            }
        } else {
            // Откуда звук у всех колонок группы — одним нажатием.
            section(g, PhoneLang.tr("screens.group_source"), y);
            y += 12;
            for (String source : sources()) {
                boolean all = !grp.members.isEmpty();
                for (String id : grp.members) {
                    ScreenData.Speaker sp = speakerById(id);
                    all &= sp != null && sp.screen.equals(source);
                }
                PhoneDraw.button(g, M, y, w - 2 * M, 16, PhoneDraw.ellipsize(sourceName(source), w - 2 * M - 8, 0.65f), mx, my,
                        all ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
                hits.add(new Hit(M, y, w - 2 * M, 16, () -> {
                    for (String id : grp.members) {
                        ScreenData.Speaker sp = speakerById(id);
                        if (sp != null) {
                            sp.screen = source;
                        }
                    }
                    ScreenStore.changed();
                }));
                y += 19;
            }
            y += 4;
            List<ScreenData.Speaker> members = new ArrayList<>();
            for (String id : grp.members) {
                ScreenData.Speaker sp = speakerById(id);
                if (sp != null) {
                    members.add(sp);
                }
            }
            section(g, PhoneLang.tr("screens.group_speakers_title", members.size()), y);
            y += 12;
            String src = members.isEmpty() ? ScreenData.PHONE : members.get(0).screen;
            y = speakerRows(g, mx, my, w, y, members, src, grp.id) + 4;
        }
        // Состав: все свои экраны или колонки, нажатие — добавить/убрать.
        section(g, PhoneLang.tr(grp.screens() ? "screens.group_members_title" : "screens.group_members_pick"), y);
        y += 12;
        List<String[]> items = new ArrayList<>();
        if (grp.screens()) {
            for (ScreenData.Screen s : data.screens) {
                items.add(new String[]{s.id, s.name.isEmpty() ? PhoneLang.tr("app.screens") : s.name});
            }
        } else {
            for (ScreenData.Speaker sp : data.speakers) {
                items.add(new String[]{sp.id, (sp.name.isEmpty() ? PhoneLang.tr("screens.speaker") : sp.name) + " · " + sourceName(sp.screen)});
            }
        }
        if (items.isEmpty()) {
            y += PhoneDraw.wrapped(g, PhoneLang.tr(grp.screens() ? "screens.none_mine" : "screens.no_speakers_all"), M, y, w - 2 * M,
                    PhoneTheme.secondaryText(), 0.55f, false) + 4;
        }
        for (String[] item : items) {
            String id = item[0];
            boolean in = grp.members.contains(id);
            PhoneDraw.button(g, M, y, w - 2 * M, 16, PhoneDraw.ellipsize((in ? "✔ " : "+ ") + item[1], w - 2 * M - 8, 0.6f), mx, my,
                    in ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(M, y, w - 2 * M, 16, () -> {
                if (grp.members.contains(id)) {
                    grp.members.remove(id);
                } else if (grp.members.size() < ScreenData.MAX_GROUP_MEMBERS) {
                    if (grp.screens() && grp.sync) {
                        // Новый экран объединённой группы — показывает то же, что остальные.
                        ScreenData.Screen lead = grp.members.isEmpty() ? null : data.screen(grp.members.get(0));
                        grp.members.add(id);
                        if (lead != null) {
                            data.propagate(lead);
                        }
                    } else {
                        grp.members.add(id);
                    }
                }
                ScreenStore.changed();
            }));
            y += 19;
        }
        y += 8;
        PhoneDraw.button(g, M, y, w - 2 * M, ROW, PhoneLang.tr("screens.group_delete"), mx, my, PhoneDraw.ButtonStyle.DANGER);
        long count = grp.members.stream().filter(id -> speakerById(id) != null).count();
        hits.add(new Hit(M, y, w - 2 * M, ROW, () -> {
            if (grp.screens() || count == 0) {
                phone.showDialog(PhoneDialog.confirmDelete(PhoneLang.tr("screens.group_delete"),
                        PhoneLang.tr(grp.screens() ? "screens.group_delete_screens_text" : "screens.group_delete_empty_text"), () -> {
                            data.groups.remove(grp);
                            ScreenStore.changed();
                            openGroup = null;
                        }));
            } else {
                phone.showDialog(PhoneDialog.confirmDelete(PhoneLang.tr("screens.group_delete"),
                        PhoneLang.tr("screens.group_delete_speakers_text", count), () -> {
                            for (String id : List.copyOf(grp.members)) {
                                data.speakers.removeIf(sp -> sp.id.equals(id));
                                data.forget(id);
                            }
                            data.groups.remove(grp);
                            ScreenStore.changed();
                            openGroup = null;
                        }));
            }
        }));
        y += ROW + 4;
        if (!grp.screens() && count > 0) {
            // Только распустить: колонки остаются.
            PhoneDraw.button(g, M, y, w - 2 * M, 16, PhoneLang.tr("screens.group_ungroup"), mx, my, PhoneDraw.ButtonStyle.NORMAL);
            hits.add(new Hit(M, y, w - 2 * M, 16, () -> {
                data.groups.remove(grp);
                ScreenStore.changed();
                openGroup = null;
            }));
            y += 16;
        }
        return y;
    }

    /** Строки колонок с «×» и кнопка «+ Колонка» (выбор блока прицелом). */
    private int speakerRows(GuiGraphics g, int mx, int my, int w, int y, List<ScreenData.Speaker> speakers, String source,
                            @Nullable String groupId) {
        for (ScreenData.Speaker sp : speakers) {
            boolean hover = PhoneDraw.inside(mx, my, M, y, w - 2 * M, 24);
            PhoneDraw.card(g, M, y, w - M, y + 24, hover);
            PhoneDraw.rawRoundedRect(g, M + 6, y + 6, M + 11, y + 11, 2, sp.on ? PhoneTheme.online() : PhoneTheme.secondaryText());
            String title = sp.name.isEmpty() ? PhoneLang.tr("screens.speaker") : sp.name;
            PhoneDraw.text(g, PhoneDraw.ellipsize(title, w - 2 * M - 50, 0.65f), M + 15, y + 5, PhoneTheme.text(), 0.65f, false);
            PhoneDraw.rightText(g, sp.volume + "%", w - M - 6, y + 5, PhoneTheme.secondaryText(), 0.55f);
            String channel = sp.channel == null || sp.channel.equalsIgnoreCase("mix") ? ""
                    : " · " + PhoneLang.tr("screens.channel." + sp.channel.toLowerCase(java.util.Locale.ROOT));
            PhoneDraw.text(g, PhoneDraw.ellipsize("♪ " + sourceName(sp.screen) + channel, w - 2 * M - 14, 0.5f), M + 6, y + 15,
                    PhoneTheme.secondaryText(), 0.5f, false);
            hits.add(new Hit(M, y, w - 2 * M, 24, () -> openSpeaker(sp.id)));
            y += 27;
        }
        PhoneDraw.button(g, M, y, w - 2 * M, ROW, "+ " + PhoneLang.tr("screens.add_speaker"), mx, my, PhoneDraw.ButtonStyle.NORMAL);
        hits.add(new Hit(M, y, w - 2 * M, ROW, () -> {
            saveName();
            if (groupId != null) {
                ScreenPicker.startForGroup(source, groupId);
            } else {
                ScreenPicker.start(ScreenPicker.Mode.SPEAKER, source);
            }
        }));
        return y + ROW + 3;
    }

    private int renderRemote(GuiGraphics g, int mx, int my, int w, int y, Screens.Live l) {
        ScreenData.Screen s = l.def();
        PhoneDraw.text(g, PhoneDraw.ellipsize(s.name.isEmpty() ? PhoneLang.tr("app.screens") : s.name, w - 2 * M, 0.8f), M, y,
                PhoneTheme.text(), 0.8f, false);
        y += 12;
        PhoneDraw.text(g, PhoneLang.tr("screens.owner", l.owner()), M, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 10;
        if (!s.url.isEmpty()) {
            y += PhoneDraw.wrapped(g, s.url, M, y, w - 2 * M, PhoneTheme.secondaryText(), 0.5f, false) + 6;
        }
        double[] page = Screens.pageTime(l);
        if (page[0] >= 0) {
            PhoneDraw.text(g, clock(Math.round(page[0] * 1000)) + (page[1] > 0 ? " / " + clock(Math.round(page[1] * 1000)) : ""), M, y,
                    PhoneTheme.secondaryText(), 0.6f, false);
            y += 12;
        }
        boolean listen = Screens.listening(l);
        PhoneDraw.button(g, M, y, w - 2 * M, ROW, PhoneLang.tr(listen ? "screens.stop_listen" : "screens.listen"), mx, my,
                listen ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.PRIMARY);
        hits.add(new Hit(M, y, w - 2 * M, ROW, () -> Screens.setListening(l, !listen)));
        y += ROW + 4;
        return y + PhoneDraw.wrapped(g, PhoneLang.tr(s.phone ? "screens.remote_phone" : "screens.remote_world"), M, y, w - 2 * M,
                PhoneTheme.secondaryText(), 0.55f, false);
    }

    // --- Элементы ---

    private void section(GuiGraphics g, String title, int y) {
        PhoneDraw.text(g, title.toUpperCase(dev.blockphone.fabric.client.phone.PhoneClock.locale()), M + 2, y + 2, PhoneTheme.secondaryText(), 0.6f, false);
    }

    private int toggle(GuiGraphics g, int mx, int my, int w, int y, String label, boolean on, Runnable action) {
        boolean hover = PhoneDraw.inside(mx, my, M, y, w - 2 * M, ROW);
        PhoneDraw.card(g, M, y, w - M, y + ROW, hover);
        PhoneDraw.text(g, PhoneDraw.ellipsize(label, w - 2 * M - 36, 0.65f), M + 6, y + 6, PhoneTheme.text(), 0.65f, false);
        int tx = w - M - 24;
        PhoneDraw.rawRoundedRect(g, tx, y + 5, tx + 18, y + 13, 4, on ? PhoneTheme.accent() : PhoneTheme.field());
        int knob = on ? tx + 11 : tx + 1;
        PhoneDraw.rawRoundedRect(g, knob, y + 6, knob + 6, y + 12, 3, on ? PhoneTheme.onAccent() : PhoneTheme.secondaryText());
        hits.add(new Hit(M, y, w - 2 * M, ROW, action));
        return y + ROW + 3;
    }

    private int slider(GuiGraphics g, int mx, int my, int w, int y, String label, String value, int min, int max, int current, IntConsumer set) {
        PhoneDraw.card(g, M, y, w - M, y + 26, false);
        PhoneDraw.text(g, label, M + 6, y + 4, PhoneTheme.text(), 0.65f, false);
        PhoneDraw.rightText(g, value, w - M - 6, y + 4, PhoneTheme.secondaryText(), 0.6f);
        int bx = M + 6;
        int bw = w - 2 * M - 12;
        int by = y + 17;
        PhoneDraw.rawRoundedRect(g, bx, by, bx + bw, by + 3, 1, PhoneTheme.field());
        int filled = (int) Math.round(bw * (current - min) / (double) Math.max(1, max - min));
        PhoneDraw.rawRoundedRect(g, bx, by, bx + filled, by + 3, 1, PhoneTheme.accent());
        PhoneDraw.rawRoundedRect(g, bx + filled - 3, by - 2, bx + filled + 3, by + 5, 3, PhoneTheme.accent());
        sliders.add(new Slider(bx, y + 12, bw, min, max, set));
        return y + 29;
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    // --- Действия ---

    private void applyLink() {
        ScreenData.Screen s = own();
        if (s == null) {
            return;
        }
        String url = link.text().strip();
        if (!url.isEmpty() && !url.matches("(?i)^https?://.*")) {
            url = "https://" + url;
        }
        if (!url.isEmpty() && WebEngine.safeUrl(url).equals("about:blank")) {
            return;
        }
        link.setText(url);
        link.setFocused(false);
        if (!url.equals(s.url)) {
            s.actions.clear(); // действия — для прежней страницы
        }
        s.url = url;
        s.rev++; // та же ссылка тоже открывается заново (сайт мог уйти на другое видео)
        s.on = true;
        s.paused = false;
        s.setPosition(0, System.currentTimeMillis());
        saveName();
        changedScreen(s);
    }

    private void togglePause(ScreenData.Screen s, long durMs) {
        long now = System.currentTimeMillis();
        long pos = s.positionAt(now);
        if (durMs > 0) {
            pos = s.loop ? pos % durMs : Math.min(pos, durMs);
        }
        s.paused = !s.paused;
        s.setPosition(pos, now);
        changedScreen(s);
    }

    private void seek(ScreenData.Screen s, long delta, long durMs) {
        long now = System.currentTimeMillis();
        long pos = s.positionAt(now);
        if (durMs > 0) {
            pos = s.loop ? pos % durMs : Math.min(pos, durMs);
        }
        pos = Math.max(0, pos + delta);
        if (durMs > 0) {
            pos = Math.min(pos, durMs);
        }
        s.setPosition(pos, now);
        changedScreen(s);
    }

    private void saveName() {
        ScreenData.Group grp = openSpeaker == null && openId == null ? group() : null;
        if (grp != null) {
            if (!name.text().strip().equals(grp.name)) {
                grp.name = name.text().strip();
                ScreenStore.changed();
            }
            name.setFocused(false);
            return;
        }
        ScreenData.Speaker sp = speaker();
        if (sp != null) {
            if (!name.text().strip().equals(sp.name)) {
                sp.name = name.text().strip();
                ScreenStore.changed();
            }
            name.setFocused(false);
            return;
        }
        ScreenData.Screen s = own();
        if (s != null && !name.text().strip().equals(s.name)) {
            s.name = name.text().strip();
            ScreenStore.changed();
        }
        name.setFocused(false);
    }


    // --- Ввод ---

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (pageMode) {
            var view = pageView();
            if (view != null) {
                view.setFocus(true);
                view.mouseButton(pageCoordX(x), pageCoordY(y), button, true);
                recordPage('d', pageCoordX(x), pageCoordY(y), button);
            }
            return true;
        }
        if (name.mouseClicked(x, y, button)) {
            link.setFocused(false);
            return true;
        }
        if (link.mouseClicked(x, y, button)) {
            saveName();
            return true;
        }
        if (name.isFocused()) {
            saveName();
        }
        link.setFocused(false);
        for (Slider s : sliders) {
            if (PhoneDraw.inside(x, y, s.x() - 3, s.y(), s.w() + 6, 12)) {
                dragging = s;
                s.apply(x);
                return true;
            }
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
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        if (pageMode) {
            var view = pageView();
            if (view != null) {
                view.mouseMove(pageCoordX(x), pageCoordY(y));
            }
            return true;
        }
        if (dragging != null) {
            dragging.apply(x);
            return true;
        }
        return name.mouseDragged(x, y) || link.mouseDragged(x, y);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (pageMode) {
            var view = pageView();
            if (view != null) {
                view.mouseButton(pageCoordX(x), pageCoordY(y), button, false);
                recordPage('u', pageCoordX(x), pageCoordY(y), button);
            }
            return true;
        }
        dragging = null;
        name.mouseReleased();
        link.mouseReleased();
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (pageMode) {
            var view = pageView();
            if (view != null) {
                view.wheel(pageCoordX(x), pageCoordY(y), amount);
                recordPage('w', pageCoordX(x), pageCoordY(y), (int) Math.round(amount * 100));
            }
            return true;
        }
        scroll = Math.max(0, Math.min(maxScroll, scroll - amount * 16));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (pageMode) {
            var view = pageView();
            if (view != null && !event.isEscape()) {
                view.key(event.key(), event.scancode(), event.modifiers());
                recordPage('k', event.key(), event.scancode(), event.modifiers());
                return true;
            }
            return false;
        }
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && (link.isFocused() || name.isFocused())) {
            if (link.isFocused()) {
                applyLink();
            } else {
                saveName();
            }
            return true;
        }
        if (name.isFocused()) {
            return name.keyPressed(event);
        }
        return link.isFocused() && link.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (pageMode) {
            var view = pageView();
            if (view != null) {
                view.typeChar(event.codepoint());
            }
            return true;
        }
        if (name.isFocused()) {
            return name.charTyped(event);
        }
        return link.isFocused() && link.charTyped(event);
    }
}
