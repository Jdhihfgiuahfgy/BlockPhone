package dev.blockphone.fabric.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Места клиентского API, которые отличаются между версиями Minecraft — версия для 1.21.10. */
public final class ClientCompat {
    /** Задать экрану размер окна и разложить его (без показа). */
    public static void initScreen(Screen screen, Minecraft mc, int width, int height) {
        screen.init(mc, width, height);
    }

    /** Настройки тестового мира DevShots (творческий, мирный, с читами). */
    public static net.minecraft.world.level.LevelSettings devLevelSettings(String name) {
        return new net.minecraft.world.level.LevelSettings(name, net.minecraft.world.level.GameType.CREATIVE, false,
                net.minecraft.world.Difficulty.PEACEFUL, true,
                new net.minecraft.world.level.gamerules.GameRules(net.minecraft.world.level.WorldDataConfiguration.DEFAULT.enabledFeatures()),
                net.minecraft.world.level.WorldDataConfiguration.DEFAULT);
    }

    /** Системное всплывающее окно (будильник): заголовок и текст в несколько строк. */
    public static void systemToast(Minecraft mc, net.minecraft.network.chat.Component title, net.minecraft.network.chat.Component text) {
        mc.getToastManager().addToast(net.minecraft.client.gui.components.toasts.SystemToast.multiline(mc,
                net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, text));
    }

    private ClientCompat() {
    }
}
