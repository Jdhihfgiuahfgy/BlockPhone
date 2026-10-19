package dev.blockphone.fabric.client.phone;

import dev.blockphone.fabric.client.i18n.PhoneLang;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * Базовый класс приложения телефона.
 *
 * <p>Экземпляр живёт всю игровую сессию (см. {@link PhoneState}), поэтому состояние
 * сохраняется между открытиями телефона. Все координаты — локальные для области
 * содержимого: (0,0) — левый верх под заголовком, размер {@link #contentWidth()}×{@link #contentHeight()}.
 */
public abstract class PhoneApp {
    private final String id;
    /** Экран телефона, на котором приложение сейчас показано (null, если телефон закрыт). */
    protected PhoneScreen phone;

    protected PhoneApp(String id) {
        this.id = id;
    }

    public final String id() {
        return id;
    }

    public String title() {
        return PhoneLang.tr("app." + id);
    }

    final void attach(PhoneScreen phone) {
        this.phone = phone;
    }

    /** Вызывается при каждом показе приложения (открытие из меню или повторное открытие телефона). */
    public void onShown() {
    }

    /** Приложение скрыто: ушли на рабочий стол или закрыли телефон. */
    public void onHidden() {
    }

    public void tick() {
    }

    public abstract void render(GuiGraphics g, int mouseX, int mouseY, float delta);

    public boolean mouseClicked(double x, double y, int button) {
        return false;
    }

    public boolean mouseReleased(double x, double y, int button) {
        return false;
    }

    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return false;
    }

    public boolean mouseScrolled(double x, double y, double amount) {
        return false;
    }

    public boolean keyPressed(KeyEvent event) {
        return false;
    }

    public boolean charTyped(CharacterEvent event) {
        return false;
    }

    /** Обработать «назад» внутри приложения (например, закрыть вложенный экран). false — выйти на рабочий стол. */
    public boolean onBack() {
        return false;
    }

    /** true, пока приложение ждёт ввода текста: тогда клавиша телефона не закрывает его. */
    public boolean capturesKeyboard() {
        return false;
    }

    // --- Кнопка справа в заголовке («+», «Удалить», «Папка»...) ---

    /** Текст кнопки справа в заголовке; null — кнопки нет. */
    public String headerAction() {
        return null;
    }

    public int headerActionColor() {
        return PhoneTheme.ACCENT;
    }

    public void onHeaderAction() {
    }

    // --- Внешний вид ---

    public boolean hasHeader() {
        return true;
    }

    /** Рисовать обои вместо фона приложения. */
    public boolean transparentBackground() {
        return false;
    }

    public int backgroundColor() {
        return PhoneTheme.background();
    }

    public int headerColor() {
        return backgroundColor();
    }

    public int headerTextColor() {
        return PhoneTheme.text();
    }

    /** Светлый текст в строке состояния (для тёмного фона). */
    public boolean lightStatusBar() {
        return PhoneTheme.dark();
    }

    public final int contentWidth() {
        return PhoneLayout.APP_W;
    }

    public final int contentHeight() {
        return PhoneLayout.APP_H - (hasHeader() ? PhoneLayout.HEADER_H : 0);
    }
}
