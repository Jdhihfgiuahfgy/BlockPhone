package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.VoiceData.CallInfo;
import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.voice.CallAudio;
import dev.blockphone.fabric.client.voice.CallClient;
import dev.blockphone.fabric.client.voice.VoiceMessages;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Меню звонка: название и длительность, участники (обводка — говорит, «×» — микрофон выключен, «)))» — громкая связь),
 * кто ещё не ответил; кнопки «Микрофон», «Громкая связь», «Звук» (не слышать собеседников), громкость звонка и «Положить».
 */
final class CallView extends MessengerView {
    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private final long chatId;
    private final List<Hit> hits = new ArrayList<>();
    /** Какой ползунок тянут: -1 — никакой, 0 — громкость, 1 — усиление микрофона. */
    private int dragging = -1;
    private int sliderX;
    private int sliderW;

    CallView(MessengerApp app, long chatId) {
        super(app);
        this.chatId = chatId;
    }

    @Override
    String title() {
        CallInfo call = CallClient.call(chatId);
        return call != null ? CallClient.title(call) : MessengerApp.tr("call.title");
    }

    @Override
    void tick() {
        // Звонок закончился — назад к переписке.
        if (!CallClient.inCall(chatId) && CallClient.call(chatId) == null) {
            app.pop();
        }
    }

    @Override
    void render(GuiGraphics g, int mx, int my) {
        hits.clear();
        int w = width();
        CallInfo call = CallClient.call(chatId);
        if (call == null) {
            PhoneDraw.centeredText(g, MessengerApp.tr("call.ended"), w / 2f, height() / 2f, PhoneTheme.secondaryText(), 0.8f, false);
            return;
        }
        boolean mine = CallClient.inCall(chatId);
        int y = 8;
        String state = mine ? (call.participants().size() > 1 ? VoiceMessages.time(CallClient.duration()) : MessengerApp.tr("call.calling"))
                : MessengerApp.tr("call.ongoing", call.participants().size());
        PhoneDraw.centeredText(g, state, w / 2f, y, PhoneTheme.secondaryText(), 0.7f, false);
        y += 14;

        // Участники: сетка аватаров.
        List<UserInfo> people = new ArrayList<>(call.participants());
        people.addAll(call.invited());
        int cols = people.size() <= 2 ? Math.max(1, people.size()) : 3;
        int cell = Math.min(52, (w - 16) / cols);
        int avatar = cell - 18;
        int rows = (people.size() + cols - 1) / cols;
        int gridX = (w - cols * cell) / 2;
        for (int i = 0; i < people.size(); i++) {
            UserInfo u = people.get(i);
            boolean waiting = i >= call.participants().size();
            int cx = gridX + (i % cols) * cell + (cell - avatar) / 2;
            int cy = y + (i / cols) * (cell + 8);
            boolean self = MessengerClient.isSelf(u.uuid());
            boolean talking = !waiting && (self ? CallAudio.speaking() : CallAudio.speaking(u.uuid()));
            if (talking) {
                PhoneDraw.rawRoundedRect(g, cx - 2, cy - 2, cx + avatar + 2, cy + avatar + 2, avatar / 2 + 2, PhoneTheme.online());
            }
            Avatars.drawUser(g, u, cx, cy, avatar);
            if (waiting) {
                g.fill(cx, cy, cx + avatar, cy + avatar, 0x90000000);
                PhoneDraw.centeredText(g, "…", cx + avatar / 2f, cy + avatar / 2f - 4, 0xFFFFFFFF, 0.9f, false);
            }
            if (call.muted(u.uuid())) {
                PhoneDraw.roundedRect(g, cx + avatar - 9, cy + avatar - 9, cx + avatar + 1, cy + avatar + 1, 5, PhoneTheme.danger());
                PhoneDraw.centeredText(g, "×", cx + avatar - 4, cy + avatar - 7.5f, 0xFFFFFFFF, 0.6f, false);
            }
            if (call.speaker(u.uuid())) {
                PhoneDraw.roundedRect(g, cx - 1, cy + avatar - 9, cx + 9, cy + avatar + 1, 5, PhoneTheme.accent());
                PhoneDraw.centeredText(g, ")", cx + 4, cy + avatar - 7.5f, PhoneTheme.onAccent(), 0.6f, false);
            }
            String name = (self ? MessengerApp.tr("call.you") : u.name());
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(name, cell - 2, 0.55f), cx + avatar / 2f, cy + avatar + 3,
                    waiting ? PhoneTheme.secondaryText() : PhoneTheme.text(), 0.55f, false);
        }
        y += rows * (cell + 8) + 4;

        if (!mine) {
            boolean ringing = call.invited().stream().anyMatch(u -> MessengerClient.isSelf(u.uuid()));
            int bw = (w - 20) / 2;
            if (ringing) {
                button(g, 8, y, bw, 18, MessengerApp.tr("call.decline"), mx, my, PhoneDraw.ButtonStyle.DANGER, () -> CallClient.answer(chatId, false));
                button(g, 12 + bw, y, bw, 18, MessengerApp.tr("call.answer"), mx, my, PhoneDraw.ButtonStyle.PRIMARY,
                        () -> CallClient.answer(chatId, true));
            } else {
                button(g, 8, y, w - 16, 18, MessengerApp.tr("call.join"), mx, my, PhoneDraw.ButtonStyle.PRIMARY, () -> CallClient.start(chatId));
            }
            return;
        }

