package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.common.protocol.data.MessengerData;
import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.ChatType;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.MessengerPackets.GroupAction.Action;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Информация о чате. Личный: собеседник и его статус. Группа: участники, переименование (владелец),
 * добавление участников, исключение (владелец), выход из группы.
 */
final class ChatInfoView extends MessengerView {
    private static final int AVATAR = 40;
    private static final int BUTTON_H = 18;

    /** Кликабельная область, записанная при отрисовке. */
    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final long chatId;
    private final TextArea rename = new TextArea(false, 0.85f);
    private final List<Hit> hits = new ArrayList<>();
    private double scroll;
    private boolean renaming;

    ChatInfoView(MessengerApp app, long chatId) {
        super(app);
        this.chatId = chatId;
        rename.setMaxLength(MessengerData.MAX_TITLE_LENGTH);
    }

    private ChatInfo chat() {
        return MessengerClient.chat(chatId);
    }

    @Override
    String title() {
        ChatInfo chat = chat();
        return MessengerApp.tr(chat != null && chat.type() == ChatType.GROUP ? "messenger.group_info" : "messenger.contact_info");
    }

    @Override
    void tick() {
        if (MessengerClient.loaded() && chat() == null) {
            app.pop();
        }
    }

    @Override
    boolean capturesKeyboard() {
        return rename.isFocused();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        hits.clear();
        ChatInfo chat = chat();
        if (chat == null) {
            return;
        }
        int w = width();
        int y = 10 - (int) scroll;
        Avatars.drawChat(g, chat, (w - AVATAR) / 2, y, AVATAR);
        y += AVATAR + 6;

        boolean group = chat.type() == ChatType.GROUP;
        if (renaming) {
            rename.setBounds(12, y - 2, w - 24, 16);
            rename.render(g, mouseX, mouseY);
            y += 18;
            int bw = (w - 32) / 2;
            button(g, 12, y, bw, MessengerApp.tr("dialog.cancel"), PhoneTheme.card(), PhoneTheme.accent(), mouseX, mouseY, () -> renaming = false);
            button(g, 20 + bw, y, bw, MessengerApp.tr("messenger.save"), PhoneTheme.accent(), PhoneTheme.onAccent(), mouseX, mouseY, this::saveTitle);
            y += BUTTON_H + 8;
        } else {
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(MessengerClient.title(chat), w - 20, 1f), w / 2f, y, PhoneTheme.text(), 1f, false);
            y += 12;
            String subtitle;
            if (group) {
                subtitle = MessengerApp.tr("messenger.members", chat.members().size());
            } else {
                UserInfo partner = MessengerClient.partner(chat);
                subtitle = MessengerApp.tr(partner != null && MessengerClient.isOnline(partner) ? "messenger.online" : "messenger.offline");
            }
            PhoneDraw.centeredText(g, subtitle, w / 2f, y, PhoneTheme.secondaryText(), 0.7f, false);
            y += 14;
        }
        if (!group) {
            return;
        }

        boolean owner = MessengerClient.isOwner(chat);
        if (owner && !renaming) {
            button(g, 12, y, w - 24, MessengerApp.tr("messenger.rename"), PhoneTheme.card(), PhoneTheme.accent(), mouseX, mouseY, () -> {
                renaming = true;
                rename.setText(chat.title());
                rename.setFocused(true);
            });
            y += BUTTON_H + 4;
        }
        if (chat.members().size() < MessengerData.MAX_GROUP_MEMBERS) {
            Set<UUID> existing = new HashSet<>();
            chat.members().forEach(m -> existing.add(m.uuid()));
            button(g, 12, y, w - 24, "+ " + MessengerApp.tr("messenger.add_members"), PhoneTheme.card(), PhoneTheme.accent(), mouseX, mouseY,
                    () -> app.push(UserPickerView.addMembers(app, chatId, existing)));
            y += BUTTON_H + 8;
        }

