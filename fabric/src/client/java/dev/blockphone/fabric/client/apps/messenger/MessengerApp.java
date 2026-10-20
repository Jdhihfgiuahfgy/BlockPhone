package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Серверный мессенджер: список чатов → переписка → информация о чате; новый чат и создание группы.
 * Экраны — наследники {@link MessengerView}, хранятся стеком. Данные — в {@link MessengerClient}.
 * Приложение видно только на сервере с BlockPhone.
 */
public final class MessengerApp extends PhoneApp {
    private final Deque<MessengerView> stack = new ArrayDeque<>();

    public MessengerApp() {
        super("messenger");
        stack.push(new ChatListView(this));
    }

    MessengerView view() {
        return stack.peek();
    }

    void push(MessengerView next) {
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

    /** Открыть переписку поверх списка чатов (стек: список → чат). */
    public void openChat(long chatId) {
        view().onHidden();
        while (stack.size() > 1) {
            stack.pop();
        }
        ChatView chat = new ChatView(this, chatId);
        stack.push(chat);
        chat.onShown();
    }

    /** Для автоскриншотов: прикрепить фото в открытом чате. */
    public void debugAttach(dev.blockphone.fabric.client.photo.PhotoStore.Photo photo) {
        if (view() instanceof ChatView chat) {
            chat.attachForTest(photo);
        }
    }

    /** Для автоскриншотов: «Мои стикеры». */
    public void debugStickers() {
        push(new StickersView(this));
    }

    /** Для автоскриншотов: редактор стикера с готовым фото. */
    public void debugStickerEditor(long packId, dev.blockphone.fabric.client.photo.PhotoStore.Photo photo) {
        StickerEditorView editor = new StickerEditorView(this, packId);
        push(editor);
        editor.debugPrepare(photo, dev.blockphone.fabric.client.photo.StickerMaker.Shape.CIRCLE, true, 1.4f);
    }

    /** Для автоскриншотов: нажать «Добавить» в редакторе стикера. */
    public void debugAddSticker() {
        if (view() instanceof StickerEditorView editor) {
            editor.onHeaderAction();
        }
    }

    /** Для автоскриншотов: панель стикеров в открытом чате. */
    public void debugChatStickers(boolean open, boolean sendFirst) {
        if (view() instanceof ChatView chat) {
            chat.debugStickers(open, sendFirst);
        }
    }

    /** Для автоскриншотов: отправить сообщение из поля ввода открытого чата. */
    public void debugSubmit() {
        if (view() instanceof ChatView chat) {
            chat.submit();
        }
    }

    void showDialog(PhoneDialog dialog) {
        if (phone != null) {
            phone.showDialog(dialog);
        }
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
    public int headerActionColor() {
        int color = view().headerActionColor();
        return color != 0 ? color : super.headerActionColor();
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
        MessengerClient.setOpenChat(-1);
    }

    @Override
    public void tick() {
        Long open = MessengerClient.takePendingOpen();
        if (open != null) {
            openChat(open);
        }
        view().tick();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        view().render(g, mouseX, mouseY);
        String error = MessengerClient.recentError();
        if (error != null) {
            int w = contentWidth();
            int h = PhoneDraw.wrappedHeight(error, w - 28, 0.75f) + 10;
            int y = contentHeight() - h - 40;
            PhoneDraw.roundedRect(g, 8, y, w - 8, y + h, 6, 0xE0FF3B30);
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

    /** Подпись «Загрузка...» / пустого списка по центру. */
    static void centerHint(GuiGraphics g, MessengerView view, String text, int top) {
        PhoneDraw.wrapped(g, text, 12, top, view.width() - 24, dev.blockphone.fabric.client.phone.PhoneTheme.secondaryText(),
                0.75f, true);
    }

    static String tr(String key, Object... args) {
        return PhoneLang.tr(key, args);
    }
}
