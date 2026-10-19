package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.common.protocol.data.MessengerData;
import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.ChatType;
import dev.blockphone.common.protocol.data.MessengerData.MessageInfo;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Переписка: пузыри сообщений (свои справа, синие), разделители дней, подгрузка истории при прокрутке вверх,
 * поле ввода (Enter — отправить, Shift+Enter — новая строка), ПКМ по сообщению — меню (изменить/удалить/копировать).
 */
final class ChatView extends MessengerView {
    private static final float TEXT_SCALE = 0.75f;
    private static final float SMALL = 0.55f;
    private static final int LINE_H = 8;
    private static final int STATUS_H = 12;
    private static final int INPUT_H = 26;
    private static final int PAD_X = 5;
    private static final int PAD_Y = 3;
    private static final int GAP = 3;
    private static final int SEPARATOR_H = 14;
    private static final int OWN_COLOR = 0xFF0A84FF;
    private static final int[] NAME_COLORS = {0xFFE5534B, 0xFF2F81F7, 0xFF3FB950, 0xFFD29922, 0xFFA371F7, 0xFFDB61A2};
    private static final long TYPING_SEND_MS = 3000;

    /** Элемент ленты: сообщение (или неподтверждённое) либо разделитель дня. */
    private record Item(MessageInfo message, MessengerClient.Pending pending, String separator,
                        List<FormattedCharSequence> lines, String name, String time, int width, int height, boolean own) {
    }

    /** Область сообщения на экране (для ПКМ). */
    private record Hit(int x, int y, int w, int h, MessageInfo message) {
    }

    private record MenuItem(String label, Runnable action, boolean danger) {
    }

    private final long chatId;
    private final TextArea input = new TextArea(true, TEXT_SCALE);
    private final Map<String, List<FormattedCharSequence>> wrapCache = new HashMap<>();
    private final List<Hit> hits = new ArrayList<>();
    private double scroll;
    private long editingId = -1;
    private long lastTypingSent;
    private List<MenuItem> menu;
    private int menuX;
    private int menuY;

    ChatView(MessengerApp app, long chatId) {
        super(app);
        this.chatId = chatId;
        input.setMaxLength(MessengerData.MAX_MESSAGE_LENGTH);
        input.setOnChange(text -> {
            long now = System.currentTimeMillis();
            if (!text.isBlank() && editingId < 0 && now - lastTypingSent > TYPING_SEND_MS) {
                lastTypingSent = now;
                ClientNetwork.send(new MessengerPackets.Typing(chatId));
            }
        });
    }

    private ChatInfo chat() {
        return MessengerClient.chat(chatId);
    }

    @Override
    String title() {
        ChatInfo chat = chat();
        return chat != null ? MessengerClient.title(chat) : MessengerApp.tr("app.messenger");
    }

    @Override
    String headerAction() {
        return "i";
    }

    @Override
    void onHeaderAction() {
        if (chat() != null) {
            app.push(new ChatInfoView(app, chatId));
        }
    }

    @Override
    void onShown() {
        MessengerClient.requestHistory(chatId);
        MessengerClient.setOpenChat(chatId);
        input.setFocused(true);
    }

    @Override
    void onHidden() {
        MessengerClient.setOpenChat(-1);
        input.setFocused(false);
    }

    @Override
    void tick() {
        if (MessengerClient.loaded() && chat() == null) {
            app.pop(); // Чат удалён или нас исключили.
        }
    }

    @Override
    boolean capturesKeyboard() {
        return input.isFocused();
    }

    // --- Раскладка ---

    private int inputTop() {
        return height() - INPUT_H - 4;
    }

    private int areaBottom() {
        return inputTop() - (editingId >= 0 ? 14 : 4);
    }

    private int maxTextWidth() {
        return (int) (width() * 0.72f) - PAD_X * 2;
    }

    private List<FormattedCharSequence> wrap(String key, String text) {
        return wrapCache.computeIfAbsent(key, k -> PhoneDraw.font().split(FormattedText.of(text), (int) (maxTextWidth() / TEXT_SCALE)));
    }

