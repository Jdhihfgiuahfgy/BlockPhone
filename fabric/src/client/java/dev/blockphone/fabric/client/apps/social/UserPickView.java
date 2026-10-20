package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.List;
import java.util.function.Consumer;

/** Выбор игрока поиском по нику (пустой поиск — кто сейчас в сети). Выбор возвращает на предыдущий экран. */
final class UserPickView extends SocialView {
    private static final int LIST_TOP = 24;

    private final String title;
    private final Consumer<UserInfo> onPick;
    private final TextArea search = new TextArea(false, 0.75f);

    UserPickView(SocialApp app, String title, Consumer<UserInfo> onPick) {
        super(app);
        this.title = title;
        this.onPick = onPick;
        search.setMaxLength(16);
        search.setOnChange(text -> {
            scroll = 0;
            SocialClient.search(text.strip());
        });
    }

    @Override
    String title() {
        return title;
    }

    @Override
    void onShown() {
        search.setFocused(true);
        SocialClient.search(search.text().strip());
    }

    @Override
    void onHidden() {
        search.setFocused(false);
    }

    @Override
    boolean capturesKeyboard() {
        return search.isFocused();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        search.setBounds(6, 4, w - 12, 16);
        search.setPlaceholder(tr("messenger.search_player"));
        search.render(g, mouseX, mouseY);
        List<UserInfo> users = SocialClient.searchUsers();
        if (users.isEmpty()) {
            PhoneDraw.wrapped(g, tr(search.text().isBlank() ? "messenger.nobody_online" : "messenger.not_found"), 12, LIST_TOP + 16,
                    w - 24, PhoneTheme.secondaryText(), 0.75f, true);
            return;
        }
        clampScroll(users.size() * Rows.H + 4, height() - LIST_TOP);
        int listMouseY = mouseY >= LIST_TOP ? mouseY : -1000;
        beginList(g, LIST_TOP, height());
        int y = LIST_TOP - (int) scroll;
        for (UserInfo user : users) {
            Rows.user(this, g, user, y, w, mouseX, listMouseY, null, () -> {
                app.pop();
                onPick.accept(user);
            });
            y += Rows.H;
        }
        endList(g);
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (search.mouseClicked(x, y, button)) {
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        return search.mouseDragged(x, y);
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        return search.mouseReleased();
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        return search.isFocused() && search.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return search.isFocused() && search.charTyped(event);
    }
}
