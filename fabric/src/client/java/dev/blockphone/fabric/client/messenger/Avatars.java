package dev.blockphone.fabric.client.messenger;

import dev.blockphone.common.protocol.data.MessengerData.ChatInfo;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Аватарки: лицо скина игрока (если он на этом сервере — настоящий скин, иначе стандартный),
 * для групп — цветной квадрат с первой буквой названия.
 */
public final class Avatars {
    private static final int[] GROUP_COLORS = {0xFF5E81AC, 0xFFBF616A, 0xFFA3BE8C, 0xFFD08770, 0xFFB48EAD, 0xFF88C0D0};

    public static void drawUser(GuiGraphics g, UserInfo user, int x, int y, int size) {
        PlayerFaceRenderer.draw(g, skin(user), x, y, size);
    }

    public static void drawChat(GuiGraphics g, ChatInfo chat, int x, int y, int size) {
        UserInfo partner = MessengerClient.partner(chat);
        if (partner != null) {
            drawUser(g, partner, x, y, size);
            return;
        }
        String title = MessengerClient.title(chat);
        int color = GROUP_COLORS[Math.floorMod(title.hashCode(), GROUP_COLORS.length)];
        PhoneDraw.roundedRect(g, x, y, x + size, y + size, size / 4, color);
        String letter = title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase();
        float scale = size >= 20 ? 1.5f : 1f;
        PhoneDraw.centeredText(g, letter, x + size / 2f, y + size / 2f - 4 * scale, 0xFFFFFFFF, scale, true);
    }

    /** Аватар паблика: квадрат его цвета с первой буквой названия. */
    /** Аватар паблика: своё фото (обрезка по центру) или цветной квадрат с буквой. */
    public static void drawPublic(GuiGraphics g, String title, int color, dev.blockphone.common.protocol.data.ImageRef avatar, int x, int y,
                                  int size) {
        if (avatar == null) {
            drawPublic(g, title, color, x, y, size);
            return;
        }
        dev.blockphone.fabric.client.ui.Images.drawRounded(g, avatar, x, y, size, size, Math.min(size / 4, PhoneTheme.RADIUS * 2),
                dev.blockphone.fabric.client.ui.Images.THUMB);
    }

    public static void drawPublic(GuiGraphics g, String title, int color, int x, int y, int size) {
        PhoneDraw.roundedRect(g, x, y, x + size, y + size, size / 4, color | 0xFF000000);
        String letter = title.isEmpty() ? "?" : title.substring(0, 1).toUpperCase();
        float scale = size >= 28 ? 2f : size >= 20 ? 1.5f : size >= 14 ? 1f : 0.75f;
        PhoneDraw.centeredText(g, letter, x + size / 2f, y + size / 2f - 4 * scale, 0xFFFFFFFF, scale, true);
    }

    /** Онлайн-индикатор: зелёная точка в углу аватарки. */
    public static void drawOnlineDot(GuiGraphics g, int x, int y, int size, int background) {
        int d = Math.max(4, size / 4);
        int cx = x + size - d;
        int cy = y + size - d;
        PhoneDraw.roundedRect(g, cx - 1, cy - 1, cx + d + 1, cy + d + 1, (d + 2) / 2, background);
        PhoneDraw.roundedRect(g, cx, cy, cx + d, cy + d, d / 2, PhoneTheme.online());
    }

    /** Скин игрока (с сервера или стандартный по UUID). */
    public static PlayerSkin skinOf(UserInfo user) {
        return skin(user);
    }

    private static PlayerSkin skin(UserInfo user) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            PlayerInfo info = connection.getPlayerInfo(user.uuid());
            if (info != null) {
                return info.getSkin();
            }
        }
        return DefaultPlayerSkin.get(user.uuid());
    }

    private Avatars() {
    }
}
