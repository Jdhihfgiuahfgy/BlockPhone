package dev.blockphone.fabric.client.apps.telegram;

import dev.blockphone.fabric.client.music.Accounts;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.telegram.Telegram;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;

/**
 * Вход в Telegram по шагам: ключи приложения (api_id, api_hash с my.telegram.org) → номер телефона → код из
 * Telegram → облачный пароль (если включён). Всё вводит сам игрок; ключи и сессия хранятся только у него.
 */
final class TgLoginView extends TgView {
    private static final int BLUE = 0xFF2AABEE;

    private final TextArea apiId = new TextArea(false, 0.75f);
    private final TextArea apiHash = new TextArea(false, 0.7f);
    private final TextArea phone = new TextArea(false, 0.85f);
    private final TextArea code = new TextArea(false, 0.85f);
    private final TextArea password = new TextArea(false, 0.85f);
    private boolean editKeys;

    TgLoginView(TelegramApp app) {
        super(app);
        apiId.setMaxLength(12);
        apiHash.setMaxLength(64);
        phone.setMaxLength(20);
        code.setMaxLength(10);
        password.setMaxLength(128);
        password.setMasked(true);
        Accounts a = Accounts.get();
        if (a.telegramApiId > 0) {
            apiId.setText(String.valueOf(a.telegramApiId));
        }
        apiHash.setText(a.telegramApiHash);
    }

    @Override
    String title() {
        return "Telegram";
    }

    private TextArea field() {
        if (editKeys || Telegram.status() == Telegram.Status.NOT_CONFIGURED) {
            return apiHash.isFocused() ? apiHash : apiId;
        }
        return switch (Telegram.status()) {
            case WAIT_PHONE -> phone;
            case WAIT_CODE -> code;
            case WAIT_PASSWORD -> password;
            default -> null;
        };
    }

    @Override
    void onShown() {
        TextArea f = field();
        if (f != null) {
            f.setFocused(true);
        }
    }

    @Override
    void onHidden() {
        for (TextArea t : new TextArea[]{apiId, apiHash, phone, code, password}) {
            t.setFocused(false);
        }
    }

