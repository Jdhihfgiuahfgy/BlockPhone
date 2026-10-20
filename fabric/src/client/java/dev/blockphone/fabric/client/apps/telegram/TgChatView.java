package dev.blockphone.fabric.client.apps.telegram;

import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.telegram.Telegram;
import dev.blockphone.fabric.client.telegram.VideoNoteRecorder;
import dev.blockphone.fabric.client.ui.TextArea;
import it.tdlight.jni.TdApi;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Переписка Telegram: пузыри (свои — справа), фото, «кружочки» (обложка), подгрузка истории при прокрутке вверх,
 * поле ввода (Enter — отправить), кнопка «◯» — записать «кружочек» из игры.
 */
final class TgChatView extends TgView {
    private static final float TEXT = 0.75f;
    private static final float SMALL = 0.55f;
    private static final int LINE_H = 8;
    private static final int PAD = 5;
    private static final int GAP = 3;
    private static final int INPUT_H = 20;
    private static final int MEDIA_W = 110;
    private static final int NOTE = 64;
    private static final int[] NAME_COLORS = {0xFFE17076, 0xFFFAA774, 0xFFA695E7, 0xFF7BC862, 0xFF6EC9CB, 0xFF65AADD, 0xFFEE7AAE};

    private record Item(TdApi.Message message, List<FormattedCharSequence> lines, String name, String time, int mediaW, int mediaH,
                        boolean note, int width, int height) {
    }

    private final long chatId;
    private final TextArea input = new TextArea(true, TEXT);
    private final Map<String, List<FormattedCharSequence>> wrapCache = new HashMap<>();

    TgChatView(TelegramApp app, long chatId) {
        super(app);
        this.chatId = chatId;
        input.setMaxLength(4096);
    }

    @Override
    String title() {
        TdApi.Chat chat = Telegram.chat(chatId);
        return chat != null ? Telegram.title(chat) : "Telegram";
    }

    @Override
    void onShown() {
        Telegram.openChat(chatId);
        input.setFocused(true);
    }

    @Override
    void onHidden() {
        Telegram.closeChat(chatId);
        input.setFocused(false);
    }

    @Override
    boolean capturesKeyboard() {
        return input.isFocused();
    }

    private boolean group() {
        TdApi.Chat chat = Telegram.chat(chatId);
        return chat != null && (chat.type instanceof TdApi.ChatTypeBasicGroup
                || chat.type instanceof TdApi.ChatTypeSupergroup s && !s.isChannel);
    }

    private int areaBottom() {
        return height() - INPUT_H - 8;
    }

    private List<FormattedCharSequence> wrap(TdApi.Message m, String text) {
        int max = (int) (width() * 0.72f) - PAD * 2;
        return wrapCache.computeIfAbsent(m.id + ":" + text.hashCode(),
                k -> PhoneDraw.font().split(FormattedText.of(text), (int) (max / TEXT)));
    }

