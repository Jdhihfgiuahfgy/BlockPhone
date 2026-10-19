package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.common.protocol.data.MessengerData;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Выбор игроков галочками: создание группы (с названием) или добавление участников в группу.
 */
final class UserPickerView extends MessengerView {
    private static final int FIELD_H = 16;

    private final long chatId;
    private final Set<UUID> exclude;
    private final TextArea name = new TextArea(false, 0.75f);
    private final TextArea search = new TextArea(false, 0.75f);
    private final Map<UUID, UserInfo> selected = new LinkedHashMap<>();
    private double scroll;
    private boolean sent;

    /** chatId &lt; 0 — создание новой группы. */
    private UserPickerView(MessengerApp app, long chatId, Set<UUID> exclude) {
        super(app);
        this.chatId = chatId;
        this.exclude = exclude;
        name.setMaxLength(MessengerData.MAX_TITLE_LENGTH);
        search.setMaxLength(16);
        search.setOnChange(text -> {
            scroll = 0;
            MessengerClient.search(text.strip());
        });
    }

    static UserPickerView createGroup(MessengerApp app) {
        return new UserPickerView(app, -1, Set.of());
    }

    static UserPickerView addMembers(MessengerApp app, long chatId, Set<UUID> existing) {
        return new UserPickerView(app, chatId, existing);
    }

    private boolean creating() {
        return chatId < 0;
    }

    @Override
    String title() {
        return MessengerApp.tr(creating() ? "messenger.new_group" : "messenger.add_members");
    }

    @Override
    String headerAction() {
        return MessengerApp.tr(creating() ? "messenger.create" : "messenger.add");
    }

    @Override
    int headerActionColor() {
        return valid() ? 0 : PhoneTheme.secondaryText();
    }

    private boolean valid() {
        return !sent && !selected.isEmpty() && (!creating() || !name.text().isBlank());
    }

    @Override
    void onHeaderAction() {
        if (!valid()) {
            return;
        }
        sent = true;
        List<UUID> members = new ArrayList<>(selected.keySet());
        if (creating()) {
            ClientNetwork.send(new MessengerPackets.CreateGroup(name.text().strip(), members));
        } else {
            ClientNetwork.send(new MessengerPackets.GroupAction(chatId, MessengerPackets.GroupAction.Action.ADD, "", members));
            app.pop();
        }
    }

    @Override
    void onShown() {
        (creating() ? name : search).setFocused(true);
        MessengerClient.search(search.text().strip());
    }

    @Override
    void onHidden() {
        name.setFocused(false);
        search.setFocused(false);
    }

    @Override
    boolean capturesKeyboard() {
        return name.isFocused() || search.isFocused();
    }

    private int searchY() {
        return creating() ? 4 + FIELD_H + 6 : 4;
    }

    private int listTop() {
        return searchY() + FIELD_H + 16;
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        if (creating()) {
            name.setBounds(8, 4, w - 16, FIELD_H);
            name.setPlaceholder(MessengerApp.tr("messenger.group_name"));
            name.render(g, mouseX, mouseY);
        }
        int sy = searchY();
        search.setBounds(8, sy, w - 16, FIELD_H);
        search.setPlaceholder(MessengerApp.tr("messenger.search_player"));
        search.render(g, mouseX, mouseY);

        int top = listTop();
        String label = MessengerApp.tr("messenger.selected", selected.size());
        PhoneDraw.text(g, label.toUpperCase(), 12, top - 10, PhoneTheme.secondaryText(), 0.6f, false);

        List<UserInfo> users = visibleUsers();
        if (users.isEmpty()) {
            MessengerApp.centerHint(g, this, MessengerApp.tr(search.text().isBlank() ? "messenger.nobody_online" : "messenger.not_found"), top + 20);
            return;
        }
        clampScroll(users.size());
        g.enableScissor(0, top, w, height());
        for (int i = 0; i < users.size(); i++) {
            int y = top + i * UserRows.ROW_H - (int) scroll;
            if (y + UserRows.ROW_H < top || y > height()) {
                continue;
            }
            UserInfo user = users.get(i);
            UserRows.draw(g, user, y, w, mouseX, mouseY >= top ? mouseY : -1000, selected.containsKey(user.uuid()), null);
        }
        g.disableScissor();
    }

    /** Выбранные (даже если пропали из поиска) + результаты поиска, без уже состоящих в группе. */
    private List<UserInfo> visibleUsers() {
        List<UserInfo> result = new ArrayList<>(selected.values());
        for (UserInfo user : MessengerClient.searchResults()) {
            if (!selected.containsKey(user.uuid()) && !exclude.contains(user.uuid())) {
                result.add(user);
            }
        }
        return result;
    }

    private void clampScroll(int count) {
        double max = Math.max(0, count * UserRows.ROW_H - (height() - listTop()));
        scroll = Math.max(0, Math.min(max, scroll));
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        boolean handled = false;
        if (creating()) {
            handled = name.mouseClicked(x, y, button);
        }
        handled |= search.mouseClicked(x, y, button);
        if (handled || button != 0) {
            return handled;
        }
        int top = listTop();
        if (y >= top) {
            List<UserInfo> users = visibleUsers();
            int index = (int) Math.floor((y - top + scroll) / UserRows.ROW_H);
            if (index >= 0 && index < users.size()) {
                UserInfo user = users.get(index);
                if (selected.remove(user.uuid()) == null && selected.size() + exclude.size() < MessengerData.MAX_GROUP_MEMBERS - 1) {
                    selected.put(user.uuid(), user);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    boolean mouseScrolled(double x, double y, double amount) {
        scroll -= amount * 20;
        clampScroll(visibleUsers().size());
        return true;
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        return name.mouseDragged(x, y) || search.mouseDragged(x, y);
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        name.mouseReleased();
        return search.mouseReleased();
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        return name.keyPressed(event) || search.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return name.charTyped(event) || search.charTyped(event);
    }
}
