package dev.blockphone.fabric.client.screens;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.apps.AppDefinition;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.BlockPhoneClient;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneHolders;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.phone.PhoneState;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * «Режим экрана» своих приложений: приложение с разрешением display выводит картинку на экран в мире
 * (Lua: display.start / display.join, обратный вызов onDisplay). Владелец экрана — ведущий; другие игроки подключаются
 * через то же приложение (display.join) и рисуют экран у себя сами — игру они синхронизируют через net.
 * У игроков без подключения на экране — заставка: название приложения и подсказка.
 * Режим джойстика ({@link GamepadScreen}): телефон набок в руках, клавиши и мышь — приложению (input.down).
 */
public final class AppDisplays {
    /** Не чаще 30 кадров в секунду (кадр считает скрипт в потоке рендера). */
    private static final long FRAME_MS = 33;

    /** Что показывает приложение (реализует скрипт приложения). */
    public interface Source {
        String appId();

        boolean alive();

        int width();

        int height();

        /** Нарисовать кадр (onDisplay) — пиксели ARGB width×height или null. */
        @Nullable int[] frame(long now);

        /** Подписи последнего кадра (шрифт игры поверх картинки). */
        List<Text> texts();
    }

    /** Подпись поверх кадра: x, y, size — в пикселях кадра. align: 0 — слева, 1 — по центру, 2 — справа. */
    public record Text(String text, float x, float y, float size, int color, int align, boolean shadow) {
    }

    private static final class Binding {
        final String key;
        final Source source;
        final boolean host;
        @Nullable DynamicTexture texture;
        @Nullable Identifier textureId;
        int texW;
        int texH;
        long lastFrame;

        Binding(String key, Source source, boolean host) {
            this.key = key;
            this.source = source;
            this.host = host;
        }

        void close() {
            if (textureId != null) {
                Minecraft.getInstance().getTextureManager().release(textureId);
                textureId = null;
                texture = null;
            }
        }
    }

    private static final Map<String, Binding> BY_KEY = new HashMap<>();
    /** Для DevShots: «зажатые» клавиши в режиме джойстика. */
    public static final java.util.Set<String> debugKeys = new java.util.HashSet<>();
    private static @Nullable String controlling;
    private static String controlHint = "";
    /** Приглашения: G у экрана с приложением, которое у игрока не подключено (appId → ключ экрана). */
    private static final Map<String, String> INVITES = new HashMap<>();

    public static String key(String owner, String screenId) {
        return owner.toLowerCase(Locale.ROOT) + "/" + screenId;
    }

    // --- Привязка ---

    /** Показывать кадры source на экране key (прежняя привязка этого приложения снимается). */
    public static void bind(Source source, String key, boolean host) {
        unbind(source);
        Binding old = BY_KEY.remove(key);
        if (old != null) {
            old.close();
        }
        BY_KEY.put(key, new Binding(key, source, host));
    }