    private List<Item> layout() {
        ChatInfo chat = chat();
        boolean group = chat != null && chat.type() == ChatType.GROUP;
        List<Item> items = new ArrayList<>();
        MessengerClient.ChatMessages data = MessengerClient.messages(chatId);
        long prevTime = 0;
        String prevSender = null;
        for (MessageInfo m : data.list) {
            if (prevTime == 0 || !MessengerFormat.sameDay(prevTime, m.sentAt())) {
                items.add(separator(MessengerFormat.day(m.sentAt())));
                prevSender = null;
            }
            boolean own = MessengerClient.isSelf(m.sender().uuid());
            String text = m.deleted() ? MessengerApp.tr("messenger.deleted") : m.text();
            List<FormattedCharSequence> lines = wrap(m.id() + ":" + m.editedAt() + ":" + m.deleted(), text);
            String senderKey = m.sender().uuid().toString();
            String name = group && !own && !senderKey.equals(prevSender) ? m.sender().name() : null;
            String time = (m.editedAt() > 0 && !m.deleted() ? MessengerApp.tr("messenger.edited") + " " : "") + MessengerFormat.time(m.sentAt());
            items.add(bubble(m, null, lines, name, time, own));
            prevTime = m.sentAt();
            prevSender = senderKey;
        }
        for (MessengerClient.Pending p : MessengerClient.pending(chatId)) {
            items.add(bubble(null, p, wrap("p:" + p.nonce(), p.text()), null, "...", true));
        }
        return items;
    }

    private Item separator(String label) {
        return new Item(null, null, label, List.of(), null, null, 0, SEPARATOR_H, false);
    }

    private Item bubble(MessageInfo m, MessengerClient.Pending p, List<FormattedCharSequence> lines, String name, String time, boolean own) {
        float textW = 0;
        for (FormattedCharSequence line : lines) {
            textW = Math.max(textW, PhoneDraw.font().width(line) * TEXT_SCALE);
        }
        float nameW = name != null ? PhoneDraw.width(name, SMALL) : 0;
        float timeW = PhoneDraw.width(time, SMALL);
        int width = (int) Math.ceil(Math.max(textW, Math.max(nameW, timeW))) + PAD_X * 2;
        int height = PAD_Y * 2 + lines.size() * LINE_H + 6 + (name != null ? 7 : 0);
        return new Item(m, p, null, lines, name, time, width, height, own);
    }

    private static int totalHeight(List<Item> items) {
        int total = 0;
        for (Item item : items) {
            total += item.height() + GAP;
        }
        return total;
    }

    // --- Отрисовка ---

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        renderStatus(g);

        List<Item> items = layout();
        int areaTop = STATUS_H + 2;
        int areaBottom = areaBottom();
        int total = totalHeight(items);
        clampScroll(total);
        MessengerClient.ChatMessages data = MessengerClient.messages(chatId);
        if (data.loaded && data.hasMore && scroll >= total - (areaBottom - areaTop) - 20) {
            MessengerClient.requestOlder(chatId);
        }

        hits.clear();
        g.enableScissor(0, areaTop, w, areaBottom);
        if (!data.loaded) {
            MessengerApp.centerHint(g, this, MessengerApp.tr("messenger.loading"), (areaTop + areaBottom) / 2 - 4);
        } else if (items.isEmpty()) {
            MessengerApp.centerHint(g, this, MessengerApp.tr("messenger.say_hi"), (areaTop + areaBottom) / 2 - 4);
        }
        int y = areaBottom + (int) scroll - total;
        for (Item item : items) {
            if (y + item.height() >= areaTop && y <= areaBottom) {
                renderItem(g, item, y, mouseX, mouseY);
            }
            y += item.height() + GAP;
        }
        g.disableScissor();

