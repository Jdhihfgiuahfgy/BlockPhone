package dev.blockphone.fabric.client.apps.messenger;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * Экран внутри приложения «Мессенджер» (список чатов, переписка, информация о чате...).
 * {@link MessengerApp} хранит их стеком: «назад» снимает верхний экран.
 */
abstract class MessengerView {
    protected final MessengerApp app;

    protected MessengerView(MessengerApp app) {
        this.app = app;
    }

    abstract String title();

    /** Нажали на название в заголовке. */
    boolean onTitleClick() {
        return false;
    }

    String headerAction() {
        return null;
    }

    int headerActionColor() {
        return 0;
    }

    void onHeaderAction() {
    }

    void onShown() {
    }

    void onHidden() {
    }

    void tick() {
    }

    abstract void render(GuiGraphics g, int mouseX, int mouseY);

    boolean mouseClicked(double x, double y, int button) {
        return false;
    }

    boolean mouseReleased(double x, double y, int button) {
        return false;
    }

    boolean mouseDragged(double x, double y, int button) {
        return false;
    }

    boolean mouseScrolled(double x, double y, double amount) {
        return false;
    }

    boolean keyPressed(KeyEvent event) {
        return false;
    }

    boolean charTyped(CharacterEvent event) {
        return false;
    }

    /** true — «назад» обработан внутри экрана (например, закрыто меню). */
    boolean onBack() {
        return false;
    }

    boolean capturesKeyboard() {
        return false;
    }

    int width() {
        return app.contentWidth();
    }

    int height() {
        return app.contentHeight();
    }
}
