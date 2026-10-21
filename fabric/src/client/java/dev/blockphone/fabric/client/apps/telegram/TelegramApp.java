package dev.blockphone.fabric.client.apps.telegram;

import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.telegram.Telegram;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Telegram: вход (ключи приложения → номер → код → пароль), список чатов, переписка, «кружочки» из кадров игры.
 * Пока клиент не готов — экран входа ({@link TgLoginView}); когда вошли — список чатов. Только Windows x64.
 */
public final class TelegramApp extends PhoneApp {
    private final Deque<TgView> stack = new ArrayDeque<>();
    private boolean ready;

    public TelegramApp() {
        super("telegram");
        stack.push(new TgLoginView(this));
    }

    TgView view() {
        return stack.peek();
    }

    void push(TgView next) {
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

    /** Открыть чат (для автоскриншотов и уведомлений). */
    public void openChat(long chatId) {
        while (stack.size() > 1) {
            stack.pop().onHidden();
        }
        push(new TgChatView(this, chatId));
    }

    /** Для автоскриншотов: "menu" — меню сообщения, "reply" — ответ и выбранные фото. */
    public void debugChat(String what) {
        if (what.equals("viewer") && view() instanceof TgChatView chat) {
            java.util.List<it.tdlight.jni.TdApi.Message> album = new java.util.ArrayList<>();
            for (it.tdlight.jni.TdApi.Message m : Telegram.messages(chat.chatId()).list) {
                if (m.mediaAlbumId == 77) {
                    album.add(m);
                }
            }
            openViewer(album, 0);
            return;
        }
        if (what.equals("profile") && view() instanceof TgChatView chat) {
            viewer.close();
            push(new TgProfileView(this, chat.chatId()));
            return;
        }
        if (what.equals("list")) {
            while (stack.size() > 1) {
                stack.pop().onHidden();
            }
            return;
        }
        if (view() instanceof TgChatView chat) {
            chat.debug(what);
        }
    }

    void showDialog(PhoneDialog dialog) {
        if (phone != null) {
            phone.showDialog(dialog);
        }
    }

    /** Корневой экран под текущее состояние входа. */
    private void syncRoot() {
        boolean nowReady = Telegram.status() == Telegram.Status.READY;
        if (nowReady != ready) {
            ready = nowReady;
            view().onHidden();
            stack.clear();
            TgView root = nowReady ? new TgChatListView(this) : new TgLoginView(this);
            stack.push(root);
            root.onShown();
        }
    }

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
        Telegram.ensureStarted();
        syncRoot();
        view().onShown();
    }

    @Override
    public void onHidden() {
        view().onHidden();
    }

    @Override
    public void tick() {
        syncRoot();
        view().tick();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        smooth(() -> {
            renderInner(g, mouseX, mouseY);
            return true;
        });
    }

    /** Гладкий шрифт — только внутри Telegram (остальной телефон — шрифтом игры). */
    private static boolean smooth(java.util.function.BooleanSupplier body) {
        boolean was = PhoneDraw.smoothText;
        PhoneDraw.smoothText = true;
        try {
            return body.getAsBoolean();
        } finally {
            PhoneDraw.smoothText = was;
        }
    }

    static final int HEADER = 24;
    final TgMediaViewer viewer = new TgMediaViewer();

    /** Открыть фото/видео/«кружочек» на весь экран (альбом — листать). */
    void openViewer(java.util.List<it.tdlight.jni.TdApi.Message> messages, int index) {
        viewer.open(messages, index);
    }

    @Override
    public boolean hasHeader() {
        return false;
    }

    @Override
    public int backgroundColor() {
        return viewer.isOpen() ? 0xFF000000 : view().background();
    }

    @Override
    public boolean lightStatusBar() {
        return viewer.isOpen() || TgTheme.dark();
    }

    @Override
    public boolean landscape() {
        return viewer.landscape();
    }

    @Override
    public boolean immersive() {
        return viewer.isOpen();
    }