        renderInput(g, mouseX, mouseY);
        if (menu != null) {
            renderMenu(g, mouseX, mouseY);
        }
    }

    private void renderStatus(GuiGraphics g) {
        ChatInfo chat = chat();
        if (chat == null) {
            return;
        }
        List<String> typing = MessengerClient.typing(chatId);
        String status;
        int color = PhoneTheme.secondaryText();
        if (!typing.isEmpty()) {
            status = chat.type() == ChatType.GROUP
                    ? MessengerApp.tr("messenger.typing_named", String.join(", ", typing))
                    : MessengerApp.tr("messenger.typing");
            color = PhoneTheme.ACCENT;
        } else if (chat.type() == ChatType.GROUP) {
            long online = chat.members().stream().filter(MessengerClient::isOnline).count();
            status = MessengerApp.tr("messenger.members_online", chat.members().size(), online);
        } else {
            UserInfo partner = MessengerClient.partner(chat);
            boolean online = partner != null && MessengerClient.isOnline(partner);
            status = MessengerApp.tr(online ? "messenger.online" : "messenger.offline");
            if (online) {
                color = PhoneTheme.ACCENT;
            }
        }
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(status, width() - 16, 0.6f), width() / 2f, 3, color, 0.6f, false);
        g.fill(0, STATUS_H, width(), STATUS_H + 1, PhoneTheme.separator());
    }

    private void renderItem(GuiGraphics g, Item item, int y, int mouseX, int mouseY) {
        int w = width();
        if (item.separator() != null) {
            float tw = PhoneDraw.width(item.separator(), SMALL) + 10;
            int x1 = (int) (w / 2f - tw / 2);
            PhoneDraw.roundedRect(g, x1, y + 2, (int) (x1 + tw), y + 11, 4, PhoneTheme.dark() ? 0x40FFFFFF : 0x18000000);
            PhoneDraw.centeredText(g, item.separator(), w / 2f, y + 4.5f, PhoneTheme.secondaryText(), SMALL, false);
            return;
        }
        int x = item.own() ? w - 6 - item.width() : 6;
        boolean pending = item.pending() != null;
        boolean deleted = item.message() != null && item.message().deleted();
        int bg = item.own() ? OWN_COLOR : PhoneTheme.card();
        if (pending) {
            bg = (bg & 0x00FFFFFF) | 0x99000000;
        }
        PhoneDraw.roundedRect(g, x, y, x + item.width(), y + item.height(), 6, bg);
        int textColor = item.own() ? 0xFFFFFFFF : PhoneTheme.text();
        if (deleted) {
            textColor = item.own() ? 0xB0FFFFFF : PhoneTheme.secondaryText();
        }
        int ty = y + PAD_Y;
        if (item.name() != null) {
            int color = NAME_COLORS[Math.floorMod(item.message().sender().uuid().hashCode(), NAME_COLORS.length)];
            PhoneDraw.text(g, item.name(), x + PAD_X, ty, color, SMALL, false);
            ty += 7;
        }
        for (FormattedCharSequence line : item.lines()) {
            g.pose().pushMatrix();
            g.pose().translate(x + PAD_X, ty);
            g.pose().scale(TEXT_SCALE, TEXT_SCALE);
            g.drawString(PhoneDraw.font(), line, 0, 0, textColor, false);
            g.pose().popMatrix();
            ty += LINE_H;
        }
        int timeColor = item.own() ? 0xC0FFFFFF : PhoneTheme.secondaryText();
        PhoneDraw.rightText(g, item.time(), x + item.width() - PAD_X, ty + 1, timeColor, SMALL);
        if (item.message() != null && !deleted) {
            hits.add(new Hit(x, y, item.width(), item.height(), item.message()));
        }
    }

    private void renderInput(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        int top = inputTop();
        if (editingId >= 0) {
            PhoneDraw.text(g, MessengerApp.tr("messenger.editing"), 10, top - 11, PhoneTheme.ACCENT, 0.6f, false);
        }
        PhoneDraw.roundedRect(g, 6, top, w - 30, top + INPUT_H, 8, PhoneTheme.card());
        input.setBounds(8, top + 1, w - 40, INPUT_H - 2);
        input.setPlaceholder(MessengerApp.tr("messenger.message_placeholder"));
        input.render(g, mouseX, mouseY);

        boolean canSend = !input.text().isBlank();
        int bx = w - 26;
        int by = top + 2;
        boolean hovered = PhoneDraw.inside(mouseX, mouseY, bx, by, 22, 22);
        int color = canSend ? (hovered ? 0xFF3D9BFF : OWN_COLOR) : PhoneTheme.separator();
        PhoneDraw.roundedRect(g, bx, by, bx + 22, by + 22, 11, color);
        PhoneDraw.centeredText(g, editingId >= 0 ? "✔" : "➤", bx + 11, by + 7, 0xFFFFFFFF, 1f, false);
    }

    private void renderMenu(GuiGraphics g, int mouseX, int mouseY) {
        int mw = 76;
        int rowH = 14;
        int mh = menu.size() * rowH + 4;
        PhoneDraw.roundedRect(g, menuX - 1, menuY - 1, menuX + mw + 1, menuY + mh + 1, 6, 0x40000000);
        PhoneDraw.roundedRect(g, menuX, menuY, menuX + mw, menuY + mh, 6, PhoneTheme.card());
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.get(i);
            int ry = menuY + 2 + i * rowH;
            if (PhoneDraw.inside(mouseX, mouseY, menuX, ry, mw, rowH)) {
                g.fill(menuX + 2, ry, menuX + mw - 2, ry + rowH, PhoneTheme.cardHover());
            }
            PhoneDraw.text(g, item.label(), menuX + 6, ry + 4, item.danger() ? 0xFFFF3B30 : PhoneTheme.text(), 0.75f, false);
        }
    }

    private void clampScroll(int total) {
        int area = areaBottom() - (STATUS_H + 2);
        double max = Math.max(0, total - area);
        scroll = Math.max(0, Math.min(max, scroll));
    }

    // --- Действия ---

    private void submit() {
        String text = input.text().strip();
        if (text.isEmpty()) {
            return;
        }
        if (editingId >= 0) {
            ClientNetwork.send(new MessengerPackets.EditMessage(editingId, text));
            editingId = -1;
        } else {
            MessengerClient.sendMessage(chatId, text);
        }
        input.setText("");
        scroll = 0;
    }

    private void openMenu(MessageInfo message, int x, int y) {
        List<MenuItem> items = new ArrayList<>();
        boolean own = MessengerClient.isSelf(message.sender().uuid());
        if (own) {
            items.add(new MenuItem(MessengerApp.tr("messenger.edit"), () -> {
                editingId = message.id();
                input.setText(message.text());
                input.setFocused(true);
            }, false));
        }
        items.add(new MenuItem(MessengerApp.tr("messenger.copy"),
                () -> Minecraft.getInstance().keyboardHandler.setClipboard(message.text()), false));
        if (own) {
            items.add(new MenuItem(MessengerApp.tr("dialog.delete"), () -> app.showDialog(PhoneDialog.confirmDelete(
                    MessengerApp.tr("messenger.delete_title"), MessengerApp.tr("messenger.delete_message"),
                    () -> ClientNetwork.send(new MessengerPackets.DeleteMessage(message.id())))), true));
        }
        menu = items;
        int mh = items.size() * 14 + 4;
        menuX = Math.max(4, Math.min(width() - 80, x));
        menuY = Math.max(STATUS_H + 2, Math.min(areaBottom() - mh, y));
    }

    // --- Ввод ---

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (menu != null) {
            int rowH = 14;
            for (int i = 0; i < menu.size(); i++) {
                if (PhoneDraw.inside(x, y, menuX, menuY + 2 + i * rowH, 76, rowH)) {
                    MenuItem item = menu.get(i);
                    menu = null;
                    item.action().run();
                    return true;
                }
            }
            menu = null;
            return true;
        }
        if (PhoneDraw.inside(x, y, width() - 26, inputTop() + 2, 22, 22)) {
            submit();
            return true;
        }
        if (input.mouseClicked(x, y, button)) {
            return true;
        }
        if (y < STATUS_H && chat() != null) {
            app.push(new ChatInfoView(app, chatId));
            return true;
        }
        if (button == 1) {
            for (Hit hit : hits) {
                if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                    openMenu(hit.message(), (int) x, (int) y);
                    return true;
                }
            }
        }
        input.setFocused(true);
        return false;
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        return input.mouseDragged(x, y);
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        return input.mouseReleased();
    }

    @Override
    boolean mouseScrolled(double x, double y, double amount) {
        if (input.mouseScrolled(x, y, amount)) {
            return true;
        }
        menu = null;
        scroll += amount * 20;
        clampScroll(totalHeight(layout()));
        return true;
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !event.hasShiftDown()) {
            submit();
            return true;
        }
        return input.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return input.charTyped(event);
    }

    @Override
    boolean onBack() {
        if (menu != null) {
            menu = null;
            return true;
        }
        if (editingId >= 0) {
            editingId = -1;
            input.setText("");
            return true;
        }
        return false;
    }
}
