package dev.blockphone.fabric.client.phone;

import com.mojang.blaze3d.platform.InputConstants;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.BlockPhoneClient;
import dev.blockphone.fabric.client.camera.CameraMode;
import dev.blockphone.fabric.client.compat.ClientCompat;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

/**
 * Режим «на ходу»: маленький телефон прикреплён в углу экрана, а игра идёт как обычно — мышь крутит камеру, можно ходить,
 * прыгать, драться. Пока зажата клавиша курсора (по умолчанию левый Alt), появляется курсор и телефоном можно пользоваться;
 * отпустил — снова управляешь камерой. Если в телефоне печатаешь текст, курсор остаётся, пока поле в фокусе.
 * Включается/выключается клавишей «Телефон «на ходу»» (по умолчанию J).
 */
public final class PhonePin {
    private static @Nullable PhoneScreen screen;
    /** Телефон убирается с экрана только ради возврата мыши игре — приложения не закрываются. */
    private static boolean hiding;
    /** Для автоскриншотов: как будто клавиша курсора зажата. */
    public static boolean debugCursor;

    public static void init() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "pinned_phone"), PhonePin::renderHud);
    }

    public static boolean active() {
        return screen != null;
    }

    static boolean pinned(PhoneScreen candidate) {
        return candidate != null && candidate == screen;
    }

    static boolean hiding() {
        return hiding;
    }

    public static void toggle(Minecraft mc) {
        if (screen != null) {
            unpin(mc);
        } else {
            pin(mc);
        }
    }

    public static void pin(Minecraft mc) {
        if (screen != null || mc.player == null || CameraMode.active()) {
            return;
        }
        if (mc.screen instanceof PhoneScreen) {
            mc.setScreen(null);
        }
        screen = new PhoneScreen();
        ClientCompat.initScreen(screen, mc, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
    }

    public static void unpin(Minecraft mc) {
        PhoneScreen s = screen;
        screen = null;
        if (s == null) {
            return;
        }
        if (mc.screen == s) {
            mc.setScreen(null);
        } else {
            s.removed(); // приложения узнают, что телефон закрыт
        }
    }

    /** Каждый такт: показать курсор, пока зажата клавиша, иначе вернуть мышь игре; размер — по окну. */
    public static void tick(Minecraft mc) {
        PhoneScreen s = screen;
        if (s == null) {
            return;
        }
        if (mc.player == null) {
            unpin(mc);
            return;
        }
        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        if (s.width != w || s.height != h) {
            ClientCompat.initScreen(s, mc, w, h);
        }
        boolean cursor = debugCursor || keyDown(mc, BlockPhoneClient.cursorKey);
        if (mc.screen == null) {
            if (cursor && !CameraMode.active()) {
                mc.setScreen(s);
            } else {
                s.tick();
            }
        } else if (mc.screen == s && !cursor && !s.capturesKeyboard() && !s.dialogOpen()) {
            hiding = true;
            mc.setScreen(null);
            hiding = false;
        }
    }

    private static boolean keyDown(Minecraft mc, KeyMapping key) {
        InputConstants.Key bound = KeyBindingHelper.getBoundKeyOf(key);
        return bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() != InputConstants.UNKNOWN.getValue()
                && InputConstants.isKeyDown(mc.getWindow(), bound.getValue());
    }

    /** Телефон на экране, пока мышь у игры (без курсора — наведения нет). */
    private static void renderHud(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        PhoneScreen s = screen;
        if (s == null || mc.screen == s || mc.options.hideGui || CameraMode.active()) {
            return;
        }
        s.render(g, -10_000, -10_000, delta.getGameTimeDeltaPartialTick(false));
    }

    private PhonePin() {
    }
}
