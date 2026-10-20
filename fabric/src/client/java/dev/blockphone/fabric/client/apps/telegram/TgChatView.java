package dev.blockphone.fabric.client.apps.telegram;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.telegram.Telegram;
import dev.blockphone.fabric.client.telegram.VideoNoteRecorder;
import dev.blockphone.fabric.client.ui.PhotoPicker;
import dev.blockphone.fabric.client.ui.PopupMenu;
import dev.blockphone.fabric.client.ui.TextArea;
import it.tdlight.jni.TdApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
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
 * Переписка Telegram: пузыри (свои — справа), альбомы (несколько фото одним пузырём), ответы и пересланные, реакции,
 * «кружочки» (обложка), подгрузка истории при прокрутке вверх. ПКМ по сообщению — реакции, ответить, копировать,
 * изменить, удалить. Поле ввода (Enter — отправить), «+» — фото из галереи (до 10 — одним альбомом), «◯» — «кружочек».
 */
final class TgChatView extends TgView {
    private static final float TEXT = 0.75f;
    private static final float SMALL = 0.55f;
    private static final int LINE_H = 8;
    private static final int PAD = 5;
    private static final int GAP = 3;
    private static final int INPUT_H = 20;
    private static final int MEDIA_W = 112;
    private static final int NOTE = 64;
    private static final int REPLY_H = 17;
    private static final int CHIP_H = 10;
    private static final int MAX_PHOTOS = 10;
    private static final int[] NAME_COLORS = {0xFFE17076, 0xFFFAA774, 0xFFA695E7, 0xFF7BC862, 0xFF6EC9CB, 0xFF65AADD, 0xFFEE7AAE};
    /** Быстрые реакции в меню сообщения. */
    private static final String[] REACTIONS = {"👍", "❤", "🔥", "😂", "😮", "😢", "👎", "🎉"};
    private static final int REACT_CELL = 14;
    private static final String[] REACTION_ICONS = {"like", "heart", "fire", "laugh", "wow", "sad", "dislike", "party"};

    /** Свой цветной значок реакции (шрифт игры рисует эмодзи мелко и одним цветом) или null. */
    private static @Nullable Identifier reactionIcon(String emoji) {
        String plain = emoji.replace("️", "");
        for (int i = 0; i < REACTIONS.length; i++) {
            if (REACTIONS[i].equals(plain)) {
                return Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/reaction/" + REACTION_ICONS[i] + ".png");
            }
        }
        return null;
    }

    private static void drawEmoji(GuiGraphics g, String emoji, int x, int y, int size, int color) {
        Identifier icon = reactionIcon(emoji);
        if (icon != null) {
            PhoneDraw.texture(g, icon, x, y, size, size, 32, 32);
        } else {
            PhoneDraw.text(g, emoji, x, y + 1, color, size / 9f, false);
        }
    }

    /** Картинка внутри пузыря: смещение относительно начала области медиа. */
    private record Media(@Nullable TdApi.File file, int dx, int dy, int w, int h, String badge) {
    }

    private record Chip(String emoji, int count, boolean chosen, int dx, int dy, int w) {
    }

    private record Item(List<TdApi.Message> messages, TdApi.Message main, String text, List<FormattedCharSequence> lines,
                        @Nullable String name, @Nullable String forward, @Nullable String replyName, @Nullable String replyText,
                        String time, List<Media> media, int mediaH, boolean note, List<Chip> chips, int chipsH,
                        int width, int height) {
    }

    private record Bubble(int x, int y, int w, int h, Item item) {
    }

    private final long chatId;
    private final TextArea input = new TextArea(true, TEXT);
    private final Map<String, List<FormattedCharSequence>> wrapCache = new HashMap<>();
    private final List<PhotoStore.Photo> photos = new ArrayList<>();
    private final PhotoPicker picker = new PhotoPicker(this::togglePhoto, true, photos::contains);
    private final List<Bubble> bubbles = new ArrayList<>();
    private PopupMenu menu = new PopupMenu();
    private @Nullable TdApi.Message menuTarget;
    private int reactX;
    private int reactY;
    /** Ответ на это сообщение (0 — нет). */
    private long replyTo;
    /** Правится это сообщение (0 — нет). */
    private long editing;

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

    private int inputTop() {
        return height() - INPUT_H - 4;
    }

