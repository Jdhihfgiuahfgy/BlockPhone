package dev.blockphone.fabric.client.apps.telegram;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.telegram.MediaFrames;
import dev.blockphone.fabric.client.telegram.OggOpus;
import dev.blockphone.fabric.client.telegram.Telegram;
import dev.blockphone.fabric.client.telegram.TelegramVoice;
import dev.blockphone.fabric.client.telegram.VideoNoteRecorder;
import dev.blockphone.fabric.client.voice.VoiceMessages;
import dev.blockphone.fabric.client.voice.VoiceRecorder;
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
 * стикеры (без пузыря), GIF и «кружочки» (анимация из MP4), голосовые («волна», проигрывание), подгрузка истории при
 * прокрутке вверх. ПКМ по сообщению — реакции, ответить, копировать, изменить, удалить. Поле ввода (Enter — отправить),
 * «+» — фото из галереи (до 10 — одним альбомом) или «кружочек», «☺» — панель стикеров и GIF, микрофон — голосовое.
 */
final class TgChatView extends TgView {
    private static final float TEXT = 0.75f;
    private static final float SMALL = 0.55f;
    private static final int LINE_H = 8;
    private static final int PAD = 5;
    private static final int GAP = 3;
    private static final int INPUT_H = 20;
    private static final int MEDIA_W = 112;
    /** Размер «кружочка» в переписке (как в Telegram — крупный круг без пузыря). */
    private static final int NOTE = 100;
    private static final int REPLY_H = 17;
    private static final int CHIP_H = 10;
    private static final int MAX_PHOTOS = 10;
    private static final int STICKER = 80;
    private static final int VOICE_H = 18;
    private static final int PANEL_H = 120;
    private static final int TAB = 18;
    private static final int MAX_VOICE_SECONDS = 300;
    private static final int KIND_NORMAL = 0;
    private static final int KIND_STICKER = 1;
    private static final int[] NAME_COLORS = {0xFFE17076, 0xFFFAA774, 0xFFA695E7, 0xFF7BC862, 0xFF6EC9CB, 0xFF65AADD, 0xFFEE7AAE};
    /** Быстрые реакции в меню сообщения. */
    private static final String[] REACTIONS = {"👍", "❤", "🔥", "😂", "😮", "😢", "👎", "🎉"};
    private static final int REACT_CELL = 14;
    private String[] quick = REACTIONS;
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
        Path tgIcon = Telegram.localFile(Telegram.reactionIcon(emoji));
        PhotoTextures.Image image = tgIcon != null ? PhotoTextures.preview(new PhotoStore.Photo(tgIcon, 0), 64) : null;
        if (image != null) {
            PhoneDraw.texture(g, image.id(), x, y, size, size, image.width(), image.height());
            return;
        }
        Identifier icon = reactionIcon(emoji);
        if (icon != null) {
            PhoneDraw.texture(g, icon, x, y, size, size, 32, 32);
        } else {
            PhoneDraw.text(g, emoji, x, y + 1, color, size / 9f, false);
        }
    }

    /** Картинка внутри пузыря: смещение относительно начала области медиа. */
    /** anim — ролик MP4 (GIF, «кружочек»), который показывается анимацией поверх обложки. */
    private record Media(@Nullable TdApi.File file, int dx, int dy, int w, int h, String badge, @Nullable TdApi.File anim) {
    }

    private record Chip(String emoji, int count, boolean chosen, int dx, int dy, int w) {
    }

    private record Item(List<TdApi.Message> messages, TdApi.Message main, String text, List<FormattedCharSequence> lines,
                        @Nullable String name, @Nullable String forward, @Nullable String replyName, @Nullable String replyText,
                        String time, List<Media> media, int mediaH, boolean note, List<Chip> chips, int chipsH,
                        @Nullable TdApi.VoiceNote voice, int kind, int width, int height) {
    }

    /** До какого времени показывать громкость (после колеса над голосовым). */
    private long volumeHintUntil;

    private record Bubble(int x, int y, int w, int h, Item item) {
    }

    private final long chatId;
    private final TextArea input = new TextArea(true, TEXT);
    private final Map<String, List<FormattedCharSequence>> wrapCache = new HashMap<>();
    private final List<PhotoStore.Photo> photos = new ArrayList<>();
    /** Прикреплённые файлы (не картинки) — из буфера обмена; уходят документами. */
    private final List<java.nio.file.Path> files = new ArrayList<>();
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
    /** Панель стикеров и GIF: вкладка 0 — недавние, 1 — GIF, дальше — наборы. */
    private boolean panel;
    private int panelTab;
    private int tabOffset;
    private double panelScroll;
    private @Nullable VoiceRecorder recorder;
    private long recordReplyTo;

    TgChatView(TelegramApp app, long chatId) {
        super(app);
        this.chatId = chatId;
        input.setMaxLength(4096);
    }

    @Override
    protected boolean invertedScroll() {
        return true;
    }

    @Override
    String title() {
        TdApi.Chat chat = Telegram.chat(chatId);
        return chat != null ? Telegram.title(chat) : "Telegram";
    }

    long chatId() {
        return chatId;
    }

    @Override
    String subtitle() {
        TdApi.Chat chat = Telegram.chat(chatId);
        return chat != null ? Telegram.status(chat) : null;
    }

    @Override
    TdApi.Chat headerChat() {
        return Telegram.chat(chatId);
    }

    @Override
    void onTitleClick() {
        app.push(new TgProfileView(app, chatId));
    }

    @Override
    int background() {
        return TgTheme.chatBg();
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
        cancelRecording();
    }

    @Override
    boolean capturesKeyboard() {
        return input.isFocused();
    }

    /** Трубка в шапке личного чата: звонки Telegram в игре невозможны (TDLib не передаёт звук) — объясняем. */
    @Override
    String headerAction() {
        TdApi.Chat chat = Telegram.chat(chatId);
        return chat != null && chat.type instanceof TdApi.ChatTypePrivate ? "☎" : null;
    }

    @Override
    void onHeaderAction() {
        app.showDialog(new PhoneDialog(tr("call.title"), tr("telegram.calls_unavailable"), "OK", false, () -> {
        }));
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
        return (replyTo != 0 || editing != 0 ? 18 : 0) + (photos.isEmpty() && files.isEmpty() ? 0 : 30);
    }

    private int areaBottom() {
        return inputTop() - 4 - barsHeight() - (panel ? PANEL_H : 0);
    }

    private int maxInner() {
        return (int) (width() * 0.72f) - PAD * 2;
    }

    private List<FormattedCharSequence> wrap(TdApi.Message m, String text) {
        return wrapCache.computeIfAbsent(m.id + ":" + text.hashCode(),
                k -> PhoneDraw.font().split(PhoneDraw.styled(text), (int) (maxInner() / TEXT)));
    }

    private static String bodyText(TdApi.Message m) {
        return switch (m.content) {
            case TdApi.MessageText t -> t.text.text;
            case TdApi.MessagePhoto p -> p.caption.text;
            case TdApi.MessageVideo v -> v.caption.text;
            case TdApi.MessageAnimation a -> a.caption.text;
            case TdApi.MessageVideoNote v -> "";
            case TdApi.MessageSticker v -> "";
            case TdApi.MessageVoiceNote v -> v.caption.text;
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
            TdApi.VoiceNote voice = first.content instanceof TdApi.MessageVoiceNote v ? v.voiceNote : null;
            int kind = first.content instanceof TdApi.MessageSticker ? KIND_STICKER : KIND_NORMAL;
            float contentW = voice != null ? 100 : 0;
            for (FormattedCharSequence line : lines) {
                contentW = Math.max(contentW, PhoneDraw.font().width(line) * TEXT);
            }
            contentW = Math.max(contentW, PhoneDraw.width(time, SMALL) + (first.isOutgoing ? 10 : 0));
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
                    + (mediaH > 0 ? mediaH + 3 : 0) + (voice != null ? VOICE_H + 2 : 0) + lines.size() * LINE_H + chipsH + 9;
            items.add(new Item(messages, main, text, lines, name, forward, replyName, replyText, time, media, mediaH, note,
                    chips, chipsH, voice, kind, width, height));
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
                        raw.add(new Media(size.photo, 0, 0, size.width, size.height, "", null));
                    }
                }
                case TdApi.MessageVideo v -> raw.add(new Media(thumb(v.video.thumbnail), 0, 0, v.video.width, v.video.height, "▶", null));
                case TdApi.MessageAnimation a -> raw.add(new Media(thumb(a.animation.thumbnail), 0, 0,
                        a.animation.width, a.animation.height, "GIF", a.animation.animation));
                case TdApi.MessageVideoNote v -> {
                    out.add(new Media(v.videoNote.thumbnail != null ? v.videoNote.thumbnail.file : null, 0, 0, NOTE, NOTE, "",
                            v.videoNote.video));
                    return NOTE + 2;
                }
                case TdApi.MessageSticker st -> {
                    // Без пузыря: квадрат STICKER, картинка вписана по пропорциям.
                    float k = Math.min(STICKER / (float) Math.max(1, st.sticker.width), STICKER / (float) Math.max(1, st.sticker.height));
                    int w = Math.max(16, Math.round(st.sticker.width * k));
                    int h = Math.max(16, Math.round(st.sticker.height * k));
                    out.add(new Media(Telegram.stickerImage(st.sticker), 0, 0, w, h, "", null));
                    return h;
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
            out.add(new Media(m.file(), 0, 0, MEDIA_W, h, m.badge(), m.anim()));
            return h;
        }
        int cell = (MEDIA_W - 2) / 2;
        int y = 0;
        int i = 0;
        if (raw.size() % 2 == 1) {
            Media m = raw.get(0);
            out.add(new Media(m.file(), 0, 0, MEDIA_W, 70, m.badge(), m.anim()));
            y = 72;
            i = 1;
        }
        for (; i < raw.size(); i += 2) {
            for (int k = 0; k < 2 && i + k < raw.size(); k++) {
                Media m = raw.get(i + k);
                out.add(new Media(m.file(), k * (cell + 2), y, cell, cell, m.badge(), m.anim()));
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

    /** Размер фото не меньше 640 px по ширине (или самый большой из имеющихся). */
    private static TdApi.PhotoSize bestSize(TdApi.Photo photo) {
        TdApi.PhotoSize best = null;
        for (TdApi.PhotoSize s : photo.sizes) {
            if (best == null || (best.width < 640 && s.width > best.width) || (s.width >= 640 && s.width < best.width)) {
                best = s;
            }
        }
        return best;
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        TelegramVoice.tick();
        if (recorder != null && recorder.finished() && recorder.full()) {
            sendRecording(); // дошли до предела длины — отправляем сами
        }
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
        if (panel) {
            renderPanel(g, mouseX, mouseY, inputTop() - 4 - PANEL_H);
        }
        renderInput(g, mouseX, mouseY);
        if (menu.isOpen()) {
            if (menuTarget != null) {
                renderReactions(g, mouseX, mouseY);
            }
            menu.render(g, mouseX, mouseY);
        }
        picker.render(g, mouseX, mouseY, width(), height());
    }

    private void renderItem(GuiGraphics g, Item item, int y, int mouseX, int mouseY) {
        int w = width();
        TdApi.Message m = item.main();
        boolean own = m.isOutgoing;
        int x = own ? w - 6 - item.width() : 6;
        int bg = own ? TgTheme.outBubble() : TgTheme.inBubble();
        // Стикеры и «кружочки» — без пузыря.
        boolean sticker = item.kind() == KIND_STICKER || item.note();
        if (!sticker) {
            // Пузырь как в Telegram: скругление 8 и «хвостик» у нижнего угла (у своих — справа).
            PhoneDraw.rawRoundedRect(g, x, y, x + item.width(), y + item.height(), 8, bg);
            int by = y + item.height();
            if (own) {
                g.fill(x + item.width() - 6, by - 6, x + item.width(), by, bg);
                for (int i = 0; i < 4; i++) {
                    g.fill(x + item.width(), by - 4 + i, x + item.width() + 4 - i, by - 3 + i, bg);
                }
            } else {
                g.fill(x, by - 6, x + 6, by, bg);
                for (int i = 0; i < 4; i++) {
                    g.fill(x - 4 + i, by - 4 + i, x, by - 3 + i, bg);
                }
            }
        }
        int top = Math.max(0, y);
        int bottom = Math.min(areaBottom(), y + item.height());
        if (bottom > top) {
            bubbles.add(new Bubble(x, top, item.width(), bottom - top, item));
        }
        int textColor = TgTheme.text();
        int softColor = own && !sticker ? TgTheme.outMeta() : TgTheme.secondaryText();
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
            int barColor = own ? TgTheme.onAccent() : TgTheme.accent();
            g.fill(x + PAD, ty, x + item.width() - PAD, ty + REPLY_H, own ? 0x30FFFFFF : TgTheme.accentTint(0x30));
            g.fill(x + PAD, ty, x + PAD + 2, ty + REPLY_H, barColor);
            PhoneDraw.text(g, PhoneDraw.ellipsize(item.replyName(), inner - 6, SMALL), x + PAD + 5, ty + 2, barColor, SMALL, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(item.replyText(), inner - 6, SMALL), x + PAD + 5, ty + 9, textColor, SMALL, false);
            ty += REPLY_H + 2;
        }
        if (item.mediaH() > 0) {
            renderMedia(g, item, x + (sticker && own ? item.width() - PAD - item.media().get(0).w() : PAD), ty + 1, own);
            ty += item.mediaH() + 3;
        }
        if (item.voice() != null) {
            renderVoice(g, item.voice(), x + PAD, ty, item.width() - PAD * 2, own, mouseX, mouseY);
            ty += VOICE_H + 2;
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
            int chipBg = c.chosen() ? (own ? 0xFFFFFFFF : TgTheme.accent()) : (own ? 0x40FFFFFF : TgTheme.accentTint(0x40));
            if (hover) {
                chipBg = TgTheme.mix(chipBg, 0xFFFFFFFF, 0.2f);
            }
            int chipFg = c.chosen() ? (own ? TgTheme.accent() : TgTheme.onAccent()) : textColor;
            PhoneDraw.roundedRect(g, cx, cy, cx + c.w(), cy + CHIP_H, CHIP_H / 2, chipBg);
            drawEmoji(g, c.emoji(), cx + 2, cy + 1, 8, chipFg);
            PhoneDraw.text(g, String.valueOf(c.count()), cx + 12, cy + 2, chipFg, SMALL, false);
            long id = m.id;
            hit(cx, cy, c.w(), CHIP_H, () -> Telegram.toggleReaction(chatId, id, c.emoji(), c.chosen()));
        }
        ty += item.chipsH();
        if (sticker) {
            int tw = (int) PhoneDraw.width(item.time(), SMALL) + 6;
            int tx = x + item.width() - PAD - tw;
            PhoneDraw.roundedRect(g, tx, ty - 1, tx + tw, ty + 7, 4, TgTheme.accentTint(0x30));
            PhoneDraw.text(g, item.time(), tx + 3, ty + 1, TgTheme.secondaryText(), SMALL, false);
            return;
        }
        // Время и статус своего сообщения: ◷ отправляется, ! не отправлено, ✓ отправлено, ✓✓ прочитано.
        float right = x + item.width() - PAD;
        if (own) {
            String checks = m.sendingState instanceof TdApi.MessageSendingStateFailed ? "!"
                    : m.sendingState != null ? "◷" : Telegram.read(m) ? "✓✓" : "✓";
            int checkColor = checks.equals("!") ? TgTheme.danger() : TgTheme.check();
            float cw = PhoneDraw.width(checks, SMALL);
            PhoneDraw.text(g, checks, right - cw, ty + 1, checkColor, SMALL, false);
            right -= cw + 2;
        }
        PhoneDraw.rightText(g, item.time(), right, ty + 1, softColor, SMALL);
    }

    private static int nameColor(long sender) {
        return NAME_COLORS[(int) Math.floorMod(sender, (long) NAME_COLORS.length)];
    }

    private void renderMedia(GuiGraphics g, Item item, int x, int y, boolean own) {
        for (Media media : item.media()) {
            int mx = x + media.dx();
            int my = y + media.dy();
            int mediaIndex = item.media().indexOf(media);
            if (TgMediaViewer.viewable(item.messages().get(Math.min(mediaIndex, item.messages().size() - 1)))) {
                hit(mx, my, media.w(), media.h(), () -> app.openViewer(item.messages(), mediaIndex));
            }
            boolean note = item.note();
            Path path = Telegram.localFile(media.file());
            // «Кружочек» рисуется только круглыми кадрами (у квадратной обложки были бы видны углы).
            PhotoTextures.Image image = path != null && !note ? PhotoTextures.preview(new PhotoStore.Photo(path, 0), 640) : null;
            boolean animated = false;
            if (media.anim() != null) {
                // GIF и «кружочки» — анимацией (ролик скачивается сам; не вышло декодировать — обложка).
                Path video = Telegram.localFile(media.anim());
                PhotoTextures.Image frame = video != null ? MediaFrames.frame(video, note ? 200 : 224, note) : null;
                if (frame == null && note && video != null) {
                    frame = MediaFrames.poster(video, 200, true);
                }
                if (frame != null) {
                    image = frame;
                    animated = true;
                }
            }
            if (note) {
                if (image != null) {
                    PhoneDraw.texture(g, image.id(), mx, my, media.w(), media.h(), image.width(), image.height());
                } else {
                    PhoneDraw.roundedRect(g, mx, my, mx + media.w(), my + media.h(), media.w() / 2, TgTheme.dark() ? 0xFF1F2738 : 0xFFDCE3EE);
                    PhoneDraw.centeredText(g, "...", mx + media.w() / 2f, my + media.h() / 2f - 3, TgTheme.secondaryText(), 0.75f, false);
                }
            } else if (image != null) {
                PhoneDraw.textureCover(g, image.id(), mx, my, media.w(), media.h(), image.width(), image.height());
            } else {
                g.fill(mx, my, mx + media.w(), my + media.h(), TgTheme.dark() ? 0xFF1F2738 : 0xFFDCE3EE);
                PhoneDraw.centeredText(g, "...", mx + media.w() / 2f, my + media.h() / 2f - 3, TgTheme.secondaryText(), 0.75f, false);
            }
            if (!media.badge().isEmpty() && !animated) {
                int bw = (int) PhoneDraw.width(media.badge(), SMALL) + 6;
                PhoneDraw.roundedRect(g, mx + 2, my + 2, mx + 2 + bw, my + 11, 4, 0xA0000000);
                PhoneDraw.text(g, media.badge(), mx + 5, my + 4, 0xFFFFFFFF, SMALL, false);
            }
        }
        if (item.note() && item.main().content instanceof TdApi.MessageVideoNote v) {
            // Длительность — «таблеткой» под кругом, как в Telegram.
            String label = v.videoNote.duration / 60 + ":" + String.format("%02d", v.videoNote.duration % 60);
            int lw = (int) PhoneDraw.width(label, SMALL) + 6;
            PhoneDraw.roundedRect(g, x + 2, y + NOTE - 9, x + 2 + lw, y + NOTE - 1, 4, 0xA0000000);
            PhoneDraw.text(g, label, x + 5, y + NOTE - 7, 0xFFFFFFFF, SMALL, false);
        }
    }

    /** Голосовое: кнопка ▶/❚❚, «волна» (проигранная часть ярче) и длительность. */
    private void renderVoice(GuiGraphics g, TdApi.VoiceNote voice, int x, int y, int w, boolean own, int mouseX, int mouseY) {
        boolean playing = TelegramVoice.playing(voice);
        boolean loading = TelegramVoice.loading(voice);
        int button = own ? TgTheme.check() : TgTheme.accent();
        int buttonFg = TgTheme.onAccent();
        PhoneDraw.roundedRect(g, x, y + 1, x + 16, y + 17, 8, button);
        String icon = loading ? "…" : TelegramVoice.failed(voice) ? "!" : playing ? "❚❚" : "▶";
        PhoneDraw.centeredText(g, icon, x + 8, y + 5, buttonFg, playing ? 0.55f : 0.75f, false);
        hit(x, y, w, VOICE_H, () -> TelegramVoice.toggle(voice));
        int[] wave = OggOpus.unpackWaveform(voice.waveform);
        int bars = (w - 22) / 2;
        float progress = TelegramVoice.progress(voice);
        int soft = ((own ? TgTheme.check() : TgTheme.accent()) & 0x00FFFFFF) | 0x60000000;
        int strong = own ? TgTheme.check() : TgTheme.accent();
        for (int i = 0; i < bars; i++) {
            int v = wave.length == 0 ? 6 : wave[Math.min(wave.length - 1, i * wave.length / Math.max(1, bars))];
            int h = 2 + v * 9 / 31;
            int bx = x + 20 + i * 2;
            g.fill(bx, y + 10 - h / 2, bx + 1, y + 10 - h / 2 + h, i < progress * bars ? strong : soft);
        }
        String time = VoiceMessages.time(voice.duration * 1000L);
        PhoneDraw.text(g, time, x + 20, y + 15, own ? TgTheme.outMeta() : TgTheme.secondaryText(), 0.5f, false);
        if (System.currentTimeMillis() < volumeHintUntil) {
            PhoneDraw.rightText(g, tr("telegram.volume") + " " + dev.blockphone.fabric.client.config.PhoneSettings.get().mediaVolume + "%",
                    x + w, y + 15, own ? TgTheme.outMeta() : TgTheme.secondaryText(), 0.5f);
        }
    }

    /** Панель стикеров и GIF над полем: вкладки (недавние, GIF, наборы) и сетка; нажатие — отправить. */
    private void renderPanel(GuiGraphics g, int mouseX, int mouseY, int top) {
        int w = width();
        g.fill(0, top, w, top + PANEL_H, TgTheme.background());
        g.fill(0, top, w, top + 1, TgTheme.separator());
        List<TdApi.StickerSetInfo> sets = Telegram.stickerSets();
        int tabs = 2 + sets.size();
        int visible = (w - 4) / TAB;
        tabOffset = Math.max(0, Math.min(Math.max(0, tabs - visible), tabOffset));
        for (int t = tabOffset; t < Math.min(tabs, tabOffset + visible); t++) {
            int tx = 2 + (t - tabOffset) * TAB;
            int ty = top + 2;
            boolean selected = t == panelTab;
            if (selected || PhoneDraw.inside(mouseX, mouseY, tx, ty, TAB - 1, TAB - 2)) {
                PhoneDraw.roundedRect(g, tx, ty, tx + TAB - 1, ty + TAB - 2, 4, selected ? TgTheme.accentTint(0x50) : TgTheme.cardHover());
            }
            if (t == 0) {
                PhoneDraw.centeredText(g, "◷", tx + TAB / 2f, ty + 4, TgTheme.text(), 0.8f, false);
            } else if (t == 1) {
                PhoneDraw.centeredText(g, "GIF", tx + TAB / 2f, ty + 5, TgTheme.text(), 0.5f, false);
            } else {
                TdApi.StickerSetInfo set = sets.get(t - 2);
                TdApi.File cover = set.covers != null && set.covers.length > 0 ? Telegram.stickerImage(set.covers[0]) : null;
                if (!drawImage(g, cover, tx + 2, ty + 1, TAB - 5, TAB - 5, false)) {
                    PhoneDraw.centeredText(g, set.title.isEmpty() ? "?" : set.title.substring(0, 1), tx + TAB / 2f, ty + 4, TgTheme.text(), 0.7f, false);
                }
            }
            int tab = t;
            hit(tx, ty, TAB - 1, TAB - 2, () -> {
                panelTab = tab;
                panelScroll = 0;
            });
        }
        int gridTop = top + TAB + 2;
        int gridBottom = top + PANEL_H;
        beginList(g, gridTop, gridBottom);
        int y0 = gridTop + 2 - (int) panelScroll;
        int total;
        if (panelTab == 1) {
            List<TdApi.Animation> gifs = Telegram.savedAnimations();
            int cw = (w - 10) / 2;
            int ch = 50;
            for (int i = 0; i < gifs.size(); i++) {
                TdApi.Animation a = gifs.get(i);
                int cx = 4 + (i % 2) * (cw + 2);
                int cy = y0 + (i / 2) * (ch + 2);
                if (cy + ch < gridTop || cy > gridBottom) {
                    continue;
                }
                PhotoTextures.Image poster = null;
                Path video = Telegram.localFile(a.animation);
                if (video != null) {
                    poster = PhoneDraw.inside(mouseX, mouseY, cx, cy, cw, ch) ? MediaFrames.frame(video, 96) : null;
                    if (poster == null) {
                        poster = MediaFrames.poster(video, 96);
                    }
                }
                if (poster != null) {
                    PhoneDraw.textureCover(g, poster.id(), cx, cy, cw, ch, poster.width(), poster.height());
                } else if (!drawImage(g, thumb(a.thumbnail), cx, cy, cw, ch, true)) {
                    g.fill(cx, cy, cx + cw, cy + ch, TgTheme.card());
                    PhoneDraw.centeredText(g, "GIF", cx + cw / 2f, cy + ch / 2f - 3, TgTheme.secondaryText(), 0.6f, false);
                }
                hit(cx, cy, cw, ch, () -> sendFromPanel(() -> Telegram.sendAnimation(chatId, a, replyTo)));
            }
            total = (gifs.size() + 1) / 2 * (ch + 2);
            if (gifs.isEmpty()) {
                hint(g, tr("telegram.no_gifs"), gridTop + 30);
            }
        } else {
            List<TdApi.Sticker> stickers = panelTab == 0 ? Telegram.recentStickers()
                    : panelTab - 2 < sets.size() ? Telegram.stickerSet(sets.get(panelTab - 2).id) : List.of();
            int cell = (w - 8) / 4;
            for (int i = 0; i < stickers.size(); i++) {
                TdApi.Sticker st = stickers.get(i);
                int cx = 4 + (i % 4) * cell;
                int cy = y0 + (i / 4) * cell;
                if (cy + cell < gridTop || cy > gridBottom) {
                    continue;
                }
                if (PhoneDraw.inside(mouseX, mouseY, cx, cy, cell, cell)) {
                    PhoneDraw.roundedRect(g, cx, cy, cx + cell, cy + cell, 4, TgTheme.cardHover());
                }
                if (!drawImage(g, Telegram.stickerImage(st), cx + 2, cy + 2, cell - 4, cell - 4, false)) {
                    PhoneDraw.centeredText(g, st.emoji, cx + cell / 2f, cy + cell / 2f - 4, TgTheme.text(), 1f, false);
                }
                hit(cx, cy, cell, cell, () -> sendFromPanel(() -> Telegram.sendSticker(chatId, st, replyTo)));
            }
            total = (stickers.size() + 3) / 4 * cell;
            if (stickers.isEmpty()) {
                hint(g, tr(panelTab == 0 ? "telegram.no_recent_stickers" : "messenger.loading"), gridTop + 30);
            }
        }
        panelScroll = Math.max(0, Math.min(Math.max(0, total + 4 - (gridBottom - gridTop)), panelScroll));
        endList(g);
    }

    /** Картинка из файла Telegram, вписанная (или с обрезкой — cover) в прямоугольник; false — ещё нет. */
    private static boolean drawImage(GuiGraphics g, @Nullable TdApi.File file, int x, int y, int w, int h, boolean cover) {
        Path path = Telegram.localFile(file);
        PhotoTextures.Image image = path != null ? PhotoTextures.preview(new PhotoStore.Photo(path, 0), 128) : null;
        if (image == null) {
            return false;
        }
        if (cover) {
            PhoneDraw.textureCover(g, image.id(), x, y, w, h, image.width(), image.height());
        } else {
            float k = Math.min(w / (float) image.width(), h / (float) image.height());
            int iw = Math.max(1, Math.round(image.width() * k));
            int ih = Math.max(1, Math.round(image.height() * k));
            PhoneDraw.texture(g, image.id(), x + (w - iw) / 2, y + (h - ih) / 2, iw, ih, image.width(), image.height());
        }
        return true;
    }

    private void sendFromPanel(Runnable send) {
        send.run();
        replyTo = 0;
        scroll = 0;
    }

    /** Полосы над полем: «Ответ …» / «Изменение» с крестиком и выбранные фото. */
    private void renderBars(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        int y = areaBottom();
        if (barsHeight() > 0) {
            g.fill(0, y, w, y + barsHeight() + 4, TgTheme.background());
        }
        g.fill(0, y, w, y + 1, TgTheme.separator());
        y += 3;
        if (replyTo != 0 || editing != 0) {
            TdApi.Message target = Telegram.message(chatId, editing != 0 ? editing : replyTo);
            String title = editing != 0 ? tr("telegram.editing") : tr("telegram.reply_to", target != null ? Telegram.senderName(target) : "…");
            String text = target != null ? Telegram.text(target).replace('\n', ' ') : "";
            g.fill(6, y, 8, y + 15, TgTheme.accent());
            PhoneDraw.text(g, PhoneDraw.ellipsize(title, w - 40, SMALL), 11, y + 1, TgTheme.accent(), SMALL, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(text, w - 40, SMALL), 11, y + 8, TgTheme.secondaryText(), SMALL, false);
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
                    g.fill(x, y, x + 26, y + 26, TgTheme.card());
                }
                PhoneDraw.roundedRect(g, x + 17, y + 1, x + 25, y + 9, 4, 0xC0000000);
                PhoneDraw.centeredText(g, "×", x + 21, y + 1, 0xFFFFFFFF, 0.6f, false);
                hit(x + 15, y, 11, 11, () -> photos.remove(photo));
                x += 28;
                if (x + 26 > w - 26) {
                    break;
                }
            }
            PhoneDraw.rightText(g, photos.size() + "/" + MAX_PHOTOS, w - 6, y + 10, TgTheme.secondaryText(), SMALL);
        }
        if (!files.isEmpty()) {
            // Файлы — «плашками» с именем (после фото, в той же полосе).
            int x = 6 + Math.min(photos.size(), 4) * 28;
            for (java.nio.file.Path file : List.copyOf(files)) {
                int cw = 58;
                if (x + cw > w - 26) {
                    PhoneDraw.text(g, "+" + (files.size() - files.indexOf(file)), x, y + 9, TgTheme.secondaryText(), SMALL, false);
                    break;
                }
                PhoneDraw.roundedRect(g, x, y, x + cw, y + 26, 4, TgTheme.card());
                String name = file.getFileName().toString();
                int dot = name.lastIndexOf('.');
                String ext = dot > 0 && name.length() - dot <= 5 ? name.substring(dot + 1).toUpperCase(java.util.Locale.ROOT) : "?";
                PhoneDraw.roundedRect(g, x + 2, y + 6, x + 14, y + 20, 2, TgTheme.accent());
                PhoneDraw.centeredText(g, ext, x + 8, y + 11, 0xFFFFFFFF, 0.35f, false);
                PhoneDraw.wrapped(g, PhoneDraw.ellipsize(file.getFileName().toString(), (cw - 18) * 2f, 0.5f), x + 15, y + 4,
                        cw - 18, TgTheme.text(), 0.5f, false);
                PhoneDraw.roundedRect(g, x + cw - 9, y + 1, x + cw - 1, y + 9, 4, 0xC0000000);
                PhoneDraw.centeredText(g, "×", x + cw - 5, y + 1, 0xFFFFFFFF, 0.6f, false);
                hit(x + cw - 11, y, 11, 11, () -> files.remove(file));
                x += cw + 2;
            }
        }
    }

    private void renderInput(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        int top = inputTop();
        g.fill(0, top - 4, w, top - 3, TgTheme.separator());
        if (recorder != null) {
            renderRecording(g, mouseX, mouseY, top);
            return;
        }
        boolean ready = Telegram.status() == Telegram.Status.READY;
        TdApi.Chat current = Telegram.chat(chatId);
        Telegram.Access access = current != null ? Telegram.access(current) : Telegram.Access.WRITE;
        if (access != Telegram.Access.WRITE && editing == 0) {
            // Как в Telegram: в канале без прав админа вместо поля — «Подписаться» или «Вкл./выкл. уведомления».
            input.setFocused(false);
            boolean hover = PhoneDraw.inside(mouseX, mouseY, 0, top - 3, w, INPUT_H + 6);
            String label;
            Runnable action;
            if (access == Telegram.Access.JOIN) {
                label = tr("telegram.join");
                action = () -> Telegram.join(chatId);
            } else if (current.type instanceof TdApi.ChatTypeSupergroup s && s.isChannel) {
                boolean muted = Telegram.muted(current);
                label = tr(muted ? "telegram.unmute" : "telegram.mute");
                action = () -> Telegram.setMuted(current, !muted);
            } else {
                PhoneDraw.centeredText(g, tr("telegram.read_only"), w / 2f, top + 6, TgTheme.secondaryText(), 0.7f, false);
                return;
            }
            if (hover) {
                g.fill(0, top - 3, w, top + INPUT_H + 3, TgTheme.cardHover());
            }
            PhoneDraw.centeredText(g, label, w / 2f, top + 6, TgTheme.accent(), 0.8f, false);
            hit(0, top - 3, w, INPUT_H + 6, action);
            return;
        }
        int ix = 6;
        if (ready && editing == 0) {
            roundButton(g, ix, top + 1, "+", false, mouseX, mouseY, this::openAttachMenu);
            ix += 20;
            roundButton(g, ix, top + 1, "☺", panel, mouseX, mouseY, this::togglePanel);
            ix += 20;
        }
        input.setBounds(ix, top, w - 28 - ix, INPUT_H);
        input.setPlaceholder(tr(photos.isEmpty() && files.isEmpty() ? "messenger.message_placeholder" : "telegram.caption_placeholder"));
        input.render(g, mouseX, mouseY);
        boolean canSend = !input.text().isBlank() || ((!photos.isEmpty() || !files.isEmpty()) && editing == 0);
        int bx = w - 24;
        boolean hovered = PhoneDraw.inside(mouseX, mouseY, bx, top + 1, 18, 18);
        if (!canSend && ready && editing == 0) {
            // Пустое поле — микрофон: записать голосовое.
            PhoneDraw.roundedRect(g, bx, top + 1, bx + 18, top + 19, 9, hovered ? TgTheme.accentHover() : TgTheme.accent());
            PhoneDraw.tinted(g, Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/voice/mic.png"),
                    bx + 3, top + 4, 12, 12, 32, 32, TgTheme.onAccent());
            hit(bx, top + 1, 18, 18, this::startRecording);
            return;
        }
        PhoneDraw.roundedRect(g, bx, top + 1, bx + 18, top + 19, 9,
                canSend ? (hovered ? 0xFF5BBDF0 : 0xFF2AABEE) : TgTheme.separator());
        PhoneDraw.centeredText(g, editing != 0 ? "✔" : "➤", bx + 9, top + 6, 0xFFFFFFFF, 0.9f, false);
        hit(bx, top + 1, 18, 18, this::submit);
    }

    private void roundButton(GuiGraphics g, int x, int y, String label, boolean active, int mouseX, int mouseY, Runnable action) {
        boolean hover = PhoneDraw.inside(mouseX, mouseY, x, y, 18, 18);
        PhoneDraw.roundedRect(g, x, y, x + 18, y + 18, 9, active ? TgTheme.accentTint(0x50) : hover ? TgTheme.cardHover() : TgTheme.card());
        PhoneDraw.centeredText(g, label, x + 9, y + 5, TgTheme.accent(), 0.9f, false);
        hit(x, y, 18, 18, action);
    }

    /** Идёт запись голосового: красная точка, время, уровень, «×» — отмена, «➤» — отправить. */
    private void renderRecording(GuiGraphics g, int mouseX, int mouseY, int top) {
        int w = width();
        VoiceRecorder r = recorder;
        PhoneDraw.field(g, 6, top, w - 28, top + INPUT_H, false);
        if (r.error() != null) {
            PhoneDraw.text(g, PhoneDraw.ellipsize(tr(r.error()), w - 60, 0.6f), 12, top + 7, TgTheme.danger(), 0.6f, false);
        } else {
            boolean blink = System.currentTimeMillis() / 500 % 2 == 0;
            PhoneDraw.roundedRect(g, 11, top + 7, 17, top + 13, 3, blink ? 0xFFE5534B : 0x80E5534B);
            PhoneDraw.text(g, VoiceMessages.time(r.recordedMs()), 21, top + 7, TgTheme.text(), 0.65f, false);
            int barW = w - 34 - 52 - 22;
            g.fill(50, top + 9, 50 + barW, top + 11, TgTheme.separator());
            g.fill(50, top + 9, 50 + Math.round(barW * r.level()), top + 11, TgTheme.accent());
        }
        button(g, w - 46, top + 3, 14, 14, "×", mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, this::cancelRecording);
        int bx = w - 24;
        boolean hovered = PhoneDraw.inside(mouseX, mouseY, bx, top + 1, 18, 18);
        PhoneDraw.roundedRect(g, bx, top + 1, bx + 18, top + 19, 9, hovered ? 0xFF5BBDF0 : 0xFF2AABEE);
        PhoneDraw.centeredText(g, "➤", bx + 9, top + 6, 0xFFFFFFFF, 0.9f, false);
        hit(bx, top + 1, 18, 18, this::sendRecording);
    }

    private void startRecording() {
        panel = false;
        recordReplyTo = replyTo;
        recorder = VoiceRecorder.start(MAX_VOICE_SECONDS);
    }

    private void cancelRecording() {
        if (recorder != null) {
            recorder.stop();
            recorder = null;
        }
    }

    /** Остановить запись и отправить, когда поток записи закончит (в фоне — сжатие в Opus). */
    private void sendRecording() {
        VoiceRecorder r = recorder;
        recorder = null;
        if (r == null || r.error() != null) {
            return;
        }
        r.stop();
        long reply = recordReplyTo;
        replyTo = 0;
        scroll = 0;
        Thread waiter = new Thread(() -> {
            while (!r.finished()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
            }
            short[] pcm = r.samples();
            Minecraft.getInstance().execute(() -> TelegramVoice.send(chatId, pcm, reply));
        }, "BlockPhone Telegram voice");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void togglePanel() {
        panel = !panel;
        if (panel) {
            Telegram.loadStickerPanel();
        }
    }

    /** «+»: фото из галереи или «кружочек». */
    private void openAttachMenu() {
        menuTarget = null;
        menu = new PopupMenu();
        menu.add(tr("telegram.attach_photo"), picker::open);
        menu.add(tr("telegram.attach_clipboard"), this::pasteFromClipboard);
        int rows = 2;
        if (VideoNoteRecorder.SIZE > 0) {
            dev.blockphone.fabric.client.config.PhoneSettings s = dev.blockphone.fabric.client.config.PhoneSettings.get();
            menu.add(tr("telegram.attach_video_note"), () -> VideoNoteRecorder.start(chatId));
            menu.add(tr("telegram.note_mic", tr(s.videoNoteMic ? "settings.on" : "settings.off")), () -> {
                s.videoNoteMic = !s.videoNoteMic;
                s.save();
            });
            menu.add(tr("telegram.note_music", tr(s.videoNoteMusic ? "settings.on" : "settings.off")), () -> {
                s.videoNoteMusic = !s.videoNoteMusic;
                s.save();
            });
            rows = 5;
        }
        menu.show(30, inputTop() - 14 * rows - 10, width(), height());
    }

    /** Полоса быстрых реакций над меню сообщения. */
    private void renderReactions(GuiGraphics g, int mouseX, int mouseY) {
        int w = quick.length * REACT_CELL + 4;
        PhoneDraw.roundedRect(g, reactX - 1, reactY, reactX + w + 1, reactY + REACT_CELL + 4, 7, 0x40000000);
        PhoneDraw.roundedRect(g, reactX, reactY, reactX + w, reactY + REACT_CELL + 2, 7, TgTheme.card());
        for (int i = 0; i < quick.length; i++) {
            int cx = reactX + 2 + i * REACT_CELL;
            if (PhoneDraw.inside(mouseX, mouseY, cx, reactY + 1, REACT_CELL, REACT_CELL)) {
                PhoneDraw.roundedRect(g, cx, reactY + 1, cx + REACT_CELL, reactY + 1 + REACT_CELL, 7, TgTheme.cardHover());
            }
            if (chosen(menuTarget, quick[i])) {
                PhoneDraw.roundedRect(g, cx, reactY + 1, cx + REACT_CELL, reactY + 1 + REACT_CELL, 7, TgTheme.accentTint(0x60));
            }
            drawEmoji(g, quick[i], cx + 2, reactY + 3, REACT_CELL - 4, TgTheme.text());
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
        quick = quick;
        Telegram.availableReactions(chatId, m.id, list -> {
            if (!list.isEmpty() && menuTarget == m) {
                quick = list.subList(0, Math.min(8, list.size())).toArray(String[]::new);
            }
        });
        menu = new PopupMenu();
        menu.add(tr("telegram.reply"), () -> {
            editing = 0;
            replyTo = item.messages().get(0).id;
            input.setFocused(true);
        });
        if (!item.text().isEmpty()) {
            menu.add(tr("telegram.copy"), () -> Minecraft.getInstance().keyboardHandler.setClipboard(item.text()));
        }
        menu.add(tr("telegram.forward"), () -> app.push(new TgChatListView(app, tr("telegram.forward_to"), to -> {
            Telegram.forward(chatId, ids, to);
            app.openChat(to);
        })));
        if (m.isOutgoing && m.content instanceof TdApi.MessageText t) {
            menu.add(tr("telegram.edit"), () -> {
                replyTo = 0;
                photos.clear();
                files.clear();
                editing = m.id;
                input.setText(t.text.text);
                input.setFocused(true);
            });
        }
        menu.addDanger(tr("telegram.delete"), () -> app.showDialog(PhoneDialog.confirmDelete(tr("telegram.delete"),
                tr(m.isOutgoing ? "telegram.delete_all" : "telegram.delete_me"),
                () -> Telegram.deleteMessages(chatId, ids, m.isOutgoing))));
        int stripW = quick.length * REACT_CELL + 4;
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

    /**
     * Фото или файлы из буфера обмена (Ctrl+V при пустом текстовом буфере или «+» → «Из буфера обмена»): прикрепляются
     * к сообщению, как через «+» → фото; отправка — обычной кнопкой (текст поля — подпись).
     */
    private void pasteFromClipboard() {
        if (editing != 0 || recorder != null) {
            return;
        }
        dev.blockphone.fabric.client.storage.ClipboardImage.paste(found -> {
            if (found.isEmpty()) {
                app.showDialog(new PhoneDialog(tr("telegram.clip_title"), tr("telegram.clip_empty"), "OK", false, () -> {
                }));
                return;
            }
            for (java.nio.file.Path f : found) {
                String n = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                boolean image = n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")
                       ;
                if (image && photos.size() < MAX_PHOTOS) {
                    PhotoStore.Photo photo = new PhotoStore.Photo(f, 0);
                    if (!photos.contains(photo)) {
                        photos.add(photo);
                    }
                } else if (!image && files.size() < MAX_PHOTOS && !files.contains(f)) {
                    files.add(f);
                }
            }
            input.setFocused(true);
        });
    }

    /** Ctrl+V вставляет текст, только если в буфере обычный текст (не пусто и не путь к файлу). */
    private static boolean pastesText() {
        String text = Minecraft.getInstance().keyboardHandler.getClipboard().strip();
        if (text.isEmpty()) {
            return false;
        }
        try {
            String path = text.startsWith("\"") && text.endsWith("\"") && text.length() > 1 ? text.substring(1, text.length() - 1) : text;
            return text.length() > 400 || text.contains("\n") || !java.nio.file.Files.isRegularFile(java.nio.file.Path.of(path));
        } catch (RuntimeException e) {
            return true;
        }
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
        if (!photos.isEmpty() || !files.isEmpty()) {
            if (!photos.isEmpty()) {
                Telegram.sendPhotos(chatId, photos.stream().map(PhotoStore.Photo::file).toList(), text, replyTo);
            }
            if (!files.isEmpty()) {
                Telegram.sendDocuments(chatId, List.copyOf(files), photos.isEmpty() ? text : "", photos.isEmpty() ? replyTo : 0);
            }
            photos.clear();
            files.clear();
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
            int stripW = quick.length * REACT_CELL + 4;
            if (PhoneDraw.inside(x, y, reactX, reactY, stripW, REACT_CELL + 2) && menuTarget != null) {
                int i = Math.max(0, Math.min(quick.length - 1, (int) ((x - reactX - 2) / REACT_CELL)));
                Telegram.toggleReaction(chatId, menuTarget.id, quick[i], chosen(menuTarget, quick[i]));
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
        TdApi.Chat current = Telegram.chat(chatId);
        if ((current == null || Telegram.access(current) == Telegram.Access.WRITE || editing != 0) && input.mouseClicked(x, y, button)) {
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
        int panelTop = inputTop() - 4 - PANEL_H;
        if (panel && y >= panelTop && y < panelTop + PANEL_H) {
            if (y < panelTop + TAB + 2) {
                tabOffset -= (int) Math.signum(amount);
            } else {
                panelScroll -= amount * 20;
            }
            return true;
        }
        if (input.mouseScrolled(x, y, amount)) {
            return true;
        }
        // Колесо над голосовым или «кружочком» — громкость (общая с видео), подсказка с процентами на 1,5 с.
        for (Bubble b : bubbles) {
            if (PhoneDraw.inside(x, y, b.x(), b.y(), b.w(), b.h()) && (b.item().voice() != null || b.item().note())) {
                VoiceMessages.changeMediaVolume(amount > 0 ? 10 : -10);
                volumeHintUntil = System.currentTimeMillis() + 1500;
                return true;
            }
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
        if (recorder != null) {
            cancelRecording();
            return true;
        }
        if (panel) {
            panel = false;
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
        } else if (what.equals("panel")) {
            menu.close();
            replyTo = 0;
            photos.clear();
            files.clear();
            panel = true;
        } else if (what.equals("gifs")) {
            panelTab = 1;
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
            if (recorder != null) {
                sendRecording();
            } else {
                submit();
            }
            return true;
        }
        if (event.isPaste() && !pastesText()) {
            pasteFromClipboard();
            return true;
        }
        return input.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return input.charTyped(event);
    }
}
