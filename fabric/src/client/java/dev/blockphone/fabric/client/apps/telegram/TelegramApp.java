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
        view().renderView(g, mouseX, mouseY);
        String error = Telegram.recentMessage();
        if (error != null) {
            int w = contentWidth();
            int h = PhoneDraw.wrappedHeight(error, w - 28, 0.75f) + 10;
            int y = contentHeight() - h - 34;
            PhoneDraw.roundedRect(g, 8, y, w - 8, y + h, 4, 0xE0B8403A);
            PhoneDraw.wrapped(g, error, 14, y + 5, w - 28, 0xFFFFFFFF, 0.75f, true);
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
        return view().mouseClicked(x, y, button);
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
