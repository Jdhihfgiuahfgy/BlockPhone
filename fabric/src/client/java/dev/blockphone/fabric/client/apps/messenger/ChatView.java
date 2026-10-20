package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.common.protocol.data.StickerData;
import dev.blockphone.fabric.client.messenger.StickerClient;

import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.data.MessengerData;
import dev.blockphone.common.protocol.data.VoiceData.CallInfo;
import dev.blockphone.common.protocol.data.VoiceData.VoiceRef;
import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.ChatType;
import dev.blockphone.common.protocol.data.MessengerData.MessageInfo;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.photo.ImageUploads;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.ui.ImageViewer;
import dev.blockphone.fabric.client.ui.Images;
import dev.blockphone.fabric.client.ui.PhotoPicker;
import dev.blockphone.fabric.client.voice.CallClient;
import dev.blockphone.fabric.client.voice.VoiceMessages;
import dev.blockphone.fabric.client.voice.VoiceRecorder;
import net.minecraft.resources.Identifier;
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
 * поле ввода (Enter — отправить, Shift+Enter — новая строка), ПКМ по сообщению — меню (изменить/удалить/копировать),
 * фото: кнопка «+» слева от поля — выбрать из галереи (загружается сразу), клик по фото — просмотр;
 * голосовые: при пустом поле справа — микрофон (нажать — запись, ещё раз «➤» — отправить, «×» — отмена);
 * звонок: трубка в строке статуса, идущий звонок — зелёная полоса под ней (Simple Voice Chat).
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
    private static final int[] NAME_COLORS = {0xFFE5534B, 0xFF2F81F7, 0xFF3FB950, 0xFFD29922, 0xFFA371F7, 0xFFDB61A2};
    private static final long TYPING_SEND_MS = 3000;
    private static final int IMAGE_W = 100;
    private static final int STICKER = 72;
    private static final int PANEL_H = 118;
    private static final int PANEL_CELL = 34;
    private static final int ATTACH_H = 24;
    private static final int VOICE_W = 96;
    private static final int VOICE_H = 16;
    private static final int CALL_H = 16;
    private static final Identifier MIC = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/voice/mic.png");
    private static final Identifier CALL = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/voice/call.png");
    private static final Identifier HANGUP = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/voice/hangup.png");
    private static final Identifier PLAY = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/music/play.png");
    private static final Identifier PAUSE = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/music/pause.png");

    /** Элемент ленты: сообщение (или неподтверждённое) либо разделитель дня. */
    private record Item(MessageInfo message, MessengerClient.Pending pending, String separator,
                        List<FormattedCharSequence> lines, String name, String time, ImageRef image, int imageH, VoiceRef voice,
                        int width, int height, boolean own, dev.blockphone.common.protocol.data.StickerData.StickerRef sticker) {
    }

    /** Голосовое на экране (клик — играть/пауза). */
    private record VoiceHit(int x, int y, int w, int h, VoiceRef voice) {
    }

    /** Кнопка в строке статуса / полосе звонка. */
    private record ButtonHit(int x, int y, int w, int h, Runnable action) {
    }

    /** Фото на экране (клик — просмотр). */
    private record ImageHit(int x, int y, int w, int h, ImageRef image) {
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
    private final List<ImageHit> imageHits = new ArrayList<>();
    private final List<VoiceHit> voiceHits = new ArrayList<>();
    private final List<ButtonHit> buttons = new ArrayList<>();
    /** Идёт запись голосового (null — нет). */
    private VoiceRecorder recorder;
    /** Запись остановлена — ждём данные и отправляем. */
    private boolean recordingSend;
    private boolean voiceUploading;
    private String voiceError;
    private long voiceErrorAt;
    private final PhotoPicker picker = new PhotoPicker(this::attach);
    /** Панель стикеров открыта; выбранный набор. */
    private boolean stickerPanel;
    private long stickerPack;
    private final List<ButtonHit> panelHits = new ArrayList<>();
    private final List<ButtonHit> stickerHits = new ArrayList<>();
    private final ImageViewer viewer = new ImageViewer();
    /** Прикреплённое фото (загружается или загружено), null — нет. */
    private ImageUploads.Upload attachment;
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
        cancelRecording();
        MessengerClient.setOpenChat(-1);
        input.setFocused(false);
    }

    @Override
    void tick() {
        tickVoice();
        if (MessengerClient.loaded() && chat() == null) {
            app.pop(); // Чат удалён или нас исключили.
        }
    }

    @Override
    boolean capturesKeyboard() {
        return input.isFocused();
    }

    /** Каждый тик: запись дошла до предела — отправить; запись остановлена — сжать и загрузить. */
    private void tickVoice() {
        if (recorder == null) {
            return;
        }
        if (recorder.error() != null) {
            showVoiceError(MessengerApp.tr(recorder.error()));
            recorder = null;
            recordingSend = false;
            return;
        }
        if (recorder.full() && !recordingSend) {
            finishRecording();
        }
        if (recordingSend && recorder.finished()) {
            short[] samples = recorder.samples();
            recorder = null;
            recordingSend = false;
            if (samples.length < dev.blockphone.common.protocol.data.VoiceData.SAMPLE_RATE / 2) {
                showVoiceError(MessengerApp.tr("voice.too_short"));
                return;
            }
            voiceUploading = true;
            VoiceMessages.upload(samples, result -> {
                voiceUploading = false;
                if (result.voice() != null) {
                    MessengerClient.sendMessage(chatId, "", null, result.voice());
                    scroll = 0;
                } else {
                    showVoiceError(MessengerApp.tr(result.error()));
                }
            });
        }
    }

    private void showVoiceError(String text) {
        voiceError = text;
        voiceErrorAt = System.currentTimeMillis();
    }

    private boolean voiceEnabled() {
        return editingId < 0 && ClientSession.maxVoiceSeconds() > 0;
    }

    private void startRecording() {
        dev.blockphone.fabric.client.voice.VoiceMessages.stop();
        recorder = VoiceRecorder.start(ClientSession.maxVoiceSeconds());
        recordingSend = false;
    }

    private void finishRecording() {
        if (recorder != null) {
            recorder.stop();
            recordingSend = true;
        }
    }

    private void cancelRecording() {
        if (recorder != null) {
            recorder.stop();
            recorder = null;
            recordingSend = false;
        }
    }

    // --- Раскладка ---

    private int inputTop() {
        return height() - INPUT_H - 4;
    }

    private int areaTop() {
        return STATUS_H + 2 + (CallClient.call(chatId) != null ? CALL_H : 0);
    }

    private int areaBottom() {
        return inputTop() - (editingId >= 0 ? 14 : 4) - (attachment != null ? ATTACH_H : 0);
    }

    private boolean canAttach() {
        return editingId < 0 && ClientSession.photosAllowed();
    }

    private int inputX() {
        return 6 + (canAttach() ? 22 : 0) + (canSticker() ? 22 : 0);
    }

    private boolean canSticker() {
        return editingId < 0 && StickerClient.enabled();
    }

    private int stickerButtonX() {
        return canAttach() ? 28 : 6;
    }

    /** Панель над полем ввода: вкладки наборов, «⚙» (мои стикеры), стикеры выбранного набора. */
    private void renderStickerPanel(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        int bottom = inputTop() - 4;
        int top = bottom - PANEL_H;
        PhoneDraw.panel(g, 4, top, w - 4, bottom, PhoneTheme.card());
        List<StickerData.PackInfo> packs = StickerClient.mine();
        if (packs.stream().noneMatch(p -> p.id() == stickerPack) && !packs.isEmpty()) {
            stickerPack = packs.get(0).id();
        }
        // Вкладки.
        int tx = 8;
        int ty = top + 4;
        PhoneDraw.button(g, w - 26, ty, 18, 14, "⚙", mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL);
        panelHits.add(new ButtonHit(w - 26, ty, 18, 14, () -> {
            stickerPanel = false;
            app.push(new StickersView(app));
        }));
        for (StickerData.PackInfo pack : packs) {
            String label = PhoneDraw.ellipsize(pack.title(), 60, 0.6f);
            int tw = (int) PhoneDraw.width(label, 0.6f) + 10;
            if (tx + tw > w - 30) {
                break;
            }
            boolean selected = pack.id() == stickerPack;
            PhoneDraw.button(g, tx, ty, tw, 14, label, mouseX, mouseY, selected ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
            long id = pack.id();
            panelHits.add(new ButtonHit(tx, ty, tw, 14, () -> stickerPack = id));
            tx += tw + 3;
        }
        StickerData.PackInfo pack = StickerClient.pack(stickerPack);
        int gy = ty + 18;
        if (pack == null || pack.stickers().isEmpty()) {
            PhoneDraw.wrapped(g, MessengerApp.tr(packs.isEmpty() ? "sticker.panel_empty" : "sticker.pack_empty"), 10, gy + 4, w - 20,
                    PhoneTheme.secondaryText(), 0.65f, false);
            return;
        }
        int perRow = Math.max(1, (w - 16) / (PANEL_CELL + 4));
        int rows = (bottom - gy - 4) / (PANEL_CELL + 4);
        for (int i = 0; i < Math.min(pack.stickers().size(), perRow * rows); i++) {
            StickerData.StickerRef sticker = pack.stickers().get(i);
            int x = 8 + (i % perRow) * (PANEL_CELL + 4);
            int y = gy + (i / perRow) * (PANEL_CELL + 4);
            if (PhoneDraw.inside(mouseX, mouseY, x, y, PANEL_CELL, PANEL_CELL)) {
                PhoneDraw.roundedRect(g, x - 1, y - 1, x + PANEL_CELL + 1, y + PANEL_CELL + 1, 4, PhoneTheme.cardHover());
            }
            Images.drawFit(g, sticker.image(), x, y, PANEL_CELL, PANEL_CELL);
            panelHits.add(new ButtonHit(x, y, PANEL_CELL, PANEL_CELL, () -> sendSticker(sticker)));
        }
    }

    private void sendSticker(StickerData.StickerRef sticker) {
        ClientNetwork.send(new MessengerPackets.SendMessage(chatId, "st" + System.nanoTime(), "", 0, 0, sticker.id()));
        scroll = 0;
    }

    /** Для автоскриншотов: открыть панель стикеров / отправить первый стикер выбранного набора. */
    void debugStickers(boolean open, boolean sendFirst) {
        stickerPanel = open;
        StickerData.PackInfo pack = StickerClient.pack(stickerPack);
        if (sendFirst && pack != null && !pack.stickers().isEmpty()) {
            sendSticker(pack.stickers().get(0));
        }
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
            List<FormattedCharSequence> lines = text.isEmpty() ? List.of() : wrap(m.id() + ":" + m.editedAt() + ":" + m.deleted(), text);
            String senderKey = m.sender().uuid().toString();
            String name = group && !own && !senderKey.equals(prevSender) ? m.sender().name() : null;
            String time = (m.editedAt() > 0 && !m.deleted() ? MessengerApp.tr("messenger.edited") + " " : "") + MessengerFormat.time(m.sentAt());
            if (m.sticker() != null && !m.deleted()) {
                // Стикер — картинка без пузыря.
                items.add(new Item(m, null, null, List.of(), name, time, m.sticker().image(), STICKER, null, STICKER,
                        STICKER + 10 + (name != null ? 7 : 0), own, m.sticker()));
            } else {
                items.add(bubble(m, null, lines, name, time, m.deleted() ? null : m.image(), m.deleted() ? null : m.voice(), own));
            }
            prevTime = m.sentAt();
            prevSender = senderKey;
        }
        for (MessengerClient.Pending p : MessengerClient.pending(chatId)) {
            List<FormattedCharSequence> lines = p.text().isEmpty() ? List.of() : wrap("p:" + p.nonce(), p.text());
            items.add(bubble(null, p, lines, null, "...", p.image(), p.voice(), true));
        }
        return items;
    }

    private Item separator(String label) {
        return new Item(null, null, label, List.of(), null, null, null, 0, null, 0, SEPARATOR_H, false, null);
    }

    private Item bubble(MessageInfo m, MessengerClient.Pending p, List<FormattedCharSequence> lines, String name, String time,
                        ImageRef image, VoiceRef voice, boolean own) {
        float textW = 0;
        for (FormattedCharSequence line : lines) {
            textW = Math.max(textW, PhoneDraw.font().width(line) * TEXT_SCALE);
        }
        float nameW = name != null ? PhoneDraw.width(name, SMALL) : 0;
        float timeW = PhoneDraw.width(time, SMALL);
        float contentW = Math.max(textW, Math.max(nameW, timeW));
        int imageH = 0;
        if (image != null) {
            contentW = Math.max(contentW, IMAGE_W);
            imageH = Images.height(image, (int) Math.ceil(contentW));
        }
        if (voice != null) {
            contentW = Math.max(contentW, VOICE_W);
        }
        int width = (int) Math.ceil(contentW) + PAD_X * 2;
        int height = PAD_Y * 2 + lines.size() * LINE_H + 6 + (name != null ? 7 : 0) + (image != null ? imageH + 3 : 0)
                + (voice != null ? VOICE_H + 6 : 0);
        return new Item(m, p, null, lines, name, time, image, imageH, voice, width, height, own, null);
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
        buttons.clear();
        renderStatus(g, mouseX, mouseY);
        renderCallBar(g, mouseX, mouseY);

        List<Item> items = layout();
        int areaTop = areaTop();
        int areaBottom = areaBottom();
        int total = totalHeight(items);
        clampScroll(total);
        MessengerClient.ChatMessages data = MessengerClient.messages(chatId);
        if (data.loaded && data.hasMore && scroll >= total - (areaBottom - areaTop) - 20) {
            MessengerClient.requestOlder(chatId);
        }

        hits.clear();
        imageHits.clear();
        voiceHits.clear();
        stickerHits.clear();
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
        panelHits.clear();
        if (stickerPanel) {
            renderStickerPanel(g, mouseX, mouseY);
        }
        if (menu != null) {
            renderMenu(g, mouseX, mouseY);
        }
        picker.render(g, mouseX, mouseY, w, height());
        viewer.render(g, w, height());
    }

    private void renderStatus(GuiGraphics g, int mouseX, int mouseY) {
        ChatInfo chat = chat();
        if (chat == null) {
            return;
        }
        if (ClientSession.callsAvailable() && CallClient.call(chatId) == null) {
            int bx = width() - 16;
            boolean hover = PhoneDraw.inside(mouseX, mouseY, bx - 2, 0, 14, STATUS_H);
            PhoneDraw.tinted(g, CALL, bx, 1, 10, 10, 32, 32, hover ? PhoneTheme.online() : PhoneTheme.secondaryText());
            buttons.add(new ButtonHit(bx - 2, 0, 14, STATUS_H, () -> CallClient.start(chatId)));
        }
        List<String> typing = MessengerClient.typing(chatId);
        String status;
        int color = PhoneTheme.secondaryText();
        if (!typing.isEmpty()) {
            status = chat.type() == ChatType.GROUP
                    ? MessengerApp.tr("messenger.typing_named", String.join(", ", typing))
                    : MessengerApp.tr("messenger.typing");
            color = PhoneTheme.accent();
        } else if (chat.type() == ChatType.GROUP) {
            long online = chat.members().stream().filter(MessengerClient::isOnline).count();
            status = MessengerApp.tr("messenger.members_online", chat.members().size(), online);
        } else {
            UserInfo partner = MessengerClient.partner(chat);
            boolean online = partner != null && MessengerClient.isOnline(partner);
            status = MessengerApp.tr(online ? "messenger.online" : "messenger.offline");
            if (online) {
                color = PhoneTheme.accent();
            }
        }
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(status, width() - 36, 0.6f), width() / 2f, 3, color, 0.6f, false);
        g.fill(0, STATUS_H, width(), STATUS_H + 1, PhoneTheme.separator());
    }

    /** Полоса идущего звонка: кто в звонке, время, «Войти» / «Ответить» / «Выйти». */
    private void renderCallBar(GuiGraphics g, int mouseX, int mouseY) {
        CallInfo call = CallClient.call(chatId);
        if (call == null) {
            return;
        }
        int w = width();
        int top = STATUS_H + 1;
        g.fill(0, top, w, top + CALL_H, PhoneTheme.mix(PhoneTheme.online(), PhoneTheme.background(), 0.75f));
        g.fill(0, top + CALL_H - 1, w, top + CALL_H, PhoneTheme.separator());
        PhoneDraw.tinted(g, CALL, 6, top + 3, 10, 10, 32, 32, PhoneTheme.online());
        boolean mine = CallClient.inCall(chatId);
        boolean ringingMe = !mine && call.invited().stream().anyMatch(u -> MessengerClient.isSelf(u.uuid()));
        String text;
        if (mine) {
            text = call.participants().size() > 1
                    ? MessengerApp.tr("call.in_call", call.participants().size(), VoiceMessages.time(CallClient.duration()))
                    : MessengerApp.tr("call.calling");
        } else if (ringingMe) {
            text = MessengerApp.tr("call.incoming_short");
        } else {
            text = MessengerApp.tr("call.ongoing", call.participants().size());
        }
        int right = w - 4;
        if (mine) {
            right = callButton(g, right, top, MessengerApp.tr("call.leave"), PhoneTheme.danger(), mouseX, mouseY, () -> CallClient.leave(chatId));
        } else if (ringingMe) {
            right = callButton(g, right, top, MessengerApp.tr("call.decline"), PhoneTheme.danger(), mouseX, mouseY,
                    () -> CallClient.answer(chatId, false));
            right = callButton(g, right, top, MessengerApp.tr("call.answer"), PhoneTheme.online(), mouseX, mouseY,
                    () -> CallClient.answer(chatId, true));
        } else if (ClientSession.callsAvailable()) {
            right = callButton(g, right, top, MessengerApp.tr("call.join"), PhoneTheme.online(), mouseX, mouseY, () -> CallClient.start(chatId));
        }
        PhoneDraw.text(g, PhoneDraw.ellipsize(text, right - 22, 0.6f), 20, top + 5, PhoneTheme.text(), 0.6f, false);
    }

    /** Кнопка в полосе звонка (справа налево). Возвращает новую правую границу. */
    private int callButton(GuiGraphics g, int right, int top, String label, int color, int mouseX, int mouseY, Runnable action) {
        int bw = (int) PhoneDraw.width(label, 0.6f) + 8;
        int x = right - bw;
        boolean hover = PhoneDraw.inside(mouseX, mouseY, x, top + 2, bw, CALL_H - 4);
        PhoneDraw.roundedRect(g, x, top + 2, right, top + CALL_H - 2, 3, hover ? PhoneTheme.mix(color, PhoneTheme.WHITE, 0.15f) : color);
        PhoneDraw.centeredText(g, label, x + bw / 2f, top + 5, 0xFFFFFFFF, 0.6f, false);
        buttons.add(new ButtonHit(x, top + 2, bw, CALL_H - 4, action));
        return x - 3;
    }

    /** Голосовое в пузыре: кнопка, полоска проигрывания, длительность. */
    private void renderVoice(GuiGraphics g, VoiceRef voice, int x, int y, int w, boolean own, boolean pending) {
        int fg = own ? PhoneTheme.onAccent() : PhoneTheme.accent();
        int dim = own ? (PhoneTheme.onAccent() & 0x00FFFFFF) | 0x60000000 : PhoneTheme.separator();
        PhoneDraw.roundedRect(g, x, y + 1, x + 14, y + 15, 7, fg);
        int iconColor = own ? PhoneTheme.accent() : PhoneTheme.onAccent();
        boolean playing = VoiceMessages.playing(voice.id());
        if (VoiceMessages.loading(voice.id())) {
            PhoneDraw.centeredText(g, "…", x + 7, y + 4, iconColor, 0.75f, false);
        } else {
            PhoneDraw.tinted(g, playing ? PAUSE : PLAY, x + 3, y + 4, 8, 8, 32, 32, iconColor);
        }
        int bx = x + 18;
        int bw = w - 18;
        float progress = VoiceMessages.progress(voice.id());
        // «Волна» — столбики разной высоты, проигранные — ярче.
        int bars = bw / 3;
        for (int i = 0; i < bars; i++) {
            int h = 2 + (int) (Math.abs(Math.sin(voice.id() * 7.3 + i * 1.7) * 5) + Math.abs(Math.sin(i * 0.6)) * 2);
            boolean played = i < bars * progress;
            g.fill(bx + i * 3, y + 8 - h / 2, bx + i * 3 + 2, y + 8 + (h + 1) / 2, played || pending ? fg : dim);
        }
        String time = VoiceMessages.failed(voice.id()) ? "×" : VoiceMessages.time(voice.durationMs());
        int timeColor = own ? (PhoneTheme.onAccent() & 0x00FFFFFF) | 0xC0000000 : PhoneTheme.secondaryText();
        PhoneDraw.text(g, time, bx, y + 13, timeColor, SMALL, false);
        if (!pending) {
            voiceHits.add(new VoiceHit(x, y, w, VOICE_H, voice));
        }
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
        if (item.sticker() != null) {
            int sy = y;
            if (item.name() != null) {
                int color = NAME_COLORS[Math.floorMod(item.message().sender().uuid().hashCode(), NAME_COLORS.length)];
                PhoneDraw.text(g, item.name(), x, sy, color, SMALL, false);
                sy += 7;
            }
            Images.drawFit(g, item.sticker().image(), x, sy, STICKER, STICKER);
            PhoneDraw.text(g, item.time(), item.own() ? x + STICKER - PhoneDraw.width(item.time(), SMALL) : x, sy + STICKER + 2,
                    PhoneTheme.secondaryText(), SMALL, false);
            long packId = item.sticker().packId();
            stickerHits.add(new ButtonHit(x, sy, STICKER, STICKER, () -> app.push(new StickerPackView(app, packId))));
            hits.add(new Hit(x, y, item.width(), item.height(), item.message()));
            return;
        }
        boolean pending = item.pending() != null;
        boolean deleted = item.message() != null && item.message().deleted();
        int bg = item.own() ? PhoneTheme.accent() : PhoneTheme.card();
        if (pending) {
            bg = (bg & 0x00FFFFFF) | 0x99000000;
        }
        PhoneDraw.roundedRect(g, x, y, x + item.width(), y + item.height(), 6, bg);
        int textColor = item.own() ? PhoneTheme.onAccent() : PhoneTheme.text();
        if (deleted) {
            textColor = item.own() ? (PhoneTheme.onAccent() & 0x00FFFFFF) | 0xB0000000 : PhoneTheme.secondaryText();
        }
        int ty = y + PAD_Y;
        if (item.name() != null) {
            int color = NAME_COLORS[Math.floorMod(item.message().sender().uuid().hashCode(), NAME_COLORS.length)];
            PhoneDraw.text(g, item.name(), x + PAD_X, ty, color, SMALL, false);
            ty += 7;
        }
        if (item.image() != null) {
            int iw = item.width() - PAD_X * 2;
            Images.draw(g, item.image(), x + PAD_X, ty + 1, iw, item.imageH(), Images.THUMB);
            imageHits.add(new ImageHit(x + PAD_X, ty + 1, iw, item.imageH(), item.image()));
            ty += item.imageH() + 3;
        }
        if (item.voice() != null) {
            renderVoice(g, item.voice(), x + PAD_X, ty, item.width() - PAD_X * 2, item.own(), pending);
            ty += VOICE_H + 6;
        }
        for (FormattedCharSequence line : item.lines()) {
            g.pose().pushMatrix();
            g.pose().translate(x + PAD_X, ty);
            g.pose().scale(TEXT_SCALE, TEXT_SCALE);
            g.drawString(PhoneDraw.font(), line, 0, 0, textColor, false);
            g.pose().popMatrix();
            ty += LINE_H;
        }
        int timeColor = item.own() ? (PhoneTheme.onAccent() & 0x00FFFFFF) | 0xC0000000 : PhoneTheme.secondaryText();
        PhoneDraw.rightText(g, item.time(), x + item.width() - PAD_X, ty + 1, timeColor, SMALL);
        if (item.message() != null && !deleted) {
            hits.add(new Hit(x, y, item.width(), item.height(), item.message()));
        }
    }

    private void renderInput(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        int top = inputTop();
        if (editingId >= 0) {
            PhoneDraw.text(g, MessengerApp.tr("messenger.editing"), 10, top - 11, PhoneTheme.accent(), 0.6f, false);
        }
        int ix = inputX();
        input.setBounds(ix, top, w - 30 - ix, INPUT_H);
        input.setPlaceholder(MessengerApp.tr("messenger.message_placeholder"));
        input.render(g, mouseX, mouseY);
        if (canAttach()) {
            PhoneDraw.button(g, 6, top + 2, 18, 22, "+", mouseX, mouseY,
                    attachment != null ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
        }
        if (canSticker()) {
            PhoneDraw.button(g, stickerButtonX(), top + 2, 18, 22, "☺", mouseX, mouseY,
                    stickerPanel ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
        }
        if (attachment != null) {
            renderAttachment(g, mouseX, mouseY, top - ATTACH_H - 2);
        }
        if (voiceError != null && System.currentTimeMillis() - voiceErrorAt < 4000) {
            int h = PhoneDraw.wrappedHeight(voiceError, w - 28, 0.7f) + 8;
            int ey = top - h - 4 - (attachment != null ? ATTACH_H : 0);
            PhoneDraw.roundedRect(g, 8, ey, w - 8, ey + h, 4, 0xE0B8403A);
            PhoneDraw.wrapped(g, voiceError, 14, ey + 4, w - 28, 0xFFFFFFFF, 0.7f, true);
        }
        if (recorder != null || voiceUploading) {
            renderRecording(g, mouseX, mouseY, top);
            return;
        }

        boolean canSend = !input.text().isBlank() || (attachment != null && attachment.state() == ImageUploads.State.DONE);
        int bx = w - 26;
        int by = top + 2;
        boolean hovered = PhoneDraw.inside(mouseX, mouseY, bx, by, 22, 22);
        boolean mic = !canSend && attachment == null && voiceEnabled();
        int color = canSend || mic ? (hovered ? PhoneTheme.accentHover() : PhoneTheme.accent()) : PhoneTheme.separator();
        PhoneDraw.roundedRect(g, bx, by, bx + 22, by + 22, 11, color);
        if (mic) {
            PhoneDraw.tinted(g, MIC, bx + 5, by + 5, 12, 12, 32, 32, PhoneTheme.onAccent());
        } else {
            PhoneDraw.centeredText(g, editingId >= 0 ? "✔" : "➤", bx + 11, by + 7, PhoneTheme.onAccent(), 1f, false);
        }
    }

    /** Вместо поля ввода во время записи: «×», мигающая точка, время, громкость, «➤». */
    private void renderRecording(GuiGraphics g, int mouseX, int mouseY, int top) {
        int w = width();
        g.fill(0, top - 2, w, top + INPUT_H + 2, PhoneTheme.background());
        PhoneDraw.panel(g, 6, top, w - 30, top + INPUT_H, PhoneTheme.card());
        if (voiceUploading || recordingSend) {
            PhoneDraw.text(g, MessengerApp.tr("voice.sending"), 14, top + 9, PhoneTheme.secondaryText(), 0.7f, false);
            return;
        }
        PhoneDraw.button(g, 9, top + 5, 16, 16, "×", mouseX, mouseY, PhoneDraw.ButtonStyle.DANGER);
        boolean blink = (System.currentTimeMillis() / 500) % 2 == 0;
        PhoneDraw.roundedRect(g, 30, top + 10, 36, top + 16, 3, blink ? PhoneTheme.danger() : 0x00000000);
        String time = VoiceMessages.time(recorder.recordedMs()) + " / " + VoiceMessages.time(ClientSession.maxVoiceSeconds() * 1000L);
        PhoneDraw.text(g, time, 40, top + 6, PhoneTheme.text(), 0.7f, false);
        int lx = 40;
        int lw = w - 30 - 8 - lx;
        g.fill(lx, top + 17, lx + lw, top + 19, PhoneTheme.separator());
        g.fill(lx, top + 17, lx + Math.round(lw * recorder.level()), top + 19, PhoneTheme.danger());
        int bx = w - 26;
        int by = top + 2;
        boolean hovered = PhoneDraw.inside(mouseX, mouseY, bx, by, 22, 22);
        PhoneDraw.roundedRect(g, bx, by, bx + 22, by + 22, 11, hovered ? PhoneTheme.accentHover() : PhoneTheme.accent());
        PhoneDraw.centeredText(g, "➤", bx + 11, by + 7, PhoneTheme.onAccent(), 1f, false);
    }

    /** Полоска над полем ввода: миниатюра прикреплённого фото, состояние загрузки, «×». */
    private void renderAttachment(GuiGraphics g, int mouseX, int mouseY, int y) {
        int w = width();
        PhoneDraw.panel(g, 6, y, w - 6, y + ATTACH_H - 2, PhoneTheme.card());
        PhotoTextures.Image thumb = PhotoTextures.thumbnail(attachment.photo);
        if (thumb != null) {
            PhoneDraw.texture(g, thumb.id(), 9, y + 2, 18, 18, thumb.width(), thumb.height());
        }
        String status = switch (attachment.state()) {
            case PREPARING, UPLOADING -> MessengerApp.tr("photo.uploading");
            case DONE -> MessengerApp.tr("photo.ready");
            case FAILED -> MessengerApp.tr(attachment.error());
        };
        int color = attachment.state() == ImageUploads.State.FAILED ? PhoneTheme.danger() : PhoneTheme.secondaryText();
        PhoneDraw.text(g, PhoneDraw.ellipsize(status, w - 60, 0.7f), 31, y + 8, color, 0.7f, false);
        PhoneDraw.button(g, w - 24, y + 3, 15, 15, "×", mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL);
    }

    private void attach(PhotoStore.Photo photo) {
        ImageUploads.cancel(attachment);
        attachment = ImageUploads.start(photo, ImageRef.Purpose.CHAT, upload -> {
        });
        input.setFocused(true);
    }

    /** Прикрепить фото из кода (для автоскриншотов). */
    void attachForTest(PhotoStore.Photo photo) {
        attach(photo);
    }

    private void clearAttachment() {
        ImageUploads.cancel(attachment);
        attachment = null;
    }

    private void renderMenu(GuiGraphics g, int mouseX, int mouseY) {
        int mw = 76;
        int rowH = 14;
        int mh = menu.size() * rowH + 4;
        PhoneDraw.roundedRect(g, menuX - 1, menuY, menuX + mw + 2, menuY + mh + 2, 4, 0x40000000);
        PhoneDraw.panel(g, menuX, menuY, menuX + mw, menuY + mh, PhoneTheme.card());
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.get(i);
            int ry = menuY + 2 + i * rowH;
            if (PhoneDraw.inside(mouseX, mouseY, menuX, ry, mw, rowH)) {
                g.fill(menuX + 2, ry, menuX + mw - 2, ry + rowH, PhoneTheme.cardHover());
            }
            PhoneDraw.text(g, item.label(), menuX + 6, ry + 4, item.danger() ? PhoneTheme.danger() : PhoneTheme.text(), 0.75f, false);
        }
    }

    private void clampScroll(int total) {
        int area = areaBottom() - areaTop();
        double max = Math.max(0, total - area);
        scroll = Math.max(0, Math.min(max, scroll));
    }

    // --- Действия ---

    void submit() {
        String text = input.text().strip();
        ImageRef image = null;
        if (attachment != null && editingId < 0) {
            if (attachment.busy()) {
                return; // Дождаться загрузки фото.
            }
            image = attachment.image();
        }
        if (text.isEmpty() && image == null) {
            return;
        }
        if (editingId >= 0) {
            ClientNetwork.send(new MessengerPackets.EditMessage(editingId, text));
            editingId = -1;
        } else {
            MessengerClient.sendMessage(chatId, text, image);
            attachment = null;
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
        menuY = Math.max(areaTop(), Math.min(areaBottom() - mh, y));
    }

    // --- Ввод ---

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (viewer.mouseClicked()) {
            return true;
        }
        if (picker.isOpen()) {
            return picker.mouseClicked(x, y, button);
        }
        if (canAttach() && PhoneDraw.inside(x, y, 6, inputTop() + 2, 18, 22)) {
            stickerPanel = false;
            picker.open();
            return true;
        }
        if (canSticker() && PhoneDraw.inside(x, y, stickerButtonX(), inputTop() + 2, 18, 22)) {
            stickerPanel = !stickerPanel;
            return true;
        }
        if (stickerPanel) {
            for (ButtonHit hit : panelHits) {
                if (button == 0 && PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                    hit.action().run();
                    return true;
                }
            }
            if (PhoneDraw.inside(x, y, 4, inputTop() - 4 - PANEL_H, width() - 8, PANEL_H)) {
                return true;
            }
        }
        if (button == 0 && menu == null) {
            for (ButtonHit hit : stickerHits) {
                if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                    hit.action().run();
                    return true;
                }
            }
        }
        if (attachment != null && PhoneDraw.inside(x, y, width() - 24, inputTop() - ATTACH_H + 1, 15, 15)) {
            clearAttachment();
            return true;
        }
        if (recorder != null) {
            int top = inputTop();
            if (!recordingSend && PhoneDraw.inside(x, y, 9, top + 5, 16, 16)) {
                cancelRecording();
            } else if (PhoneDraw.inside(x, y, width() - 26, top + 2, 22, 22)) {
                finishRecording();
            }
            return true;
        }
        for (ButtonHit hit : buttons) {
            if (button == 0 && PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                hit.action().run();
                return true;
            }
        }
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
            boolean canSend = !input.text().isBlank() || attachment != null;
            if (!canSend && voiceEnabled() && !voiceUploading) {
                startRecording();
            } else {
                submit();
            }
            return true;
        }
        if (input.mouseClicked(x, y, button)) {
            return true;
        }
        if (y < STATUS_H && chat() != null) {
            app.push(new ChatInfoView(app, chatId));
            return true;
        }
        if (button == 0) {
            for (VoiceHit hit : voiceHits) {
                if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                    VoiceMessages.toggle(hit.voice());
                    return true;
                }
            }
            for (ImageHit hit : imageHits) {
                if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                    viewer.open(hit.image());
                    return true;
                }
            }
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
        if (picker.mouseScrolled(amount) || viewer.isOpen()) {
            return true;
        }
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
        if (recorder != null) {
            cancelRecording();
            return true;
        }
        if (stickerPanel) {
            stickerPanel = false;
            return true;
        }
        if (viewer.isOpen()) {
            viewer.close();
            return true;
        }
        if (picker.isOpen()) {
            picker.close();
            return true;
        }
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
