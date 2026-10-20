package dev.blockphone.fabric.client.apps.music;

import dev.blockphone.fabric.client.music.MusicPlayer;
import dev.blockphone.fabric.client.music.Track;
import dev.blockphone.fabric.client.music.spotify.Spotify;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Музыка: вкладки «Моя музыка» (файлы из blockphone/music), «Яндекс» (Моя волна, Мне нравится, поиск) и «Spotify»
 * (пульт). Над вкладками — мини-плеер текущего трека (клик — полный плеер). Играет и с закрытым телефоном.
 */
public final class MusicApp extends PhoneApp {
    static final int TAB_H = 18;
    static final int MINI_H = 26;

    public enum Tab { LOCAL, YANDEX, SPOTIFY }

    private final Deque<MusicView> stack = new ArrayDeque<>();
    private Tab tab = Tab.LOCAL;

    public MusicApp() {
        super("music");
        stack.push(new LocalView(this));
    }

    MusicView view() {
        return stack.peek();
    }

    public void switchTab(Tab next) {
        view().onHidden();
        stack.clear();
        tab = next;
        MusicView root = switch (next) {
            case LOCAL -> new LocalView(this);
            case YANDEX -> new YandexView(this);
            case SPOTIFY -> new SpotifyView(this);
        };
        stack.push(root);
        root.onShown();
    }

    void push(MusicView next) {
        view().onHidden();
        stack.push(next);
        next.onShown();
    }

    void pop() {
        if (stack.size() > 1) {
            stack.pop().onHidden();
            view().onShown();
        }
    }

    void showDialog(dev.blockphone.fabric.client.phone.PhoneDialog dialog) {
        if (phone != null) {
            phone.showDialog(dialog);
        }
    }

    /** Открыть полный плеер (для автоскриншотов и мини-плеера). */
    public void openPlayer() {
        if (!(view() instanceof PlayerView)) {
            push(new PlayerView(this));
        }
    }

    private boolean miniVisible(MusicView view) {
        return view.root() && MusicPlayer.current() != null && tab != Tab.SPOTIFY;
    }

    int viewHeight(MusicView view) {
        if (!view.root()) {
            return contentHeight();
        }
        return contentHeight() - TAB_H - (miniVisible(view) ? MINI_H : 0);
    }

    // --- PhoneApp ---

    @Override
    public String title() {
        return view().title();
    }

    @Override
    public String headerAction() {
        return view().headerAction();
    }

    @Override
    public void onHeaderAction() {
        view().onHeaderAction();
    }

    @Override
    public void onShown() {
        view().onShown();
    }

    @Override
    public void onHidden() {
        view().onHidden();
    }