    public static void unbind(Source source) {
        BY_KEY.values().removeIf(b -> {
            if (b.source != source) {
                return false;
            }
            b.close();
            if (b.key.equals(controlling)) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof GamepadScreen) {
                    mc.setScreen(null);
                }
                leaveControl();
            }
            return true;
        });
    }

    /** Экран, к которому привязано приложение (null — ни к какому). */
    public static @Nullable String boundKey(Source source) {
        for (Binding b : BY_KEY.values()) {
            if (b.source == source) {
                return b.key;
            }
        }
        return null;
    }

    public static boolean isHost(Source source) {
        for (Binding b : BY_KEY.values()) {
            if (b.source == source) {
                return b.host;
            }
        }
        return false;
    }

    /** Экран по ключу (свой или чужой, в этом измерении), null — нет. */
    public static Screens.@Nullable Live live(String key) {
        for (Screens.Live l : Screens.all()) {
            if (l.key().equals(key)) {
                return l;
            }
        }
        return null;
    }

    /** Каждый такт: привязки к пропавшим экранам или экранам, где уже другое приложение, снимаются. */
    static void tick() {
        BY_KEY.values().removeIf(b -> {
            Screens.Live l = live(b.key);
            boolean ok = b.source.alive() && l != null && b.source.appId().equals(l.def().app);
            if (!ok) {
                b.close();
                if (b.key.equals(controlling)) {
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.screen instanceof GamepadScreen) {
                        mc.setScreen(null);
                    }
                    leaveControl();
                }
            }
            return !ok;
        });
    }

    /** Позвать приложение к экрану key (его скрипт получит onDisplayInvite) и открыть его в телефоне. */
    public static void invite(String appId, String key) {
        INVITES.put(appId, key);
    }

    /** Приглашение для приложения (G у экрана) — забрать один раз. */
    public static @Nullable String takeInvite(String appId) {
        return INVITES.remove(appId);
    }

    // --- Рисование ---

    /** Экран с приложением: кадр приложения (если подключено) или заставка. */
    static void render(PoseStack pose, SubmitNodeCollector queue, Vec3 cam, Screens.Live live) {
        ScreenData.Screen s = live.def();
        Vec3[] c = s.corners(0.004);
        Screens.solid(pose, queue, cam, c, Screens.WHITE, 0xFF000000);
        Binding b = BY_KEY.get(live.key());
        if (b != null && b.source.alive()) {
            Identifier tex = texture(b);
            if (tex != null) {
                float aspect = b.texW / (float) Math.max(1, b.texH);
                Screens.solid(pose, queue.order(1), cam, Screens.fitted(s, aspect, 0.006), tex, 0xFFFFFFFF);
                texts(pose, queue.order(2), cam, Screens.fitted(s, aspect, 0.009), b.texW, b.texH, b.source.texts());
                return;
            }
        }
        // Заставка: название приложения и как подключиться.
        int w = 384, h = Math.max(120, Math.round(384 * s.heightBlocks() / (float) Math.max(1, s.widthBlocks())));
        String title = s.appTitle.isEmpty() ? s.app : s.appTitle;
        AppDefinition app = dev.blockphone.fabric.client.apps.custom.CustomApps.get(s.app);
        String hint = app != null ? PhoneLang.tr("screens.app_card_open", keyName()) : PhoneLang.tr("screens.app_card_install");
        List<Text> card = List.of(
                new Text(title, w / 2f, h / 2f - 26, 22, 0xFFFFFFFF, 1, true),
                new Text(PhoneLang.tr("screens.app_card_owner", live.owner()), w / 2f, h / 2f + 4, 10, 0xFFB0BEC5, 1, false),
                new Text(hint, w / 2f, h / 2f + 22, 10, 0xFFFFEB3B, 1, false));
        texts(pose, queue.order(2), cam, Screens.fitted(s, w / (float) h, 0.009), w, h, card);
    }

    private static @Nullable Identifier texture(Binding b) {
        long now = System.currentTimeMillis();
        if (b.textureId != null && now - b.lastFrame < FRAME_MS) {
            return b.textureId;
        }
        b.lastFrame = now;
        int[] px;
        try {
            px = b.source.frame(now);
        } catch (RuntimeException e) {
            BlockPhoneFabric.LOGGER.warn("[apps] display frame failed", e);
            px = null;
        }
        int w = b.source.width(), h = b.source.height();
        if (px == null || px.length < w * h) {
            return b.textureId;
        }
        Minecraft mc = Minecraft.getInstance();
        if (b.texture == null || b.texW != w || b.texH != h) {
            b.close();
            b.texture = new DynamicTexture(() -> "BlockPhone app display", w, h, false);
            b.textureId = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "dynamic/display/" + Integer.toHexString(b.key.hashCode())
                    + "_" + System.nanoTime());
            b.texW = w;
            b.texH = h;
            mc.getTextureManager().register(b.textureId, b.texture);
        }
        NativeImage pixels = b.texture.getPixels();
        if (pixels == null) {
            return null;
        }
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                pixels.setPixel(x, y, px[row + x]);
            }
        }
        b.texture.upload();
        return b.textureId;
    }

    /** Подписи шрифтом игры на плоскости экрана (rect — углы картинки, w×h — пиксели кадра). */
    private static void texts(PoseStack pose, net.minecraft.client.renderer.OrderedSubmitNodeCollector queue, Vec3 cam, Vec3[] rect, int w, int h, List<Text> texts) {
        if (texts.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        Vec3 o = rect[0];
        Vec3 right = rect[1].subtract(rect[0]).scale(1.0 / w);
        Vec3 down = rect[3].subtract(rect[0]).scale(1.0 / h);
        Vec3 normal = right.cross(down).normalize().scale(right.length());
        pose.pushPose();
        pose.translate(o.x - cam.x, o.y - cam.y, o.z - cam.z);
        pose.last().pose().mul(new Matrix4f(
                (float) right.x, (float) right.y, (float) right.z, 0,
                (float) down.x, (float) down.y, (float) down.z, 0,
                (float) normal.x, (float) normal.y, (float) normal.z, 0,
                0, 0, 0, 1));
        for (Text t : texts) {
            float k = t.size() / font.lineHeight;
            float tw = font.width(t.text());
            float x = t.align() == 1 ? -tw / 2 : t.align() == 2 ? -tw : 0;
            pose.pushPose();
            pose.translate(t.x(), t.y(), 0);
            pose.scale(k, k, k);
            queue.submitText(pose, x, 0, FormattedCharSequence.forward(t.text(), Style.EMPTY), t.shadow(), Font.DisplayMode.POLYGON_OFFSET,
                    Screens.FULL_BRIGHT, t.color(), 0, 0);
            pose.popPose();
        }
        pose.popPose();
    }

    // --- Джойстик ---

    public static String keyName() {
        com.mojang.blaze3d.platform.InputConstants.Key key = KeyBindingHelper.getBoundKeyOf(BlockPhoneClient.gameKey);
        String id = key.getName();
        if (id.startsWith("key.keyboard.")) {
            return id.substring("key.keyboard.".length()).replace('.', ' ').toUpperCase(Locale.ROOT);
        }
        return key.getDisplayName().getString().toUpperCase(Locale.ROOT);
    }

    /** Приложение source включает/выключает режим джойстика для своего экрана (hint — подсказка внизу). */
    public static boolean control(Source source, boolean on, @Nullable String hint) {
        Minecraft mc = Minecraft.getInstance();
        String key = boundKey(source);
        if (!on) {
            if (key != null && key.equals(controlling) && mc.screen instanceof GamepadScreen) {
                mc.setScreen(null);
            }
            return true;
        }
        if (key == null || mc.player == null) {
            return false;
        }
        enterControl(mc, key, hint);
        return true;
    }

    /** Этот игрок сейчас управляет экраном приложения source (клавиши — ему). */
    public static boolean controls(Source source) {
        String key = boundKey(source);
        return key != null && key.equals(controlling) && Minecraft.getInstance().screen instanceof GamepadScreen;
    }

    private static void enterControl(Minecraft mc, String key, @Nullable String hint) {
        controlling = key;
        controlHint = hint == null ? "" : hint;
        PhoneHolders.gamepad = true;
        mc.setScreen(new GamepadScreen());
    }

    static String controlHint() {
        return controlHint.isEmpty() ? PhoneLang.tr("screens.gamepad_hint", keyName()) : controlHint + " · " + keyName() + " — "
                + PhoneLang.tr("screens.gamepad_exit");
    }

    static boolean controllingAny() {
        return controlling != null;
    }

    static void leaveControl() {
        controlling = null;
        PhoneHolders.gamepad = false;
    }

    /**
     * Клавиша джойстика: у экрана с приложением (до 32 блоков) — управлять им, если приложение подключено; если нет —
     * открыть приложение в телефоне с приглашением (onDisplayInvite); не установлено — подсказка.
     */
    public static void toggleControl(Minecraft mc) {
        if (mc.screen instanceof GamepadScreen) {
            mc.setScreen(null);
            return;
        }
        if (mc.screen != null || mc.player == null) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1);
        Screens.Live best = null;
        double bestScore = Double.MAX_VALUE;
        for (Screens.Live live : Screens.all()) {
            ScreenData.Screen s = live.def();
            if (!live.on() || s.app.isEmpty()) {
                continue;
            }
            Vec3 to = s.center().subtract(eye);
            double d = to.length();
            if (d > 32) {
                continue;
            }
            double score = d * (2 - to.normalize().dot(look));
            if (score < bestScore) {
                bestScore = score;
                best = live;
            }
        }
        if (best == null) {
            toast(mc, PhoneLang.tr("screens.game_none"));
            return;
        }
        Binding b = BY_KEY.get(best.key());
        if (b != null && b.source.alive()) {
            enterControl(mc, best.key(), controlHint);
            return;
        }
        AppDefinition app = dev.blockphone.fabric.client.apps.custom.CustomApps.get(best.def().app);
        if (app == null) {
            toast(mc, PhoneLang.tr("screens.app_card_install"));
            return;
        }
        INVITES.put(app.id(), best.key());
        PhoneState.setCurrent(dev.blockphone.fabric.client.apps.custom.CustomApps.PREFIX + app.id());
        mc.setScreen(new PhoneScreen());
    }

    private static void toast(Minecraft mc, String text) {
        dev.blockphone.fabric.client.compat.ClientCompat.systemToast(mc, Component.literal(PhoneLang.tr("app.screens")), Component.literal(text));
    }

    /** Выход из мира. */
    static void closeAll() {
        for (Binding b : BY_KEY.values()) {
            b.close();
        }
        BY_KEY.clear();
        INVITES.clear();
        leaveControl();
    }

    /** Для DevShots. */
    public static String debugStatus() {
        List<String> out = new ArrayList<>();
        for (Binding b : BY_KEY.values()) {
            out.add(b.key + (b.host ? " host " : " guest ") + b.source.appId() + " " + b.texW + "x" + b.texH);
        }
        return out + " controlling=" + controlling;
    }

    private AppDisplays() {
    }
}
