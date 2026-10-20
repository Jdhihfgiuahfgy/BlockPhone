package dev.blockphone.fabric.client.social;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.SocialData.ProfileLook;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.ui.Images;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Оформление личной страницы: аватар (голова или фигура из скина в одной из поз на выбранном фоне) и баннер (градиент или фото).
 * Фигура рисуется настоящей моделью игрока ({@code submitSkinRenderState}); на каждую позу — своя модель (широкая и тонкая),
 * повёрнутая один раз: модель рисуется позже в кадре, поэтому её нельзя переставлять между вызовами.
 */
public final class ProfileLooks {
    /** Фоны аватара: верх → низ. */
    private static final int[][] BACKGROUNDS = {
            {0xFF3D7BFF, 0xFF7A4DFF}, {0xFF1FB57A, 0xFF0E6E8C}, {0xFFFF8A3D, 0xFFE0405A}, {0xFF2B364C, 0xFF111725},
            {0xFFFFD25A, 0xFFFF7A3D}, {0xFF8EC5FC, 0xFFE0C3FC}, {0xFF56AB2F, 0xFFA8E063}, {0xFFDA4453, 0xFF89216B},
            {0xFF00C9FF, 0xFF92FE9D}, {0xFF232526, 0xFF414345}, {0xFFF7797D, 0xFFFBD786}, {0xFF6A3093, 0xFFA044FF}};
    /** Градиенты баннера: слева → справа. */
    private static final int[][] BANNERS = {
            {0xFF3D7BFF, 0xFF7A4DFF}, {0xFF0F2027, 0xFF2C5364}, {0xFFFF5F6D, 0xFFFFC371}, {0xFF11998E, 0xFF38EF7D},
            {0xFF8E2DE2, 0xFF4A00E0}, {0xFFF953C6, 0xFFB91D73}, {0xFF1D2B64, 0xFFF8CDDA}, {0xFF373B44, 0xFF4286F4},
            {0xFFE65C00, 0xFFF9D423}, {0xFF134E5E, 0xFF71B280}};
    /** Наклон фигуры вокруг вертикали для каждой позы (градусы). */
    private static final float[] TURN = {25, 20, 35, 15, 40, 30, 20, 25};

    private static PlayerModel[] wide;
    private static PlayerModel[] slim;

    public static String poseKey(int pose) {
        return "profile.pose." + Math.floorMod(pose, ProfileLook.POSES);
    }

    public static int backgroundTop(int index) {
        return BACKGROUNDS[Math.floorMod(index, BACKGROUNDS.length)][0];
    }

    public static int backgroundBottom(int index) {
        return BACKGROUNDS[Math.floorMod(index, BACKGROUNDS.length)][1];
    }

    /** Аватар по оформлению: квадрат size×size со скруглением. */
    public static void drawAvatar(GuiGraphics g, UserInfo user, ProfileLook look, int x, int y, int size) {
        int[] bg = BACKGROUNDS[Math.floorMod(look.background(), BACKGROUNDS.length)];
        int r = Math.max(2, size / 6);
        PhoneDraw.rawRoundedRect(g, x, y, x + size, y + size, r, bg[1]);
        g.fillGradient(x + r / 2, y + r / 2, x + size - r / 2, y + size - r / 2, bg[0], bg[1]);
        if (look.avatarType() == ProfileLook.AVATAR_HEAD) {
            int face = Math.round(size * 0.7f);
            Avatars.drawUser(g, user, x + (size - face) / 2, y + (size - face) / 2, face);
            return;
        }
        PlayerSkin skin = Avatars.skinOf(user);
        PlayerModel model = model(skin.model() == PlayerModelType.SLIM, look.pose());
        // Модель рисуется отдельной картинкой в экранных координатах: текущий сдвиг/масштаб телефона применяем сами.
        org.joml.Vector2f a = g.pose().transformPosition(new org.joml.Vector2f(x, y + size / 20f));
        org.joml.Vector2f b = g.pose().transformPosition(new org.joml.Vector2f(x + size, y + size + size / 20f));
        float px = b.x - a.x;
        if (px < 2) {
            return;
        }
        float scale = 0.9f * px / 2.125f;
        g.submitSkinRenderState(model, skin.body().texturePath(), scale, -5f, TURN[look.pose()], -1.0625f, Math.round(a.x),
                Math.round(a.y), Math.round(b.x), Math.round(b.y));
    }