    private List<Item> layout() {
        boolean group = group();
        List<Item> items = new ArrayList<>();
        long prevSender = Long.MIN_VALUE;
        for (TdApi.Message m : Telegram.messages(chatId).list) {
            String text = switch (m.content) {
                case TdApi.MessageText t -> t.text.text;
                case TdApi.MessagePhoto p -> p.caption.text;
                case TdApi.MessageVideoNote v -> "";
                default -> Telegram.text(m);
            };
            List<FormattedCharSequence> lines = text.isEmpty() ? List.of() : wrap(m, text);
            long sender = m.senderId instanceof TdApi.MessageSenderUser u ? u.userId
                    : m.senderId instanceof TdApi.MessageSenderChat c ? c.chatId : 0;
            String name = group && !m.isOutgoing && sender != prevSender ? Telegram.senderName(m) : null;
            prevSender = sender;
            String time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochSecond(m.date).atZone(ZoneId.systemDefault()));
            int mediaW = 0;
            int mediaH = 0;
            boolean note = false;
            if (m.content instanceof TdApi.MessagePhoto p) {
                TdApi.PhotoSize size = bestSize(p.photo);
                if (size != null) {
                    mediaW = MEDIA_W;
                    mediaH = Math.round(MEDIA_W * Math.max(0.5f, Math.min(1.3f, size.height / (float) Math.max(1, size.width))));
                }
            } else if (m.content instanceof TdApi.MessageVideoNote) {
                mediaW = NOTE;
                mediaH = NOTE + 8;
                note = true;
            }
            float textW = 0;
            for (FormattedCharSequence line : lines) {
                textW = Math.max(textW, PhoneDraw.font().width(line) * TEXT);
            }
            float contentW = Math.max(textW, Math.max(name != null ? PhoneDraw.width(name, SMALL) : 0, PhoneDraw.width(time, SMALL)));
            contentW = Math.max(contentW, mediaW);
            int width = (int) Math.ceil(contentW) + PAD * 2;
            int height = 6 + lines.size() * LINE_H + 6 + (name != null ? 7 : 0) + (mediaH > 0 ? mediaH + 3 : 0);
            items.add(new Item(m, lines, name, time, mediaW, mediaH, note, width, height));
        }
        return items;
    }

    /** Размер фото не меньше 320 px по ширине (или самый большой из имеющихся). */
    private static TdApi.PhotoSize bestSize(TdApi.Photo photo) {
        TdApi.PhotoSize best = null;
        for (TdApi.PhotoSize s : photo.sizes) {
            if (best == null || (best.width < 320 && s.width > best.width) || (s.width >= 320 && s.width < best.width)) {
                best = s;
            }
        }
        return best;
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        Telegram.ChatMessages data = Telegram.messages(chatId);
        List<Item> items = layout();
        int areaBottom = areaBottom();
        int total = 0;
        for (Item item : items) {
            total += item.height() + GAP;
        }
        clampScroll(total, areaBottom);
        if (data.loaded && data.hasMore && scroll >= total - areaBottom - 20) {
            Telegram.loadOlder(chatId);
        }
        beginList(g, 0, areaBottom);
        if (!data.loaded) {
            hint(g, tr("messenger.loading"), areaBottom / 2 - 4);
        }
        int y = areaBottom + (int) scroll - total;
        for (Item item : items) {
            if (y + item.height() >= 0 && y <= areaBottom) {
                renderItem(g, item, y);
            }
            y += item.height() + GAP;
        }
        endList(g);
        renderInput(g, mouseX, mouseY);
    }

    private void renderItem(GuiGraphics g, Item item, int y) {
        int w = width();
        TdApi.Message m = item.message();
        boolean own = m.isOutgoing;
        int x = own ? w - 6 - item.width() : 6;
        int bg = own ? PhoneTheme.accent() : PhoneTheme.card();
        if (m.sendingState instanceof TdApi.MessageSendingStatePending) {
            bg = (bg & 0x00FFFFFF) | 0x99000000;
        }
        PhoneDraw.roundedRect(g, x, y, x + item.width(), y + item.height(), 6, bg);
        int textColor = own ? PhoneTheme.onAccent() : PhoneTheme.text();
        int ty = y + 3;
        if (item.name() != null) {
            long sender = m.senderId instanceof TdApi.MessageSenderUser u ? u.userId : 0;
            PhoneDraw.text(g, item.name(), x + PAD, ty, NAME_COLORS[(int) Math.floorMod(sender, (long) NAME_COLORS.length)], SMALL, false);
            ty += 7;
        }
        if (item.mediaH() > 0) {
            renderMedia(g, m, item, x + PAD, ty + 1, own);
            ty += item.mediaH() + 3;
        }
        for (FormattedCharSequence line : item.lines()) {
            g.pose().pushMatrix();
            g.pose().translate(x + PAD, ty);
            g.pose().scale(TEXT, TEXT);
            g.drawString(PhoneDraw.font(), line, 0, 0, textColor, false);
            g.pose().popMatrix();
            ty += LINE_H;
        }
        int timeColor = own ? (PhoneTheme.onAccent() & 0x00FFFFFF) | 0xC0000000 : PhoneTheme.secondaryText();
        PhoneDraw.rightText(g, item.time(), x + item.width() - PAD, ty + 1, timeColor, SMALL);
    }

    private void renderMedia(GuiGraphics g, TdApi.Message m, Item item, int x, int y, boolean own) {
        TdApi.File file = null;
        if (m.content instanceof TdApi.MessagePhoto p) {
            TdApi.PhotoSize size = bestSize(p.photo);
            file = size != null ? size.photo : null;
        } else if (m.content instanceof TdApi.MessageVideoNote v && v.videoNote.thumbnail != null) {
            file = v.videoNote.thumbnail.file;
        }
        Path path = Telegram.localFile(file);
        PhotoTextures.Image image = path != null ? PhotoTextures.preview(new PhotoStore.Photo(path, 0), 320) : null;
        int mw = item.mediaW();
        int mh = item.note() ? NOTE : item.mediaH();
        if (image != null) {
            PhoneDraw.textureCover(g, image.id(), x, y, mw, mh, image.width(), image.height());
        } else {
            g.fill(x, y, x + mw, y + mh, PhoneTheme.dark() ? 0xFF1F2738 : 0xFFDCE3EE);
            PhoneDraw.centeredText(g, "...", x + mw / 2f, y + mh / 2f - 3, PhoneTheme.secondaryText(), 0.75f, false);
        }
        if (item.note() && m.content instanceof TdApi.MessageVideoNote v) {
            // Круглая рамка «кружочка» поверх обложки (углы закрашены цветом пузыря).
            int bubble = own ? PhoneTheme.accent() : PhoneTheme.card();
            PhoneDraw.roundCornersMask(g, x, y, x + mw, y + mh, mw / 2, bubble);
            String label = tr("telegram.video_note") + " " + v.videoNote.duration / 60 + ":" + String.format("%02d", v.videoNote.duration % 60);
            PhoneDraw.text(g, label, x, y + mh + 1, own ? PhoneTheme.onAccent() : PhoneTheme.secondaryText(), SMALL, false);
        }
    }

    private void renderInput(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        int top = height() - INPUT_H - 4;
        g.fill(0, top - 4, w, top - 3, PhoneTheme.separator());
        boolean noteButton = VideoNoteRecorder.SIZE > 0 && Telegram.status() == Telegram.Status.READY;
        int ix = noteButton ? 28 : 6;
        input.setBounds(ix, top, w - 30 - ix, INPUT_H);
        input.setPlaceholder(tr("messenger.message_placeholder"));
        input.render(g, mouseX, mouseY);
        if (noteButton) {
            boolean hover = PhoneDraw.inside(mouseX, mouseY, 6, top + 1, 18, 18);
            PhoneDraw.roundedRect(g, 6, top + 1, 24, top + 19, 9, hover ? 0xFFE5534B : PhoneTheme.card());
            PhoneDraw.roundedRect(g, 10, top + 5, 20, top + 15, 5, hover ? 0xFFFFFFFF : 0xFFE5534B);
            hit(6, top + 1, 18, 18, () -> VideoNoteRecorder.start(chatId));
        }
        boolean canSend = !input.text().isBlank();
        int bx = w - 24;
        boolean hovered = PhoneDraw.inside(mouseX, mouseY, bx, top + 1, 18, 18);
        PhoneDraw.roundedRect(g, bx, top + 1, bx + 18, top + 19, 9,
                canSend ? (hovered ? 0xFF5BBDF0 : 0xFF2AABEE) : PhoneTheme.separator());
        PhoneDraw.centeredText(g, "➤", bx + 9, top + 6, 0xFFFFFFFF, 0.9f, false);
        hit(bx, top + 1, 18, 18, this::submit);
    }

    private void submit() {
        String text = input.text().strip();
        if (!text.isEmpty()) {
            Telegram.sendText(chatId, text);
            input.setText("");
            scroll = 0;
        }
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (input.mouseClicked(x, y, button)) {
            return true;
        }
        return super.mouseClicked(x, y, button);
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
        scroll += amount * 20;
        return true;
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && !event.hasShiftDown()) {
            submit();
            return true;
        }
        return input.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return input.charTyped(event);
    }
}