    /** Высота полос над полем ввода: ответ/правка и выбранные фото. */
    private int barsHeight() {
        return (replyTo != 0 || editing != 0 ? 18 : 0) + (photos.isEmpty() ? 0 : 30);
    }

    private int areaBottom() {
        return inputTop() - 4 - barsHeight();
    }

    private int maxInner() {
        return (int) (width() * 0.72f) - PAD * 2;
    }

    private List<FormattedCharSequence> wrap(TdApi.Message m, String text) {
        return wrapCache.computeIfAbsent(m.id + ":" + text.hashCode(),
                k -> PhoneDraw.font().split(FormattedText.of(text), (int) (maxInner() / TEXT)));
    }

    private static String bodyText(TdApi.Message m) {
        return switch (m.content) {
            case TdApi.MessageText t -> t.text.text;
            case TdApi.MessagePhoto p -> p.caption.text;
            case TdApi.MessageVideo v -> v.caption.text;
            case TdApi.MessageAnimation a -> a.caption.text;
            case TdApi.MessageVideoNote v -> "";
            default -> Telegram.text(m);
        };
    }

    /** Сообщения подряд с одним mediaAlbumId — один альбом. */
    private static List<List<TdApi.Message>> groups(List<TdApi.Message> list) {
        List<List<TdApi.Message>> result = new ArrayList<>();
        for (TdApi.Message m : list) {
            List<TdApi.Message> last = result.isEmpty() ? null : result.get(result.size() - 1);
            if (last != null && m.mediaAlbumId != 0 && last.get(0).mediaAlbumId == m.mediaAlbumId) {
                last.add(m);
            } else {
                List<TdApi.Message> group = new ArrayList<>();
                group.add(m);
                result.add(group);
            }
        }
        return result;
    }