        // Уровень своего микрофона.
        if (!CallClient.muted()) {
            float level = CallAudio.micLevel();
            PhoneDraw.rawRoundedRect(g, 20, y, w - 20, y + 3, 1, PhoneTheme.field());
            PhoneDraw.rawRoundedRect(g, 20, y, 20 + Math.round((w - 40) * level), y + 3, 1, PhoneTheme.online());
        }
        y += 8;
        String micError = CallAudio.micError();
        if (micError != null) {
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(MessengerApp.tr(micError), w - 12, 0.55f), w / 2f, y, PhoneTheme.danger(), 0.55f, false);
            y += 10;
        }

        // Кнопки: микрофон, громкая связь, звук.
        int bw = (w - 24) / 3;
        toggle(g, 8, y, bw, MessengerApp.tr(CallClient.muted() ? "call.mic_off" : "call.mic_on"), !CallClient.muted(), mx, my,
                () -> CallClient.setMuted(!CallClient.muted()));
        toggle(g, 12 + bw, y, bw, MessengerApp.tr("call.speaker"), CallClient.speaker(), mx, my,
                () -> CallClient.setSpeaker(!CallClient.speaker()));
        toggle(g, 16 + 2 * bw, y, bw, MessengerApp.tr(CallClient.deafened() ? "call.sound_off" : "call.sound_on"), !CallClient.deafened(),
                mx, my, () -> CallClient.setDeafened(!CallClient.deafened()));
        y += 24;

        // Громкость звонка и усиление микрофона (0..400 %), шумодав.
        PhoneSettings s = PhoneSettings.get();
        y = slider(g, y, w, MessengerApp.tr("call.volume"), s.callVolume, 0);
        y = slider(g, y, w, MessengerApp.tr("call.mic_gain"), s.callMicGain, 1);
        toggle(g, 8, y, w - 16, MessengerApp.tr(s.callNoiseSuppression ? "call.noise_on" : "call.noise_off"), s.callNoiseSuppression, mx, my,
                () -> {
                    s.callNoiseSuppression = !s.callNoiseSuppression;
                    s.save();
                });
        y += 24;
        if (CallClient.speaker()) {
            PhoneDraw.wrapped(g, MessengerApp.tr("call.speaker_hint"), 10, y, w - 20, PhoneTheme.secondaryText(), 0.5f, true);
            y += PhoneDraw.wrappedHeight(MessengerApp.tr("call.speaker_hint"), w - 20, 0.5f) + 4;
        }

        int hy = Math.max(y + 4, height() - 30);
        button(g, w / 2 - 40, hy, 80, 20, MessengerApp.tr("call.hang_up"), mx, my, PhoneDraw.ButtonStyle.DANGER, () -> CallClient.leave(chatId));
    }

    private void toggle(GuiGraphics g, int x, int y, int w, String label, boolean on, int mx, int my, Runnable action) {
        button(g, x, y, w, 20, label, mx, my, on ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL, action);
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, PhoneDraw.ButtonStyle style, Runnable action) {
        PhoneDraw.button(g, x, y, w, h, PhoneDraw.ellipsize(label, w - 4, 0.65f), mx, my, style);
        hits.add(new Hit(x, y, w, h, action));
    }

    /** Ползунок 0..400 %: подпись, значение, дорожка; возвращает y под ним. */
    private int slider(GuiGraphics g, int y, int w, String label, int value, int index) {
        PhoneDraw.text(g, label, 10, y, PhoneTheme.text(), 0.6f, false);
        PhoneDraw.rightText(g, value + "%", w - 10, y, PhoneTheme.secondaryText(), 0.6f);
        y += 10;
        sliderX = 12;
        sliderW = w - 24;
        float f = Math.max(0, Math.min(1, value / 400f));
        PhoneDraw.rawRoundedRect(g, sliderX, y, sliderX + sliderW, y + 3, 1, PhoneTheme.field());
        PhoneDraw.rawRoundedRect(g, sliderX, y, sliderX + Math.round(sliderW * f), y + 3, 1, PhoneTheme.accent());
        int knob = sliderX + Math.round(sliderW * f);
        PhoneDraw.rawRoundedRect(g, knob - 4, y - 3, knob + 4, y + 5, 4, PhoneTheme.accent());
        hits.add(new Hit(sliderX - 4, y - 6, sliderW + 8, 15, () -> dragging = index));
        return y + 12;
    }

    private void setSlider(double x) {
        int value = (int) Math.round(Math.max(0, Math.min(1, (x - sliderX) / Math.max(1, sliderW))) * 400 / 5) * 5;
        PhoneSettings s = PhoneSettings.get();
        if (dragging == 0) {
            s.callVolume = value;
        } else if (dragging == 1) {
            s.callMicGain = value;
        }
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (button != 0) {
            return false;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                hit.action().run();
                if (dragging >= 0) {
                    setSlider(x);
                }
                return true;
            }
        }
        return false;
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        if (dragging >= 0) {
            setSlider(x);
            return true;
        }
        return false;
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        if (dragging >= 0) {
            dragging = -1;
            PhoneSettings.get().save();
            return true;
        }
        return false;
    }
}
