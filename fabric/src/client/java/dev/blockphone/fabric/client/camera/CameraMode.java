package dev.blockphone.fabric.client.camera;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.fabric.client.apps.gallery.GalleryApp;
import dev.blockphone.fabric.client.camera.CameraSettings.Param;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.mixin.GameRendererAccessor;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneScreen;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoMeta;
import dev.blockphone.fabric.client.photo.PhotoStore;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

/**
 * Режим камеры: интерфейс игры скрыт, вместо него — видоискатель. Игрок может ходить и смотреть по сторонам.
 *
 * <p>Управление: ЛКМ — снимок, колесо — зум, ←/→ — выбрать параметр, ↑/↓ — изменить, ПКМ — следующий фильтр,
 * H — скрыть панель, R — сброс, G — галерея, Esc — выход.
 * Хуки в игру — миксины в {@code client.mixin}: скрытие HUD и руки, FOV, ввод, постэффект.
 */
public final class CameraMode {
    public static final CameraSettings SETTINGS = new CameraSettings();
    private static final Identifier NOISE = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/camera/noise.png");

    private enum Exit { GAME, PHONE, GALLERY }

    private static boolean active;
    private static boolean fromPhone;
    private static float renderZoom = 1f;
    private static boolean panelHidden;
    private static long countdownEnd = -1;
    private static PhotoCapture capture;
    private static long flashStart = -1;
    private static String toast;
    private static long toastUntil;
    private static PhotoStore.Photo lastPhoto;
    private static final Random RANDOM = new Random();

    public static void init() {
        ClientTickEvents.START_CLIENT_TICK.register(CameraMode::tick);
    }

    public static boolean active() {
        return active;
    }

