package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.List;

/** Новый чат: поиск игрока по нику (пустой поиск — кто сейчас в сети) и кнопка «Создать группу». */
final class NewChatView extends MessengerView {
    private static final int SEARCH_H = 16;
    private static final int GROUP_ROW_Y = 26;
    private static final int GROUP_ROW_H = 22;
    private static final int LIST_TOP = GROUP_ROW_Y + GROUP_ROW_H + 14;

    private final TextArea search = new TextArea(false, 0.75f);
    private double scroll;
    private boolean opening;

    NewChatView(MessengerApp app) {
        super(app);
        search.setMaxLength(16);
        search.setOnChange(text -> {
            scroll = 0;
            MessengerClient.search(text.strip());
        });
    }

    @Override
    String title() {
        return MessengerApp.tr("messenger.new_chat");
    }

    @Override
    void onShown() {
        opening = false;
        search.setFocused(true);
        MessengerClient.search(search.text().strip());
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
        PhoneDraw.roundedRect(g, 8, 4, w - 8, 4 + SEARCH_H, 6, PhoneTheme.card());
        search.setBounds(10, 5, w - 20, SEARCH_H - 2);
        search.setPlaceholder(MessengerApp.tr("messenger.search_player"));
        search.render(g, mouseX, mouseY);

        boolean hovered = PhoneDraw.inside(mouseX, mouseY, 8, GROUP_ROW_Y, w - 16, GROUP_ROW_H);
        PhoneDraw.roundedRect(g, 8, GROUP_ROW_Y, w - 8, GROUP_ROW_Y + GROUP_ROW_H, 6, hovered ? PhoneTheme.cardHover() : PhoneTheme.card());
        PhoneDraw.text(g, "+  " + MessengerApp.tr("messenger.create_group"), 16, GROUP_ROW_Y + 7, PhoneTheme.ACCENT, 0.85f, false);

        String header = MessengerApp.tr(search.text().isBlank() ? "messenger.online_now" : "messenger.found");
        PhoneDraw.text(g, header.toUpperCase(), 12, LIST_TOP - 10, PhoneTheme.secondaryText(), 0.6f, false);

        List<UserInfo> users = MessengerClient.searchResults();
        if (opening) {
            MessengerApp.centerHint(g, this, MessengerApp.tr("messenger.opening"), LIST_TOP + 20);
            return;
        }
        if (users.isEmpty()) {
            String key = search.text().isBlank() ? "messenger.nobody_online" : "messenger.not_found";
            MessengerApp.centerHint(g, this, MessengerApp.tr(key), LIST_TOP + 20);
            return;
        }
        clampScroll(users.size());
        g.enableScissor(0, LIST_TOP, w, height());
        for (int i = 0; i < users.size(); i++) {
            int y = LIST_TOP + i * UserRows.ROW_H - (int) scroll;
            if (y + UserRows.ROW_H < LIST_TOP || y > height()) {
                continue;
            }
            UserRows.draw(g, users.get(i), y, w, mouseX, mouseY >= LIST_TOP ? mouseY : -1000, null, null);
        }
        g.disableScissor();
    }

    private void clampScroll(int count) {
        double max = Math.max(0, count * UserRows.ROW_H - (height() - LIST_TOP));
        scroll = Math.max(0, Math.min(max, scroll));
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (search.mouseClicked(x, y, button)) {
            return true;
        }
        if (button != 0 || opening) {
            return false;
        }
        if (PhoneDraw.inside(x, y, 8, GROUP_ROW_Y, width() - 16, GROUP_ROW_H)) {
            app.push(UserPickerView.createGroup(app));
            return true;
        }
        if (y >= LIST_TOP) {
            List<UserInfo> users = MessengerClient.searchResults();
            int index = (int) Math.floor((y - LIST_TOP + scroll) / UserRows.ROW_H);
            if (index >= 0 && index < users.size()) {
                opening = true;
                ClientNetwork.send(new MessengerPackets.OpenDirect(users.get(index).uuid()));
                return true;
            }
        }
        return false;
    }

    @Override
    boolean mouseScrolled(double x, double y, double amount) {
        scroll -= amount * 20;
        clampScroll(MessengerClient.searchResults().size());
        return true;
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
        return search.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return search.charTyped(event);
    }

    @Override
    boolean onBack() {
        if (search.isFocused() && !search.text().isEmpty()) {
            search.setText("");
            MessengerClient.search("");
            return true;
        }
        return false;
    }
}
