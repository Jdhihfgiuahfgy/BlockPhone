package dev.blockphone.fabric.client.phone;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.VoiceData.CallInfo;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.voice.CallClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

/**
 * Входящий звонок поверх любого приложения телефона: кто звонит, «Отклонить» и «Ответить».
 * Координаты — экранные (весь экран телефона).
 */
final class CallOverlay {
    private static final int BUTTON = 30;
    private static final Identifier CALL = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/voice/call.png");
    private static final Identifier HANGUP = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/voice/hangup.png");

    static boolean visible() {
        return CallClient.incoming() != null;
    }

    private static int buttonsY() {
        return PhoneLayout.SCREEN_H - 90;
    }

    private static int declineX() {
        return PhoneLayout.SCREEN_W / 2 - 50;
    }

    private static int acceptX() {
        return PhoneLayout.SCREEN_W / 2 + 50 - BUTTON;
    }

    static void render(GuiGraphics g, int mx, int my) {
        CallInfo call = CallClient.incoming();
        if (call == null) {
            return;
        }
        int w = PhoneLayout.SCREEN_W;
        int h = PhoneLayout.SCREEN_H;
        g.fillGradient(0, 0, w, h, 0xF0142A22, 0xF00B0F18);
        float cx = w / 2f;
        PhoneDraw.centeredText(g, PhoneLang.tr("call.incoming"), cx, 50, 0xFFB0F0C8, 0.75f, false);
        UserInfo caller = CallClient.caller(call);
        int avatar = 48;
        int ay = 70;
        // Пульсирующее кольцо.
        float pulse = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 250.0));
        int ring = 4 + Math.round(pulse * 4);
        PhoneDraw.roundedRect(g, (int) cx - avatar / 2 - ring, ay - ring, (int) cx + avatar / 2 + ring, ay + avatar + ring,
                avatar / 2 + ring, 0x403FB97A);
        if (caller != null) {
            Avatars.drawUser(g, caller, (int) cx - avatar / 2, ay, avatar);
        }
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(CallClient.title(call), w - 20, 1.2f), cx, ay + avatar + 12, 0xFFFFFFFF, 1.2f, true);
        if (caller != null && call.participants().size() > 1) {
            PhoneDraw.centeredText(g, PhoneLang.tr("call.participants", call.participants().size()), cx, ay + avatar + 26, 0xFFB0B8C8,
                    0.7f, false);
        }
        int by = buttonsY();
        button(g, declineX(), by, 0xFFE5534B, HANGUP, PhoneLang.tr("call.decline"), mx, my);
        button(g, acceptX(), by, 0xFF3FB97A, CALL, PhoneLang.tr("call.answer"), mx, my);
    }

    private static void button(GuiGraphics g, int x, int y, int color, Identifier icon, String label, int mx, int my) {
        boolean hover = PhoneDraw.inside(mx, my, x, y, BUTTON, BUTTON);
        PhoneDraw.roundedRect(g, x, y, x + BUTTON, y + BUTTON, BUTTON / 2, hover ? PhoneTheme.mix(color, 0xFFFFFFFF, 0.2f) : color);
        PhoneDraw.tinted(g, icon, x + 7, y + 7, BUTTON - 14, BUTTON - 14, 32, 32, 0xFFFFFFFF);
        PhoneDraw.centeredText(g, label, x + BUTTON / 2f, y + BUTTON + 5, 0xFFE6ECF5, 0.65f, false);
    }

    /** Клик, пока виден входящий звонок. true — клик поглощён. */
    static boolean mouseClicked(double mx, double my) {
        CallInfo call = CallClient.incoming();
        if (call == null) {
            return false;
        }
        int by = buttonsY();
        if (PhoneDraw.inside(mx, my, declineX(), by, BUTTON, BUTTON)) {
            CallClient.answer(call.chatId(), false);
        } else if (PhoneDraw.inside(mx, my, acceptX(), by, BUTTON, BUTTON)) {
            CallClient.answer(call.chatId(), true);
        }
        return true;
    }

    private CallOverlay() {
    }
}