    @Override
    public void tick() {
        if (tab == Tab.SPOTIFY) {
            Spotify.watch();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        MusicView view = view();
        int vh = viewHeight(view);
        g.enableScissor(0, 0, contentWidth(), vh);
        view.renderView(g, mouseX, mouseY < vh ? mouseY : -1000);
        g.disableScissor();
        if (view.root()) {
            if (miniVisible(view)) {
                renderMini(g, mouseX, mouseY, vh);
            }
            renderTabs(g, mouseX, mouseY);
        }
        String error = MusicPlayer.recentError();
        if (error == null) {
            error = Spotify.recentError();
        }
        if (error != null) {
            int w = contentWidth();
            int h = PhoneDraw.wrappedHeight(error, w - 28, 0.75f) + 10;
            int y = vh - h - 6;
            PhoneDraw.roundedRect(g, 8, y, w - 8, y + h, 4, 0xE0B8403A);
            PhoneDraw.wrapped(g, error, 14, y + 5, w - 28, 0xFFFFFFFF, 0.75f, true);
        }
    }

    private void renderMini(GuiGraphics g, int mx, int my, int top) {
        Track track = MusicPlayer.current();
        int w = contentWidth();
        g.fill(0, top, w, top + MINI_H, PhoneTheme.card());
        g.fill(0, top, w, top + 1, PhoneTheme.separator());
        long duration = MusicPlayer.durationMs();
        if (duration > 0) {
            g.fill(0, top + 1, (int) (w * Math.min(1, MusicPlayer.positionMs() / (double) duration)), top + 2, PhoneTheme.accent());
        }
        TrackRows.cover(g, track, 6, top + 4, 18);
        PhoneDraw.text(g, PhoneDraw.ellipsize(track.title(), w - 76, 0.75f), 30, top + 6, PhoneTheme.text(), 0.75f, false);
        String sub = MusicPlayer.state() == MusicPlayer.State.LOADING ? MusicView.tr("music.loading") : track.artist();
        PhoneDraw.text(g, PhoneDraw.ellipsize(sub, w - 76, 0.6f), 30, top + 15, PhoneTheme.secondaryText(), 0.6f, false);
        boolean playing = MusicPlayer.state() == MusicPlayer.State.PLAYING || MusicPlayer.state() == MusicPlayer.State.LOADING;
        miniButton(g, playing ? MusicView.PAUSE : MusicView.PLAY, w - 38, top + 13, mx, my);
        miniButton(g, MusicView.NEXT, w - 16, top + 13, mx, my);
    }

    private static void miniButton(GuiGraphics g, net.minecraft.resources.Identifier icon, int cx, int cy, int mx, int my) {
        if (PhoneDraw.inside(mx, my, cx - 9, cy - 9, 18, 18)) {
            PhoneDraw.roundedRect(g, cx - 9, cy - 9, cx + 9, cy + 9, 9, PhoneTheme.cardHover());
        }
        PhoneDraw.tinted(g, icon, cx - 6, cy - 6, 12, 12, 32, 32, PhoneTheme.text());
    }

    private void renderTabs(GuiGraphics g, int mx, int my) {
        int top = contentHeight() - TAB_H;
        int w = contentWidth();
        g.fill(0, top, w, contentHeight(), PhoneTheme.header());
        g.fill(0, top, w, top + 1, PhoneTheme.separator());
        Tab[] tabs = Tab.values();
        int tw = w / tabs.length;
        for (int i = 0; i < tabs.length; i++) {
            int x = i * tw;
            boolean selected = tabs[i] == tab;
            boolean hovered = PhoneDraw.inside(mx, my, x, top, tw, TAB_H);
            if (selected) {
                g.fill(x + 6, top + 1, x + tw - 6, top + 2, PhoneTheme.accent());
            }
            int color = selected ? PhoneTheme.accent() : hovered ? PhoneTheme.text() : PhoneTheme.secondaryText();
            String label = MusicView.tr("music.tab." + tabs[i].name().toLowerCase());
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(label, tw - 4, 0.65f), x + tw / 2f, top + 7, color, 0.65f, false);
        }
    }

    @Override
    public boolean onBack() {
        if (view().onBack()) {
            return true;
        }
        if (stack.size() > 1) {
            pop();
            return true;
        }
        return false;
    }

    @Override
    public boolean capturesKeyboard() {
        return view().capturesKeyboard();
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        MusicView view = view();
        int w = contentWidth();
        if (view.root() && y >= contentHeight() - TAB_H) {
            int index = (int) (x / (w / Tab.values().length));
            if (index >= 0 && index < Tab.values().length && Tab.values()[index] != tab) {
                switchTab(Tab.values()[index]);
            }
            return true;
        }
        int vh = viewHeight(view);
        if (miniVisible(view) && y >= vh && y < vh + MINI_H) {
            if (x >= w - 48 && x < w - 28) {
                MusicPlayer.toggle();
            } else if (x >= w - 26) {
                MusicPlayer.next();
            } else {
                openPlayer();
            }
            return true;
        }
        return view.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return view().mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return view().mouseDragged(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        return view().mouseScrolled(x, y, amount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return view().keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return view().charTyped(event);
    }
}