    /** Войти в режим камеры (нужен загруженный мир). */
    public static void enter(boolean openedFromPhone) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        active = true;
        fromPhone = openedFromPhone;
        renderZoom = SETTINGS.zoom;
        countdownEnd = -1;
        if (mc.screen != null) {
            mc.setScreen(null);
        }
        applyFilter();
    }

    private static void exit(Exit target) {
        if (!active) {
            return;
        }
        active = false;
        countdownEnd = -1;
        Minecraft mc = Minecraft.getInstance();
        mc.gameRenderer.clearPostEffect();
        switch (target) {
            case PHONE -> {
                PhoneState.setCurrent(PhoneState.HOME);
                mc.setScreen(new PhoneScreen());
            }
            case GALLERY -> {
                GalleryApp.openOnShow(lastPhoto);
                PhoneState.setCurrent("gallery");
                mc.setScreen(new PhoneScreen());
            }
            case GAME -> {
            }
        }
    }

    public static void toggle() {
        if (active) {
            exit(Exit.GAME);
        } else {
            enter(false);
        }
    }

    public static void setFilter(dev.blockphone.fabric.client.photo.CameraFilter filter) {
        SETTINGS.filter = filter;
        if (active) {
            applyFilter();
        }
    }

    /** Выйти из камеры и открыть последнее фото в галерее (как по G). */
    public static void openGallery() {
        exit(Exit.GALLERY);
    }

    private static void applyFilter() {
        Minecraft mc = Minecraft.getInstance();
        Identifier effect = SETTINGS.filter.postEffect();
        if (effect == null) {
            mc.gameRenderer.clearPostEffect();
        } else {
            ((GameRendererAccessor) mc.gameRenderer).blockphone$setPostEffect(effect);
        }
    }

    // --- Такт и ввод ---

    private static void tick(Minecraft mc) {
        if (!active) {
            return;
        }
        if (mc.player == null || mc.level == null || mc.screen != null) {
            // Открылся другой экран (смерть, чат и т.п.) или вышли из мира.
            exit(Exit.GAME);
            return;
        }
        while (mc.options.keyAttack.consumeClick()) {
            shoot();
        }
        while (mc.options.keyUse.consumeClick()) {
            SETTINGS.change(Param.FILTER, 1);
            applyFilter();
        }
        if (countdownEnd > 0 && System.currentTimeMillis() >= countdownEnd) {
            countdownEnd = -1;
            startCapture(mc);
        }
    }

    /** Снимок (как по ЛКМ). */
    public static void shoot() {
        if ((capture != null && capture.busy()) || countdownEnd > 0) {
            return;
        }
        int timer = CameraSettings.TIMER[SETTINGS.timer];
        if (timer > 0) {
            countdownEnd = System.currentTimeMillis() + timer * 1000L;
        } else {
            startCapture(Minecraft.getInstance());
        }
    }

    private static void startCapture(Minecraft mc) {
        capture = new PhotoCapture(SETTINGS.exposureMillis(), SETTINGS.exposureStops(), SETTINGS.noiseAmount(),
                buildMeta(mc), CameraMode::onSaved);
        flashStart = System.currentTimeMillis();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.8f, 0.6f));
    }

    private static void onSaved(PhotoStore.Photo photo) {
        if (photo != null) {
            lastPhoto = photo;
            toast = PhoneLang.tr("camera.saved", photo.name());
        } else {
            toast = PhoneLang.tr("camera.save_failed");
        }
        toastUntil = System.currentTimeMillis() + 2500;
    }

    private static PhotoMeta buildMeta(Minecraft mc) {
        PhotoMeta meta = new PhotoMeta();
        meta.takenAt = System.currentTimeMillis();
        ServerData server = mc.getCurrentServer();
        if (server != null && !mc.isLocalServer()) {
            meta.server = server.ip;
        }
        IntegratedServer local = mc.getSingleplayerServer();
        if (local != null) {
            meta.world = local.getWorldData().getLevelName();
        }
        meta.serverId = dev.blockphone.fabric.client.network.ClientSession.serverId();
        meta.dimension = mc.level.dimension().identifier().toString();
        meta.x = mc.player.getX();
        meta.y = mc.player.getY();
        meta.z = mc.player.getZ();
        meta.yaw = mc.player.getYRot();
        meta.pitch = mc.player.getXRot();
        meta.filter = SETTINGS.filter.id;
        meta.iso = CameraSettings.ISO[SETTINGS.iso];
        meta.effectiveIso = SETTINGS.effectiveIso();
        meta.shutter = CameraSettings.shutterLabel(SETTINGS.effectiveShutter());
        meta.ev = CameraSettings.EV[SETTINGS.ev];
        meta.zoom = SETTINGS.zoom;
        return meta;
    }

    /** Клавиши видоискателя. true — клавиша обработана и не должна дойти до игры. */
    public static boolean onKey(int action, KeyEvent event) {
        if (!active) {
            return false;
        }
        int key = event.key();
        boolean ours = switch (key) {
            case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN,
                 GLFW.GLFW_KEY_H, GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_R -> true;
            default -> false;
        };
        if (!ours || action == GLFW.GLFW_RELEASE) {
            return ours;
        }
        Param[] params = Param.values();
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> exit(fromPhone ? Exit.PHONE : Exit.GAME);
            case GLFW.GLFW_KEY_LEFT -> SETTINGS.selected = params[Math.floorMod(SETTINGS.selected.ordinal() - 1, params.length)];
            case GLFW.GLFW_KEY_RIGHT -> SETTINGS.selected = params[Math.floorMod(SETTINGS.selected.ordinal() + 1, params.length)];
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                SETTINGS.change(SETTINGS.selected, key == GLFW.GLFW_KEY_UP ? 1 : -1);
                if (SETTINGS.selected == Param.FILTER) {
                    applyFilter();
                }
            }
            case GLFW.GLFW_KEY_H -> panelHidden = !panelHidden;
            case GLFW.GLFW_KEY_R -> {
                SETTINGS.reset();
                applyFilter();
            }
            case GLFW.GLFW_KEY_G -> exit(Exit.GALLERY);
            default -> {
            }
        }
        return true;
    }

    public static void onScroll(double amount) {
        SETTINGS.zoom = (float) Math.max(CameraSettings.MIN_ZOOM,
                Math.min(CameraSettings.MAX_ZOOM, SETTINGS.zoom * Math.pow(1.15, amount)));
    }

    /** Во сколько раз уменьшить FOV (зум). */
    public static float fovDivisor() {
        return active ? renderZoom : 1f;
    }

    // --- Видоискатель ---

    /** Вызывается вместо обычного HUD (между отрисовкой мира и интерфейса). */
    public static void renderOverlay(GuiGraphics g, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        renderZoom += (SETTINGS.zoom - renderZoom) * 0.25f;
        if (capture != null) {
            capture.onFrame(mc);
            if (!capture.busy()) {
                capture = null;
            }
        }
        if (mc.options.hideGui) {
            return;
        }
        int w = g.guiWidth();
        int h = g.guiHeight();
        long now = System.currentTimeMillis();

        renderExposurePreview(g, w, h);
        if (SETTINGS.grid) {
            for (int i = 1; i <= 2; i++) {
                g.fill(w * i / 3, 0, w * i / 3 + 1, h, 0x50FFFFFF);
                g.fill(0, h * i / 3, w, h * i / 3 + 1, 0x50FFFFFF);
            }
        }
        renderCorners(g, 12, 12, w - 12, h - 12, 14, 0xDDFFFFFF);
        renderCorners(g, w / 2 - 14, h / 2 - 10, w / 2 + 14, h / 2 + 10, 5, 0xCCFFFFFF);

        if (renderZoom > 1.02f) {
            String zoom = String.format(java.util.Locale.ROOT, "%.1fx", renderZoom);
            PhoneDraw.centeredText(g, zoom, w / 2f, 20, 0xFFFFD60A, 1f, true);
        }
        if (capture != null && capture.exposing()) {
            g.fill(20, 20, 26, 26, PhoneTheme.danger());
            PhoneDraw.text(g, PhoneLang.tr("camera.exposing"), 30, 19, 0xFFFFFFFF, 1f, true);
        }
        if (!panelHidden) {
            renderPanel(g, w, h);
        }
        if (countdownEnd > 0) {
            String seconds = String.valueOf((countdownEnd - now + 999) / 1000);
            PhoneDraw.centeredText(g, seconds, w / 2f, h / 2f - 16, 0xFFFFFFFF, 4f, true);
        }
        if (flashStart > 0) {
            float t = (now - flashStart) / 220f;
            if (t < 1f) {
                g.fill(0, 0, w, h, (int) ((1f - t) * 200) << 24 | 0xFFFFFF);
            } else {
                flashStart = -1;
            }
        }
        if (toast != null && now < toastUntil) {
            float tw = PhoneDraw.width(toast, 1f);
            int x1 = (int) (w / 2f - tw / 2 - 6);
            PhoneDraw.roundedRect(g, x1, 34, (int) (w / 2f + tw / 2 + 6), 48, 5, 0xB0000000);
            PhoneDraw.centeredText(g, toast, w / 2f, 37, 0xFFFFFFFF, 1f, false);
        }
    }

    /** Приблизительное превью яркости и шума (точная обработка делается на сохранённом снимке). */
    private static void renderExposurePreview(GuiGraphics g, int w, int h) {
        float stops = SETTINGS.exposureStops();
        double factor = Math.pow(2, stops / 2.2);
        if (factor > 1.01) {
            int alpha = (int) (Math.min(0.85, 1 - 1 / factor) * 255);
            g.fill(0, 0, w, h, alpha << 24 | 0xFFFFFF);
        } else if (factor < 0.99) {
            int alpha = (int) (Math.min(0.92, 1 - factor) * 255);
            g.fill(0, 0, w, h, alpha << 24);
        }
        float noise = SETTINGS.noiseAmount();
        if (noise > 0.01f) {
            int color = (int) (Math.min(1f, noise * 0.7f) * 255) << 24 | 0xFFFFFF;
            int ox = RANDOM.nextInt(128);
            int oy = RANDOM.nextInt(128);
            for (int y = -oy; y < h; y += 128) {
                for (int x = -ox; x < w; x += 128) {
                    g.blit(RenderPipelines.GUI_TEXTURED, NOISE, x, y, 0, 0, 128, 128, 128, 128, 128, 128, color);
                }
            }
        }
    }

    private static void renderCorners(GuiGraphics g, int x1, int y1, int x2, int y2, int len, int color) {
        g.fill(x1, y1, x1 + len, y1 + 1, color);
        g.fill(x1, y1, x1 + 1, y1 + len, color);
        g.fill(x2 - len, y1, x2, y1 + 1, color);
        g.fill(x2 - 1, y1, x2, y1 + len, color);
        g.fill(x1, y2 - 1, x1 + len, y2, color);
        g.fill(x1, y2 - len, x1 + 1, y2, color);
        g.fill(x2 - len, y2 - 1, x2, y2, color);
        g.fill(x2 - 1, y2 - len, x2, y2, color);
    }

    private static void renderPanel(GuiGraphics g, int w, int h) {
        int panelH = 30;
        int top = h - panelH;
        g.fill(0, top, w, h, 0x99000000);
        Param[] params = Param.values();
        float cell = w / (float) params.length;
        for (int i = 0; i < params.length; i++) {
            Param param = params[i];
            float cx = cell * i + cell / 2f;
            boolean selected = param == SETTINGS.selected;
            PhoneDraw.centeredText(g, CameraSettings.paramLabel(param), cx, top + 5, selected ? 0xFFFFD60A : 0xFFAAAAAA, 0.75f, false);
            String value = PhoneDraw.ellipsize(SETTINGS.valueLabel(param), cell - 6, 1f);
            PhoneDraw.centeredText(g, value, cx, top + 15, selected ? 0xFFFFD60A : 0xFFFFFFFF, 1f, false);
            if (selected) {
                g.fill((int) (cx - 10), h - 3, (int) (cx + 10), h - 2, 0xFFFFD60A);
            }
        }
        PhoneDraw.centeredText(g, PhoneLang.tr("camera.hints"), w / 2f, top - 10, 0xCCFFFFFF, 0.75f, true);
    }

    private CameraMode() {
    }
}