    private void renderInner(GuiGraphics g, int mouseX, int mouseY) {
        if (viewer.isOpen()) {
            viewer.render(g, mouseX, mouseY, contentWidth(), contentHeight());
            return;
        }
        g.pose().pushMatrix();
        g.pose().translate(0, HEADER);
        view().renderView(g, mouseX, mouseY - HEADER);
        g.pose().popMatrix();
        renderHeader(g, mouseX, mouseY);
        String error = Telegram.recentMessage();
        if (error != null) {
            int w = contentWidth();
            int h = PhoneDraw.wrappedHeight(error, w - 28, 0.75f) + 10;
            int y = contentHeight() - h - 34;
            PhoneDraw.roundedRect(g, 8, y, w - 8, y + h, 4, 0xE0B8403A);
            PhoneDraw.wrapped(g, error, 14, y + 5, w - 28, 0xFFFFFFFF, 0.75f, true);
        }
    }

    /** Шапка как в Telegram: «‹», аватар, название и статус, кнопка справа. */
    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        g.fill(0, 0, w, HEADER, TgTheme.header());
        g.fill(0, HEADER, w, HEADER + 1, TgTheme.dark() ? 0xFF0B1118 : 0x18000000);
        boolean backHover = PhoneDraw.inside(mouseX, mouseY, 0, 0, 20, HEADER);
        int cx = 9;
        int cy = HEADER / 2;
        int arrow = backHover ? TgTheme.accent() : TgTheme.text();
        for (int i = 0; i < 5; i++) {
            g.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, arrow);
            g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, arrow);
        }
        int tx = 22;
        it.tdlight.jni.TdApi.Chat chat = view().headerChat();
        if (chat != null) {
            dev.blockphone.fabric.client.telegram.TelegramAvatars.drawRound(g, chat, tx, 3, 18, TgTheme.header());
            tx += 23;
        }
        String action = view().headerAction();
        int actionW = action != null ? 22 : 0;
        String subtitle = view().subtitle();
        String title = PhoneDraw.ellipsize(view().title(), w - tx - actionW - 4, 0.85f);
        if (subtitle == null || subtitle.isEmpty()) {
            PhoneDraw.text(g, title, tx, cy - 4, TgTheme.text(), 0.85f, false);
        } else {
            PhoneDraw.text(g, title, tx, 3.5f, TgTheme.text(), 0.85f, false);
            boolean live = chat != null && (dev.blockphone.fabric.client.telegram.Telegram.online(chat)
                    || dev.blockphone.fabric.client.telegram.Telegram.typing(chat.id) != null);
            PhoneDraw.text(g, PhoneDraw.ellipsize(subtitle, w - tx - actionW - 4, 0.6f), tx, 14, live ? TgTheme.accent() : TgTheme.secondaryText(),
                    0.6f, false);
        }
        if (action != null) {
            boolean hover = PhoneDraw.inside(mouseX, mouseY, w - actionW, 0, actionW, HEADER);
            PhoneDraw.centeredText(g, action, w - actionW / 2f, cy - 4, hover ? TgTheme.accent() : TgTheme.text(), 1f, false);
        }
    }

    @Override
    public boolean onBack() {
        if (viewer.isOpen()) {
            viewer.close();
            return true;
        }
        if (view().onBack()) {
            return true;
        }
        if (stack.size() > 1) {
            pop();
            return true;
        }
        return false;
    }

    private boolean click(double x, double y, int button) {
        if (viewer.isOpen()) {
            return viewer.mouseClicked(x, y, contentWidth(), contentHeight());
        }
        if (y < HEADER) {
            int w = contentWidth();
            if (x < 20) {
                if (!onBack() && phone != null) {
                    phone.goHome();
                }
            } else if (view().headerAction() != null && x >= w - 22) {
                view().onHeaderAction();
            } else {
                view().onTitleClick();
            }
            return true;
        }
        return view().mouseClicked(x, y - HEADER, button);
    }

    @Override
    public boolean capturesKeyboard() {
        return view().capturesKeyboard();
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return smooth(() -> click(x, y, button));
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return smooth(() -> !viewer.isOpen() && view().mouseReleased(x, y - HEADER, button));
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return smooth(() -> !viewer.isOpen() && view().mouseDragged(x, y - HEADER, button));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        return smooth(() -> !viewer.isOpen() && view().mouseScrolled(x, y - HEADER, amount));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return smooth(() -> view().keyPressed(event));
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return smooth(() -> view().charTyped(event));
    }
}