        PhoneDraw.text(g, MessengerApp.tr("messenger.members", chat.members().size()).toUpperCase(), 12, y, PhoneTheme.secondaryText(), 0.6f, false);
        y += 9;
        for (UserInfo member : chat.members()) {
            boolean isOwner = member.uuid().equals(chat.owner());
            String note = isOwner ? MessengerApp.tr("messenger.owner") : null;
            boolean canRemove = owner && !MessengerClient.isSelf(member.uuid());
            UserRows.draw(g, member, y, canRemove ? w - 22 : w, mouseX, mouseY, null, note);
            if (canRemove) {
                int bx = w - 20;
                boolean hovered = PhoneDraw.inside(mouseX, mouseY, bx, y + 6, 14, 14);
                PhoneDraw.roundedRect(g, bx, y + 6, bx + 14, y + 20, 7, hovered ? PhoneTheme.danger() : PhoneTheme.separator());
                PhoneDraw.centeredText(g, "×", bx + 7, y + 9, 0xFFFFFFFF, 1f, false);
                hits.add(new Hit(bx, y + 6, 14, 14, () -> app.showDialog(new PhoneDialog(
                        MessengerApp.tr("messenger.remove_title"), MessengerApp.tr("messenger.remove_message", member.name()),
                        MessengerApp.tr("messenger.remove"), true,
                        () -> ClientNetwork.send(new MessengerPackets.GroupAction(chatId, Action.REMOVE, "", List.of(member.uuid())))))));
            }
            y += UserRows.ROW_H;
        }
        y += 8;
        button(g, 12, y, w - 24, MessengerApp.tr("messenger.leave"), PhoneTheme.card(), PhoneTheme.danger(), mouseX, mouseY,
                () -> app.showDialog(new PhoneDialog(MessengerApp.tr("messenger.leave_title"), MessengerApp.tr("messenger.leave_message"),
                        MessengerApp.tr("messenger.leave"), true,
                        () -> ClientNetwork.send(new MessengerPackets.GroupAction(chatId, Action.LEAVE, "", List.of())))));
        y += BUTTON_H + 10;
        clampScroll(y + (int) scroll);
    }

    private void button(GuiGraphics g, int x, int y, int w, String label, int bg, int fg, int mx, int my, Runnable action) {
        boolean hovered = PhoneDraw.inside(mx, my, x, y, w, BUTTON_H);
        if (bg == PhoneTheme.card()) {
            PhoneDraw.card(g, x, y, x + w, y + BUTTON_H, hovered);
        } else {
            PhoneDraw.roundedRect(g, x, y, x + w, y + BUTTON_H, 4, hovered ? PhoneTheme.accentHover() : bg);
        }
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(label, w - 8, 0.8f), x + w / 2f, y + 6, fg, 0.8f, false);
        hits.add(new Hit(x, y, w, BUTTON_H, action));
    }

    private void saveTitle() {
        String title = rename.text().strip();
        if (!title.isEmpty()) {
            ClientNetwork.send(new MessengerPackets.GroupAction(chatId, Action.RENAME, title, List.of()));
        }
        renaming = false;
        rename.setFocused(false);
    }

    private int contentHeight;

    private void clampScroll(int total) {
        contentHeight = total;
        double max = Math.max(0, total - height());
        scroll = Math.max(0, Math.min(max, scroll));
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (renaming && rename.mouseClicked(x, y, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        for (Hit hit : List.copyOf(hits)) {
            if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    boolean mouseScrolled(double x, double y, double amount) {
        scroll -= amount * 20;
        clampScroll(contentHeight);
        return true;
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        if (renaming && (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER)) {
            saveTitle();
            return true;
        }
        return renaming && rename.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return renaming && rename.charTyped(event);
    }

    @Override
    boolean onBack() {
        if (renaming) {
            renaming = false;
            rename.setFocused(false);
            return true;
        }
        return false;
    }
}