    /** Баннер: фото (обрезка по центру) или градиент; снизу лёгкое затемнение. */
    public static void drawBanner(GuiGraphics g, ProfileLook look, int x, int y, int w, int h) {
        if (look.bannerImage() != null) {
            Images.draw(g, look.bannerImage(), x, y, w, h, Images.FULL);
        } else {
            int[] c = BANNERS[Math.floorMod(look.bannerStyle(), BANNERS.length)];
            int steps = Math.max(1, w / 2);
            for (int i = 0; i < steps; i++) {
                int x0 = x + i * w / steps;
                int x1 = x + (i + 1) * w / steps;
                g.fill(x0, y, x1, y + h, dev.blockphone.fabric.client.phone.PhoneTheme.mix(c[0], c[1], i / (float) (steps - 1 == 0 ? 1 : steps - 1)));
            }
        }
        g.fillGradient(x, y + h / 2, x + w, y + h, 0x00000000, 0x50000000);
    }

    private static PlayerModel model(boolean isSlim, int pose) {
        if (wide == null) {
            wide = new PlayerModel[ProfileLook.POSES];
            slim = new PlayerModel[ProfileLook.POSES];
            var models = Minecraft.getInstance().getEntityModels();
            for (int i = 0; i < ProfileLook.POSES; i++) {
                wide[i] = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER), false);
                slim[i] = new PlayerModel(models.bakeLayer(ModelLayers.PLAYER_SLIM), true);
                pose(wide[i], i);
                pose(slim[i], i);
            }
        }
        return (isSlim ? slim : wide)[Math.floorMod(pose, ProfileLook.POSES)];
    }

    /**
     * Позы: 0 — стоит, 1 — машет рукой, 2 — идёт, 3 — руки вверх, 4 — показывает вперёд, 5 — сидит, 6 — думает, 7 — герой
     * (руки в стороны, голова чуть вверх).
     */
    private static void pose(PlayerModel m, int pose) {
        float pi = (float) Math.PI;
        switch (pose) {
            case 1 -> {
                m.rightArm.zRot = 2.6f;
                m.rightArm.xRot = -0.1f;
                m.leftArm.zRot = -0.1f;
                m.head.zRot = -0.12f;
            }
            case 2 -> {
                m.rightArm.xRot = 0.6f;
                m.leftArm.xRot = -0.6f;
                m.rightLeg.xRot = -0.55f;
                m.leftLeg.xRot = 0.55f;
            }
            case 3 -> {
                m.rightArm.zRot = 2.8f;
                m.leftArm.zRot = -2.8f;
                m.head.xRot = -0.2f;
            }
            case 4 -> {
                m.rightArm.xRot = -pi / 2;
                m.rightArm.yRot = -0.1f;
                m.leftArm.zRot = -0.08f;
            }
            case 5 -> {
                m.rightLeg.xRot = -1.4f;
                m.leftLeg.xRot = -1.4f;
                m.rightLeg.yRot = 0.2f;
                m.leftLeg.yRot = -0.2f;
                m.rightArm.xRot = -0.6f;
                m.leftArm.xRot = -0.6f;
            }
            case 6 -> {
                m.rightArm.xRot = -2.1f;
                m.rightArm.yRot = 0.55f;
                m.leftArm.xRot = -0.9f;
                m.leftArm.yRot = 0.8f;
                m.head.xRot = 0.2f;
                m.head.zRot = 0.1f;
            }
            case 7 -> {
                m.rightArm.zRot = 0.55f;
                m.leftArm.zRot = -0.55f;
                m.rightLeg.zRot = 0.12f;
                m.leftLeg.zRot = -0.12f;
                m.head.xRot = -0.15f;
            }
            default -> {
                m.rightArm.zRot = 0.06f;
                m.leftArm.zRot = -0.06f;
            }
        }
    }

    private ProfileLooks() {
    }
}