    @Override
    boolean capturesKeyboard() {
        for (TextArea t : new TextArea[]{apiId, apiHash, phone, code, password}) {
            if (t.isFocused()) {
                return true;
            }
        }
        return false;
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        // Логотип: синий круг с «самолётиком».
        PhoneDraw.roundedRect(g, w / 2 - 16, 10, w / 2 + 16, 42, 16, BLUE);
        PhoneDraw.centeredText(g, "➤", w / 2f, 19, 0xFFFFFFFF, 1.6f, false);
        int y = 50;
        Telegram.Status status = Telegram.status();
        if (editKeys || status == Telegram.Status.NOT_CONFIGURED) {
            renderKeys(g, mouseX, mouseY, y);
            return;
        }
        switch (status) {
            case UNSUPPORTED -> hint(g, tr("telegram.unsupported"), y + 10);
            case ERROR -> {
                int h = PhoneDraw.wrapped(g, tr("telegram.native_error"), 12, y + 10, w - 24, PhoneTheme.danger(), 0.75f, true);
                String fatal = Telegram.fatal();
                if (fatal != null) {
                    PhoneDraw.wrapped(g, fatal, 12, y + 16 + h, w - 24, PhoneTheme.secondaryText(), 0.6f, true);
                }
                button(g, 8, height() - 26, w - 16, 16, tr("telegram.change_keys"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL,
                        () -> editKeys = true);
            }
            case CLOSED, STARTING -> hint(g, tr("telegram.connecting"), y + 10);
            case WAIT_PHONE -> step(g, mouseX, mouseY, y, tr("telegram.phone_title"), tr("telegram.phone_hint"), phone, "+7 900 000-00-00",
                    tr("telegram.get_code"), () -> Telegram.submitPhone(phone.text()));
            case WAIT_CODE -> step(g, mouseX, mouseY, y, tr("telegram.code_title"),
                    tr("telegram.code_hint", Telegram.codePhone() == null ? "" : "+" + Telegram.codePhone()), code, "12345",
                    tr("telegram.login"), () -> Telegram.submitCode(code.text()));
            case WAIT_PASSWORD -> {
                String hintText = Telegram.passwordHint();
                step(g, mouseX, mouseY, y, tr("telegram.password_title"),
                        hintText == null || hintText.isEmpty() ? tr("telegram.password_hint") : tr("telegram.password_hint_with", hintText),
                        password, tr("telegram.password_placeholder"), tr("telegram.login"), () -> Telegram.submitPassword(password.text()));
            }
            case WAIT_OTHER -> hint(g, tr("telegram.other_step"), y + 10);
            default -> {
            }
        }
        if (status == Telegram.Status.WAIT_PHONE) {
            button(g, 8, height() - 26, w - 16, 16, tr("telegram.change_keys"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL,
                    () -> editKeys = true);
        }
    }

    /** Шаг входа: заголовок, пояснение, поле и кнопка. */
    private void step(GuiGraphics g, int mx, int my, int y, String title, String text, TextArea field, String placeholder,
                      String action, Runnable submit) {
        int w = width();
        PhoneDraw.centeredText(g, title, w / 2f, y, PhoneTheme.text(), 1f, false);
        y += 14;
        y += PhoneDraw.wrapped(g, text, 12, y, w - 24, PhoneTheme.secondaryText(), 0.65f, true) + 6;
        field.setBounds(12, y, w - 24, 18);
        field.setPlaceholder(placeholder);
        field.render(g, mx, my);
        y += 24;
        if (!field.text().isBlank()) {
            button(g, 12, y, w - 24, 16, action, mx, my, PhoneDraw.ButtonStyle.PRIMARY, submit);
        } else {
            PhoneDraw.button(g, 12, y, w - 24, 16, action, -1000, -1000, PhoneDraw.ButtonStyle.NORMAL);
        }
    }

    /** Ключи приложения Telegram. */
    private void renderKeys(GuiGraphics g, int mx, int my, int y) {
        int w = width();
        PhoneDraw.centeredText(g, tr("telegram.keys_title"), w / 2f, y, PhoneTheme.text(), 0.9f, false);
        y += 13;
        y += PhoneDraw.wrapped(g, tr("telegram.keys_step1"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.6f, false) + 3;
        button(g, 8, y, w - 16, 14, tr("telegram.open_my_telegram"), mx, my, PhoneDraw.ButtonStyle.NORMAL,
                () -> Util.getPlatform().openUri("https://my.telegram.org/apps"));
        y += 18;
        y += PhoneDraw.wrapped(g, tr("telegram.keys_step2"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.6f, false) + 3;
        apiId.setBounds(8, y, w - 16, 16);
        apiId.setPlaceholder("api_id");
        apiId.render(g, mx, my);
        y += 19;
        apiHash.setBounds(8, y, w - 16, 16);
        apiHash.setPlaceholder("api_hash");
        apiHash.render(g, mx, my);
        y += 21;
        boolean ok = apiId.text().strip().matches("\\d{1,10}") && apiHash.text().strip().length() >= 16;
        if (ok) {
            button(g, 8, y, w - 16, 16, tr("messenger.save"), mx, my, PhoneDraw.ButtonStyle.PRIMARY, () -> {
                editKeys = false;
                Telegram.configure(Integer.parseInt(apiId.text().strip()), apiHash.text().strip());
                apiId.setFocused(false);
                apiHash.setFocused(false);
            });
        } else {
            PhoneDraw.button(g, 8, y, w - 16, 16, tr("messenger.save"), -1000, -1000, PhoneDraw.ButtonStyle.NORMAL);
        }
        y += 21;
        PhoneDraw.wrapped(g, tr("telegram.keys_note"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.55f, false);
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        for (TextArea t : new TextArea[]{apiId, apiHash, phone, code, password}) {
            if (visible(t) && t.mouseClicked(x, y, button)) {
                for (TextArea other : new TextArea[]{apiId, apiHash, phone, code, password}) {
                    if (other != t) {
                        other.setFocused(false);
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(x, y, button);
    }

    private boolean visible(TextArea t) {
        if (t == apiId || t == apiHash) {
            return editKeys || Telegram.status() == Telegram.Status.NOT_CONFIGURED;
        }
        return field() == t;
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        TextArea f = field();
        return f != null && f.mouseDragged(x, y);
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        TextArea f = field();
        return f != null && f.mouseReleased();
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        TextArea f = focused();
        if (f == null) {
            return false;
        }
        if (event.key() == GLFW.GLFW_KEY_TAB && (f == apiId || f == apiHash)) {
            apiId.setFocused(f != apiId);
            apiHash.setFocused(f == apiId);
            return true;
        }
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && !f.text().isBlank()) {
            switch (Telegram.status()) {
                case WAIT_PHONE -> Telegram.submitPhone(phone.text());
                case WAIT_CODE -> Telegram.submitCode(code.text());
                case WAIT_PASSWORD -> Telegram.submitPassword(password.text());
                default -> {
                    return f.keyPressed(event);
                }
            }
            return true;
        }
        return f.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        TextArea f = focused();
        return f != null && f.charTyped(event);
    }

    private TextArea focused() {
        for (TextArea t : new TextArea[]{apiId, apiHash, phone, code, password}) {
            if (t.isFocused()) {
                return t;
            }
        }
        return null;
    }

    @Override
    boolean onBack() {
        if (editKeys && Telegram.status() != Telegram.Status.NOT_CONFIGURED) {
            editKeys = false;
            return true;
        }
        return false;
    }
}
