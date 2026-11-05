package dev.blockphone.fabric.client.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;

/** Места клиентского API, которые отличаются между версиями Minecraft — версия для 26.1+. */
public final class ClientCompat {
    /** Задать экрану размер окна и разложить его (без показа). */
    public static void initScreen(Screen screen, Minecraft mc, int width, int height) {
        screen.init(width, height);
    }

    /** Настройки тестового мира DevShots (творческий, мирный, с читами). В 26.1 правила игры — отдельно от LevelSettings. */
    public static LevelSettings devLevelSettings(String name) {
        return new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
                WorldDataConfiguration.DEFAULT);
    }

    /** Системное всплывающее окно (будильник): заголовок и текст в несколько строк. */
    public static void systemToast(Minecraft mc, net.minecraft.network.chat.Component title, net.minecraft.network.chat.Component text) {
        mc.getToastManager().addToast(net.minecraft.client.gui.components.toasts.SystemToast.multiline(mc,
                net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, text));
    }

    private ClientCompat() {
    }
}