    private List<Item> layout() {
        boolean group = group();
        int maxInner = maxInner();
        List<Item> items = new ArrayList<>();
        long prevSender = Long.MIN_VALUE;
        for (List<TdApi.Message> messages : groups(Telegram.messages(chatId).list)) {
            TdApi.Message first = messages.get(0);
            TdApi.Message main = first;
            String text = "";
            for (TdApi.Message m : messages) {
                String t = bodyText(m);
                if (!t.isEmpty()) {
                    main = m;
                    text = t;
                    break;
                }
            }
            List<FormattedCharSequence> lines = text.isEmpty() ? List.of() : wrap(main, text);
            long sender = senderKey(first);
            String name = group && !first.isOutgoing && sender != prevSender ? Telegram.senderName(first) : null;
            prevSender = sender;
            String forward = first.forwardInfo != null ? tr("telegram.forwarded", Telegram.originName(first.forwardInfo.origin)) : null;
            String replyName = null;
            String replyText = null;
            if (first.replyTo instanceof TdApi.MessageReplyToMessage r) {
                TdApi.Message replied = r.messageId != 0 ? Telegram.message(r.chatId != 0 ? r.chatId : chatId, r.messageId) : null;
                if (replied != null) {
                    replyName = Telegram.senderName(replied);
                    replyText = Telegram.text(replied);
                } else {
                    replyName = r.origin != null ? Telegram.originName(r.origin) : "…";
                    replyText = r.content != null ? Telegram.text(r.content) : (r.quote != null ? r.quote.text.text : "");
                }
                replyText = replyText.replace('\n', ' ');
            }
            String time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochSecond(first.date).atZone(ZoneId.systemDefault()));
            if (main.editDate > 0) {
                time = tr("telegram.edited") + " " + time;
            }
            List<Media> media = new ArrayList<>();
            int mediaH = mediaLayout(messages, media);
            boolean note = first.content instanceof TdApi.MessageVideoNote;
            float contentW = 0;
            for (FormattedCharSequence line : lines) {
                contentW = Math.max(contentW, PhoneDraw.font().width(line) * TEXT);
            }
            contentW = Math.max(contentW, PhoneDraw.width(time, SMALL));
            if (name != null) {
                contentW = Math.max(contentW, PhoneDraw.width(name, SMALL));
            }
            if (forward != null) {
                contentW = Math.max(contentW, Math.min(maxInner, PhoneDraw.width(forward, SMALL)));
            }
            if (replyName != null) {
                contentW = Math.max(contentW, Math.min(maxInner, 6 + Math.max(PhoneDraw.width(replyName, SMALL), PhoneDraw.width(replyText, SMALL))));
            }
            for (Media m : media) {
                contentW = Math.max(contentW, m.dx() + m.w());
            }
            List<Chip> chips = new ArrayList<>();
            int chipsH = chipLayout(main, (int) Math.max(contentW, Math.min(maxInner, 60)), chips);
            for (Chip c : chips) {
                contentW = Math.max(contentW, c.dx() + c.w());
            }
            int width = (int) Math.ceil(contentW) + PAD * 2;
            int height = 3 + (name != null ? 7 : 0) + (forward != null ? 7 : 0) + (replyName != null ? REPLY_H + 2 : 0)
                    + (mediaH > 0 ? mediaH + 3 : 0) + lines.size() * LINE_H + chipsH + 9;
            items.add(new Item(messages, main, text, lines, name, forward, replyName, replyText, time, media, mediaH, note,
                    chips, chipsH, width, height));
        }
        return items;
    }

    private static long senderKey(TdApi.Message m) {
        return m.senderId instanceof TdApi.MessageSenderUser u ? u.userId
                : m.senderId instanceof TdApi.MessageSenderChat c ? c.chatId : 0;
    }

    /** Раскладка картинок: одна — по пропорциям, несколько — сеткой по 2 (при нечётном числе первая — во всю ширину). */
    private static int mediaLayout(List<TdApi.Message> messages, List<Media> out) {
        List<Media> raw = new ArrayList<>();
        for (TdApi.Message m : messages) {
            switch (m.content) {
                case TdApi.MessagePhoto p -> {
                    TdApi.PhotoSize size = bestSize(p.photo);
                    if (size != null) {
                        raw.add(new Media(size.photo, 0, 0, size.width, size.height, ""));
                    }
                }
                case TdApi.MessageVideo v -> raw.add(new Media(thumb(v.video.thumbnail), 0, 0, v.video.width, v.video.height, "▶"));
                case TdApi.MessageAnimation a -> raw.add(new Media(thumb(a.animation.thumbnail), 0, 0,
                        a.animation.width, a.animation.height, "GIF"));
                case TdApi.MessageVideoNote v -> {
                    out.add(new Media(v.videoNote.thumbnail != null ? v.videoNote.thumbnail.file : null, 0, 0, NOTE, NOTE, ""));
                    return NOTE + 8;
                }
                default -> {
                }
            }
        }
        if (raw.isEmpty()) {
            return 0;
        }
        if (raw.size() == 1) {
            Media m = raw.get(0);
            int h = Math.round(MEDIA_W * Math.max(0.5f, Math.min(1.3f, m.h() / (float) Math.max(1, m.w()))));
            out.add(new Media(m.file(), 0, 0, MEDIA_W, h, m.badge()));
            return h;
        }
        int cell = (MEDIA_W - 2) / 2;
        int y = 0;
        int i = 0;
        if (raw.size() % 2 == 1) {
            Media m = raw.get(0);
            out.add(new Media(m.file(), 0, 0, MEDIA_W, 70, m.badge()));
            y = 72;
            i = 1;
        }
        for (; i < raw.size(); i += 2) {
            for (int k = 0; k < 2 && i + k < raw.size(); k++) {
                Media m = raw.get(i + k);
                out.add(new Media(m.file(), k * (cell + 2), y, cell, cell, m.badge()));
            }
            y += cell + 2;
        }
        return y - 2;
    }

    private static @Nullable TdApi.File thumb(@Nullable TdApi.Thumbnail t) {
        return t != null && (t.format instanceof TdApi.ThumbnailFormatJpeg || t.format instanceof TdApi.ThumbnailFormatPng) ? t.file : null;
    }

    /** Реакции под текстом — «таблетки» с эмодзи и числом, переносятся по ширине пузыря. */
    private static int chipLayout(TdApi.Message m, int maxW, List<Chip> out) {
        if (m.interactionInfo == null || m.interactionInfo.reactions == null) {
            return 0;
        }
        int x = 0;
        int y = 0;
        for (TdApi.MessageReaction r : m.interactionInfo.reactions.reactions) {
            if (!(r.type instanceof TdApi.ReactionTypeEmoji e)) {
                continue;
            }
            int w = 8 + (int) PhoneDraw.width(String.valueOf(r.totalCount), SMALL) + 8;
            if (x > 0 && x + w > maxW) {
                x = 0;
                y += CHIP_H + 2;
            }
            out.add(new Chip(e.emoji, r.totalCount, r.isChosen, x, y, w));
            x += w + 2;
        }
        return out.isEmpty() ? 0 : y + CHIP_H + 3;
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
        bubbles.clear();
        beginList(g, 0, areaBottom);
        if (!data.loaded) {
            hint(g, tr("messenger.loading"), areaBottom / 2 - 4);
        }
        int y = areaBottom + (int) scroll - total;
        for (Item item : items) {
            if (y + item.height() >= 0 && y <= areaBottom) {
                renderItem(g, item, y, mouseX, mouseY);
            }
            y += item.height() + GAP;
        }
        endList(g);
        renderBars(g, mouseX, mouseY);
        renderInput(g, mouseX, mouseY);
        if (menu.isOpen()) {
            renderReactions(g, mouseX, mouseY);
            menu.render(g, mouseX, mouseY);
        }
        picker.render(g, mouseX, mouseY, width(), height());
    }

    private void renderItem(GuiGraphics g, Item item, int y, int mouseX, int mouseY) {
        int w = width();
        TdApi.Message m = item.main();
        boolean own = m.isOutgoing;
        int x = own ? w - 6 - item.width() : 6;
        int bg = own ? PhoneTheme.accent() : PhoneTheme.card();
        if (m.sendingState instanceof TdApi.MessageSendingStatePending) {
            bg = (bg & 0x00FFFFFF) | 0x99000000;
        }
        PhoneDraw.roundedRect(g, x, y, x + item.width(), y + item.height(), 6, bg);
        int top = Math.max(0, y);
        int bottom = Math.min(areaBottom(), y + item.height());
        if (bottom > top) {
            bubbles.add(new Bubble(x, top, item.width(), bottom - top, item));
        }
        int textColor = own ? PhoneTheme.onAccent() : PhoneTheme.text();
        int softColor = own ? (PhoneTheme.onAccent() & 0x00FFFFFF) | 0xC0000000 : PhoneTheme.secondaryText();
        int inner = item.width() - PAD * 2;
        int ty = y + 3;
        if (item.name() != null) {
            PhoneDraw.text(g, item.name(), x + PAD, ty, nameColor(senderKey(item.messages().get(0))), SMALL, false);
            ty += 7;
        }
        if (item.forward() != null) {
            PhoneDraw.text(g, PhoneDraw.ellipsize(item.forward(), inner, SMALL), x + PAD, ty, softColor, SMALL, false);
            ty += 7;
        }
        if (item.replyName() != null) {
            int barColor = own ? PhoneTheme.onAccent() : PhoneTheme.accent();
            g.fill(x + PAD, ty, x + item.width() - PAD, ty + REPLY_H, own ? 0x30FFFFFF : PhoneTheme.accentTint(0x30));
            g.fill(x + PAD, ty, x + PAD + 2, ty + REPLY_H, barColor);
            PhoneDraw.text(g, PhoneDraw.ellipsize(item.replyName(), inner - 6, SMALL), x + PAD + 5, ty + 2, barColor, SMALL, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(item.replyText(), inner - 6, SMALL), x + PAD + 5, ty + 9, textColor, SMALL, false);
            ty += REPLY_H + 2;
        }
        if (item.mediaH() > 0) {
            renderMedia(g, item, x + PAD, ty + 1, own);
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
        for (Chip c : item.chips()) {
            int cx = x + PAD + c.dx();
            int cy = ty + c.dy();
            boolean hover = PhoneDraw.inside(mouseX, mouseY, cx, cy, c.w(), CHIP_H);
            int chipBg = c.chosen() ? (own ? 0xFFFFFFFF : PhoneTheme.accent()) : (own ? 0x40FFFFFF : PhoneTheme.accentTint(0x40));
            if (hover) {
                chipBg = PhoneTheme.mix(chipBg, 0xFFFFFFFF, 0.2f);
            }
            int chipFg = c.chosen() ? (own ? PhoneTheme.accent() : PhoneTheme.onAccent()) : textColor;
            PhoneDraw.roundedRect(g, cx, cy, cx + c.w(), cy + CHIP_H, CHIP_H / 2, chipBg);
            drawEmoji(g, c.emoji(), cx + 2, cy + 1, 8, chipFg);
            PhoneDraw.text(g, String.valueOf(c.count()), cx + 12, cy + 2, chipFg, SMALL, false);
            long id = m.id;
            hit(cx, cy, c.w(), CHIP_H, () -> Telegram.toggleReaction(chatId, id, c.emoji(), c.chosen()));
        }
        ty += item.chipsH();
        PhoneDraw.rightText(g, item.time(), x + item.width() - PAD, ty + 1, softColor, SMALL);
    }

    private static int nameColor(long sender) {
        return NAME_COLORS[(int) Math.floorMod(sender, (long) NAME_COLORS.length)];
    }

    private void renderMedia(GuiGraphics g, Item item, int x, int y, boolean own) {
        for (Media media : item.media()) {
            int mx = x + media.dx();
            int my = y + media.dy();
            Path path = Telegram.localFile(media.file());
            PhotoTextures.Image image = path != null ? PhotoTextures.preview(new PhotoStore.Photo(path, 0), 320) : null;
            if (image != null) {
                PhoneDraw.textureCover(g, image.id(), mx, my, media.w(), media.h(), image.width(), image.height());
            } else {
                g.fill(mx, my, mx + media.w(), my + media.h(), PhoneTheme.dark() ? 0xFF1F2738 : 0xFFDCE3EE);
                PhoneDraw.centeredText(g, "...", mx + media.w() / 2f, my + media.h() / 2f - 3, PhoneTheme.secondaryText(), 0.75f, false);
            }
            if (!media.badge().isEmpty()) {
                int bw = (int) PhoneDraw.width(media.badge(), SMALL) + 6;
                PhoneDraw.roundedRect(g, mx + 2, my + 2, mx + 2 + bw, my + 11, 4, 0xA0000000);
                PhoneDraw.text(g, media.badge(), mx + 5, my + 4, 0xFFFFFFFF, SMALL, false);
            }
        }
        if (item.note() && item.main().content instanceof TdApi.MessageVideoNote v) {
            // Круглая рамка «кружочка» поверх обложки (углы закрашены цветом пузыря).
            int bubble = own ? PhoneTheme.accent() : PhoneTheme.card();
            PhoneDraw.roundCornersMask(g, x, y, x + NOTE, y + NOTE, NOTE / 2, bubble);
            String label = tr("telegram.video_note") + " " + v.videoNote.duration / 60 + ":" + String.format("%02d", v.videoNote.duration % 60);
            PhoneDraw.text(g, label, x, y + NOTE + 1, own ? PhoneTheme.onAccent() : PhoneTheme.secondaryText(), SMALL, false);
        }
    }

    /** Полосы над полем: «Ответ …» / «Изменение» с крестиком и выбранные фото. */
    private void renderBars(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        int y = areaBottom();
        if (barsHeight() > 0) {
            g.fill(0, y, w, y + barsHeight() + 4, PhoneTheme.background());
        }
        g.fill(0, y, w, y + 1, PhoneTheme.separator());
        y += 3;
        if (replyTo != 0 || editing != 0) {
            TdApi.Message target = Telegram.message(chatId, editing != 0 ? editing : replyTo);
            String title = editing != 0 ? tr("telegram.editing") : tr("telegram.reply_to", target != null ? Telegram.senderName(target) : "…");
            String text = target != null ? Telegram.text(target).replace('\n', ' ') : "";
            g.fill(6, y, 8, y + 15, PhoneTheme.accent());
            PhoneDraw.text(g, PhoneDraw.ellipsize(title, w - 40, SMALL), 11, y + 1, PhoneTheme.accent(), SMALL, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(text, w - 40, SMALL), 11, y + 8, PhoneTheme.secondaryText(), SMALL, false);
            button(g, w - 20, y + 1, 14, 13, "×", mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, this::cancelBar);
            y += 18;
        }
        if (!photos.isEmpty()) {
            int x = 6;
            for (PhotoStore.Photo photo : List.copyOf(photos)) {
                PhotoTextures.Image thumb = PhotoTextures.thumbnail(photo);
                if (thumb != null) {
                    PhoneDraw.textureCover(g, thumb.id(), x, y, 26, 26, thumb.width(), thumb.height());
                } else {
                    g.fill(x, y, x + 26, y + 26, PhoneTheme.card());
                }
                PhoneDraw.roundedRect(g, x + 17, y + 1, x + 25, y + 9, 4, 0xC0000000);
                PhoneDraw.centeredText(g, "×", x + 21, y + 1, 0xFFFFFFFF, 0.6f, false);
                hit(x + 15, y, 11, 11, () -> photos.remove(photo));
                x += 28;
                if (x + 26 > w - 26) {
                    break;
                }
            }
            PhoneDraw.rightText(g, photos.size() + "/" + MAX_PHOTOS, w - 6, y + 10, PhoneTheme.secondaryText(), SMALL);
        }
    }

    private void renderInput(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        int top = inputTop();
        g.fill(0, top - 4, w, top - 3, PhoneTheme.separator());
        boolean ready = Telegram.status() == Telegram.Status.READY;
        boolean noteButton = VideoNoteRecorder.SIZE > 0 && ready && editing == 0;
        int ix = 6;
        if (ready && editing == 0) {
            boolean hover = PhoneDraw.inside(mouseX, mouseY, ix, top + 1, 18, 18);
            PhoneDraw.roundedRect(g, ix, top + 1, ix + 18, top + 19, 9, hover ? PhoneTheme.cardHover() : PhoneTheme.card());
            PhoneDraw.centeredText(g, "+", ix + 9, top + 5, PhoneTheme.accent(), 1f, false);
            hit(ix, top + 1, 18, 18, picker::open);
            ix += 20;
        }
        if (noteButton) {
            boolean hover = PhoneDraw.inside(mouseX, mouseY, ix, top + 1, 18, 18);
            PhoneDraw.roundedRect(g, ix, top + 1, ix + 18, top + 19, 9, hover ? 0xFFE5534B : PhoneTheme.card());
            PhoneDraw.roundedRect(g, ix + 4, top + 5, ix + 14, top + 15, 5, hover ? 0xFFFFFFFF : 0xFFE5534B);
            hit(ix, top + 1, 18, 18, () -> VideoNoteRecorder.start(chatId));
            ix += 20;
        }
        input.setBounds(ix, top, w - 28 - ix, INPUT_H);
        input.setPlaceholder(tr(photos.isEmpty() ? "messenger.message_placeholder" : "telegram.caption_placeholder"));
        input.render(g, mouseX, mouseY);
        boolean canSend = !input.text().isBlank() || (!photos.isEmpty() && editing == 0);
        int bx = w - 24;
        boolean hovered = PhoneDraw.inside(mouseX, mouseY, bx, top + 1, 18, 18);
        PhoneDraw.roundedRect(g, bx, top + 1, bx + 18, top + 19, 9,
                canSend ? (hovered ? 0xFF5BBDF0 : 0xFF2AABEE) : PhoneTheme.separator());
        PhoneDraw.centeredText(g, editing != 0 ? "✔" : "➤", bx + 9, top + 6, 0xFFFFFFFF, 0.9f, false);
        hit(bx, top + 1, 18, 18, this::submit);
    }

    /** Полоса быстрых реакций над меню сообщения. */
    private void renderReactions(GuiGraphics g, int mouseX, int mouseY) {
        int w = REACTIONS.length * REACT_CELL + 4;
        PhoneDraw.roundedRect(g, reactX - 1, reactY, reactX + w + 1, reactY + REACT_CELL + 4, 7, 0x40000000);
        PhoneDraw.roundedRect(g, reactX, reactY, reactX + w, reactY + REACT_CELL + 2, 7, PhoneTheme.card());
        for (int i = 0; i < REACTIONS.length; i++) {
            int cx = reactX + 2 + i * REACT_CELL;
            if (PhoneDraw.inside(mouseX, mouseY, cx, reactY + 1, REACT_CELL, REACT_CELL)) {
                PhoneDraw.roundedRect(g, cx, reactY + 1, cx + REACT_CELL, reactY + 1 + REACT_CELL, 7, PhoneTheme.cardHover());
            }
            if (chosen(menuTarget, REACTIONS[i])) {
                PhoneDraw.roundedRect(g, cx, reactY + 1, cx + REACT_CELL, reactY + 1 + REACT_CELL, 7, PhoneTheme.accentTint(0x60));
            }
            drawEmoji(g, REACTIONS[i], cx + 2, reactY + 3, REACT_CELL - 4, PhoneTheme.text());
        }
    }

    private static boolean chosen(@Nullable TdApi.Message m, String emoji) {
        if (m == null || m.interactionInfo == null || m.interactionInfo.reactions == null) {
            return false;
        }
        for (TdApi.MessageReaction r : m.interactionInfo.reactions.reactions) {
            if (r.isChosen && r.type instanceof TdApi.ReactionTypeEmoji e && e.emoji.equals(emoji)) {
                return true;
            }
        }
        return false;
    }

    private void openMenu(Item item, int mx, int my) {
        TdApi.Message m = item.main();
        long[] ids = item.messages().stream().mapToLong(x -> x.id).toArray();
        menuTarget = m;
        menu = new PopupMenu();
        menu.add(tr("telegram.reply"), () -> {
            editing = 0;
            replyTo = item.messages().get(0).id;
            input.setFocused(true);
        });
        if (!item.text().isEmpty()) {
            menu.add(tr("telegram.copy"), () -> Minecraft.getInstance().keyboardHandler.setClipboard(item.text()));
        }
        if (m.isOutgoing && m.content instanceof TdApi.MessageText t) {
            menu.add(tr("telegram.edit"), () -> {
                replyTo = 0;
                photos.clear();
                editing = m.id;
                input.setText(t.text.text);
                input.setFocused(true);
            });
        }
        menu.addDanger(tr("telegram.delete"), () -> app.showDialog(PhoneDialog.confirmDelete(tr("telegram.delete"),
                tr(m.isOutgoing ? "telegram.delete_all" : "telegram.delete_me"),
                () -> Telegram.deleteMessages(chatId, ids, m.isOutgoing))));
        int stripW = REACTIONS.length * REACT_CELL + 4;
        reactX = Math.max(4, Math.min(width() - stripW - 4, mx - stripW / 2));
        reactY = Math.max(2, Math.min(height() - 90, my - REACT_CELL - 6));
        menu.show(mx, reactY + REACT_CELL + 6, width(), height());
    }

    private void togglePhoto(PhotoStore.Photo photo) {
        if (!photos.remove(photo) && photos.size() < MAX_PHOTOS) {
            photos.add(photo);
        }
    }

    private void cancelBar() {
        if (editing != 0) {
            input.setText("");
        }
        editing = 0;
        replyTo = 0;
    }

    private void submit() {
        String text = input.text().strip();
        if (editing != 0) {
            if (!text.isEmpty()) {
                Telegram.editText(chatId, editing, text);
            }
            editing = 0;
            input.setText("");
            return;
        }
        if (!photos.isEmpty()) {
            Telegram.sendPhotos(chatId, photos.stream().map(PhotoStore.Photo::file).toList(), text, replyTo);
            photos.clear();
        } else if (!text.isEmpty()) {
            Telegram.sendText(chatId, text, replyTo);
        } else {
            return;
        }
        replyTo = 0;
        input.setText("");
        scroll = 0;
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (picker.isOpen()) {
            return picker.mouseClicked(x, y, button);
        }
        if (menu.isOpen()) {
            int stripW = REACTIONS.length * REACT_CELL + 4;
            if (PhoneDraw.inside(x, y, reactX, reactY, stripW, REACT_CELL + 2) && menuTarget != null) {
                int i = Math.max(0, Math.min(REACTIONS.length - 1, (int) ((x - reactX - 2) / REACT_CELL)));
                Telegram.toggleReaction(chatId, menuTarget.id, REACTIONS[i], chosen(menuTarget, REACTIONS[i]));
                menu.close();
                return true;
            }
            return menu.mouseClicked(x, y);
        }
        if (button == 1) {
            for (Bubble b : bubbles) {
                if (PhoneDraw.inside(x, y, b.x(), b.y(), b.w(), b.h())) {
                    openMenu(b.item(), (int) x, (int) y);
                    return true;
                }
            }
            return false;
        }
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
        if (picker.mouseScrolled(amount)) {
            return true;
        }
        if (input.mouseScrolled(x, y, amount)) {
            return true;
        }
        scroll += amount * 20;
        return true;
    }

    @Override
    boolean onBack() {
        if (picker.isOpen()) {
            picker.close();
            return true;
        }
        if (menu.isOpen()) {
            menu.close();
            return true;
        }
        if (replyTo != 0 || editing != 0) {
            cancelBar();
            return true;
        }
        return false;
    }

    void debug(String what) {
        if (what.equals("menu") && !bubbles.isEmpty()) {
            Bubble b = bubbles.get(bubbles.size() - 3 >= 0 ? bubbles.size() - 3 : 0);
            openMenu(b.item(), b.x() + b.w() / 2, b.y() + 4);
        } else if (what.equals("reply")) {
            menu.close();
            replyTo = 1;
            List<PhotoStore.Photo> all = PhotoStore.list();
            for (int i = 0; i < Math.min(2, all.size()); i++) {
                photos.add(all.get(i));
            }
        }
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
