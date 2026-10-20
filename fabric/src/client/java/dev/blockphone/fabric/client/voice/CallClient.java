package dev.blockphone.fabric.client.voice;

import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.VoiceData.CallInfo;
import dev.blockphone.common.protocol.packet.VoicePackets;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Звонки на клиенте: идущие звонки в чатах, входящий вызов (звонок-«рингтон» и уведомление), свой звонок.
 * Сам голос передаёт Simple Voice Chat — сервер переводит участников в общую группу. Только поток рендера.
 */
public final class CallClient {
    private static final long RING_INTERVAL_MS = 2200;

    private static final Map<Long, CallInfo> CALLS = new HashMap<>();
    private static long lastRing;
    private static long notifiedChat = -1;
    private static long joinedAt;

    public static @Nullable CallInfo call(long chatId) {
        return CALLS.get(chatId);
    }

    /** Звонок, в котором игрок сейчас участвует. */
    public static @Nullable CallInfo myCall() {
        UUID self = MessengerClient.self();
        for (CallInfo call : CALLS.values()) {
            if (call.participants().stream().anyMatch(u -> u.uuid().equals(self))) {
                return call;
            }
        }
        return null;
    }

    /** Входящий вызов (игроку звонят, он ещё не ответил). */
    public static @Nullable CallInfo incoming() {
        UUID self = MessengerClient.self();
        for (CallInfo call : CALLS.values()) {
            if (call.invited().stream().anyMatch(u -> u.uuid().equals(self))) {
                return call;
            }
        }
        return null;
    }

    public static boolean inCall(long chatId) {
        CallInfo mine = myCall();
        return mine != null && mine.chatId() == chatId;
    }

    /** Название звонка: группа — её название, личный — имя собеседника. */
    public static String title(CallInfo call) {
        ChatInfo chat = MessengerClient.chat(call.chatId());
        return chat != null ? MessengerClient.title(chat) : PhoneLang.tr("call.title");
    }

    /** Кто звонит (первый участник). */
    public static @Nullable UserInfo caller(CallInfo call) {
        return call.participants().isEmpty() ? null : call.participants().get(0);
    }

    /** Сколько длится свой звонок, мс. */
    public static long duration() {
        return joinedAt > 0 ? System.currentTimeMillis() - joinedAt : 0;
    }

    // --- Действия ---

    public static void start(long chatId) {
        ClientNetwork.send(new VoicePackets.StartCall(chatId));
    }

    public static void answer(long chatId, boolean accept) {
        ClientNetwork.send(new VoicePackets.AnswerCall(chatId, accept));
    }

    public static void leave(long chatId) {
        ClientNetwork.send(new VoicePackets.LeaveCall(chatId));
    }

    // --- Пакеты ---

    public static void handle(VoicePackets.CallState state) {
        CallInfo call = state.call();
        boolean wasMine = inCall(call.chatId());
        if (call.active()) {
            CALLS.put(call.chatId(), call);
        } else {
            CALLS.remove(call.chatId());
        }
        boolean mine = inCall(call.chatId());
        if (mine && !wasMine) {
            joinedAt = System.currentTimeMillis();
        } else if (!mine && wasMine) {
            joinedAt = 0;
            toast(PhoneLang.tr("call.ended"), title(call), null);
        }
        CallInfo ringing = incoming();
        if (ringing == null) {
            notifiedChat = -1;
        } else if (ringing.chatId() != notifiedChat) {
            notifiedChat = ringing.chatId();
            toast(PhoneLang.tr("call.incoming"), title(ringing), caller(ringing));
        }
    }

    /** Каждый тик: «рингтон» входящего вызова. */
    public static void tick() {
        if (incoming() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRing > RING_INTERVAL_MS) {
            lastRing = now;
            Minecraft mc = Minecraft.getInstance();
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.2f, 0.8f));
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.5f, 0.6f));
        }
    }

    public static void reset() {
        CALLS.clear();
        notifiedChat = -1;
        joinedAt = 0;
    }

    private static void toast(String title, String text, @Nullable UserInfo user) {
        Minecraft.getInstance().getToastManager().addToast(new CallToast(title, text, user));
    }

    /** Уведомление о звонке (в стиле телефона). */
    private static final class CallToast implements Toast {
        private final String title;
        private final String text;
        private final @Nullable UserInfo user;
        private Visibility visibility = Visibility.HIDE;
        private long firstShown = -1;

        CallToast(String title, String text, @Nullable UserInfo user) {
            this.title = title;
            this.text = text;
            this.user = user;
        }

        @Override
        public Visibility getWantedVisibility() {
            return visibility;
        }

        @Override
        public void update(ToastManager manager, long time) {
            if (firstShown < 0) {
                firstShown = time;
            }
            visibility = time - firstShown < 6000 * manager.getNotificationDisplayTimeMultiplier() ? Visibility.SHOW : Visibility.HIDE;
        }

        @Override
        public void render(GuiGraphics g, Font font, long time) {
            PhoneDraw.rawRoundedRect(g, 0, 0, width(), height(), 4, PhoneTheme.bodyEdge());
            PhoneDraw.rawRoundedRect(g, 1, 1, width() - 1, height() - 1, 3, 0xF0111725);
            g.fill(1, 1, 3, height() - 1, PhoneTheme.online());
            if (user != null) {
                Avatars.drawUser(g, user, 7, 7, 18);
            } else {
                PhoneDraw.roundedRect(g, 7, 7, 25, 25, 9, PhoneTheme.online());
            }
            PhoneDraw.text(g, PhoneDraw.ellipsize(title, width() - 36, 1f), 31, 7, 0xFFFFFFFF, 1f, false);
            PhoneDraw.text(g, PhoneDraw.ellipsize(text, width() - 36, 1f), 31, 18, 0xFFB0B0B8, 1f, false);
        }
    }

    private CallClient() {
    }
}
