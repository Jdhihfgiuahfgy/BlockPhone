package dev.blockphone.fabric.client.screens;

import dev.blockphone.fabric.client.BlockPhoneClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/**
 * Режим джойстика: телефон в руках набок, клавиши и мышь — приложению на экране в мире (его input.down). Мир виден,
 * игра не на паузе, персонаж стоит. Выход — клавиша джойстика или Esc.
 */
public final class GamepadScreen extends Screen {
    GamepadScreen() {
        super(Component.literal("BlockPhone gamepad"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (!AppDisplays.controllingAny()) {
            onClose();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() || BlockPhoneClient.gameKey.matches(event)) {
            onClose();
        }
        return true; // клавиши — приложению (input.down), не миру
    }

    @Override
    public void removed() {
        AppDisplays.leaveControl();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) { // mc:render — в 26.1 extractRenderState/extractBackground
        // Мир виден полностью.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) { // mc:render — в 26.1 extractRenderState/extractBackground
        String hint = AppDisplays.controlHint();
        int w = font.width(hint);
        int x = (width - w) / 2, y = height - 58;
        g.fill(x - 6, y - 4, x + w + 6, y + 12, 0xA0000000);
        g.drawString(font, hint, x, y, 0xFFFFFFFF);
    }
}
