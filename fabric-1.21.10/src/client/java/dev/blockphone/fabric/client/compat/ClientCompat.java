package dev.blockphone.fabric.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Места клиентского API, которые отличаются между версиями Minecraft — версия для 1.21.10. */
public final class ClientCompat {
    /** Задать экрану размер окна и разложить его (без показа). */
    public static void initScreen(Screen screen, Minecraft mc, int width, int height) {
        screen.init(mc, width, height);
    }

    private ClientCompat() {
    }
}
