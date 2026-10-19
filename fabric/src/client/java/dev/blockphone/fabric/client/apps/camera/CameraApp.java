package dev.blockphone.fabric.client.apps.camera;

import dev.blockphone.fabric.client.camera.CameraMode;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.AppRegistry;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Иконка «Камера» на рабочем столе: закрывает телефон и включает полноэкранный видоискатель
 * ({@link CameraMode}). Вне мира показывает подсказку.
 */
public final class CameraApp extends PhoneApp {

    public CameraApp() {
        super("camera");
    }

    @Override
    public void onShown() {
        if (Minecraft.getInstance().level == null || phone == null) {
            return;
        }
        // После выхода из камеры телефон откроется на рабочем столе.
        PhoneState.setCurrent(PhoneState.HOME);
        phone.closeImmediately();
        CameraMode.enter(true);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        AppRegistry.Entry entry = AppRegistry.get(id());
        int size = 48;
        int x = (contentWidth() - size) / 2;
        int y = contentHeight() / 2 - 60;
        if (entry != null) {
            PhoneDraw.texture(g, entry.icon(), x, y, size, size, 64, 64);
        }
        PhoneDraw.wrapped(g, PhoneLang.tr("camera.no_world"), 8, y + size + 10, contentWidth() - 16,
                PhoneTheme.secondaryText(), 0.75f, true);
    }
}
