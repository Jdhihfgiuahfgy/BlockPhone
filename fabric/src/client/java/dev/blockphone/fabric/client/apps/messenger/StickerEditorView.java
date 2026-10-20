package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.data.StickerData.Limits;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.messenger.StickerClient;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.ImageOps;
import dev.blockphone.fabric.client.photo.ImageUploads;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.Picture;
import dev.blockphone.fabric.client.photo.StickerMaker;
import dev.blockphone.fabric.client.storage.ClientFiles;
import dev.blockphone.fabric.client.ui.PhotoPicker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Новый стикер из фото галереи: форма (квадрат / скруглённый / круг), белая обводка, приближение (колесо или «−/+»),
 * сдвиг (перетаскивание по картинке). «Добавить» — загрузка PNG на сервер и добавление в набор.
 */
final class StickerEditorView extends MessengerView {
    private static final int PREVIEW = 120;
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "sticker_preview");

    private final long packId;
    private final PhotoPicker picker = new PhotoPicker(this::pick);
    private final List<Hit> hits = new ArrayList<>();
    private PhotoStore.Photo photo;
    private Picture source;
    private boolean loading;
    private StickerMaker.Shape shape = StickerMaker.Shape.ROUNDED;
    private boolean outline = true;
    private float zoom = 1f;
    private float offsetX;
    private float offsetY;
    private boolean dirty = true;
    private boolean textureReady;
    private ImageUploads.Upload upload;
    private String error;
    private double dragX;
    private double dragY;
    private boolean dragging;

    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    StickerEditorView(MessengerApp app, long packId) {
        super(app);
        this.packId = packId;
    }

    @Override
    String title() {
        return MessengerApp.tr("sticker.new");
    }

    @Override
    String headerAction() {
        return source != null && (upload == null || !upload.busy()) ? MessengerApp.tr("sticker.add") : null;
    }

    @Override
    void onShown() {
        if (photo == null) {
            picker.open();
        }
    }

    @Override
    void onHidden() {
        if (textureReady) {
            Minecraft.getInstance().getTextureManager().release(TEXTURE);
            textureReady = false;
        }
    }

    /** Для автоскриншотов: выбрать фото и оформление сразу. */
    void debugPrepare(PhotoStore.Photo value, StickerMaker.Shape s, boolean o, float z) {
        picker.close();
        shape = s;
        outline = o;
        zoom = z;
        pick(value);
    }

    private void pick(PhotoStore.Photo value) {
        photo = value;
        loading = true;
        error = null;
        ClientFiles.IO.execute(() -> {
            try {
                // Для редактора хватает 512 пикселей — стикер всё равно меньше.
                Picture read = ImageOps.downscale(ImageOps.read(value.file()), 512, 512);
                Minecraft.getInstance().execute(() -> {
                    source = read;
                    loading = false;
                    dirty = true;
                });
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Sticker source not read", e);
                Minecraft.getInstance().execute(() -> {
                    loading = false;
                    error = "photo.error.read";
                });
            }
        });
    }

    @Override
    void onHeaderAction() {
        Limits limits = StickerClient.limits();
        if (source == null || limits == null) {
            return;
        }
        Picture sticker = StickerMaker.make(source, limits.maxSide(), shape, outline, zoom, offsetX, offsetY);
        error = null;
        upload = ImageUploads.startSticker(photo, sticker, limits.maxBytes(), done -> {
            if (done.state() == ImageUploads.State.DONE && done.image() != null) {
                StickerClient.addSticker(packId, done.image().id());
                app.pop();
            } else {
                error = done.error();
            }
        });
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        hits.clear();
        int w = width();
        if (picker.isOpen()) {
            picker.render(g, mouseX, mouseY, w, height());
            return;
        }
        if (source == null) {
            String text = loading ? MessengerApp.tr("messenger.loading") : MessengerApp.tr("sticker.pick_photo");
            PhoneDraw.centeredText(g, text, w / 2f, height() / 2f - 20, PhoneTheme.secondaryText(), 0.75f, false);
            button(g, w / 2 - 50, height() / 2 - 6, 100, 18, MessengerApp.tr("sticker.choose"), mouseX, mouseY,
                    PhoneDraw.ButtonStyle.PRIMARY, picker::open);
            return;
        }
        if (dirty) {
            updateTexture();
        }
        int px = (w - PREVIEW) / 2;
        int py = 8;
        // Шахматка — видно прозрачность.
        for (int y = 0; y < PREVIEW; y += 8) {
            for (int x = 0; x < PREVIEW; x += 8) {
                boolean dark = ((x + y) / 8) % 2 == 0;
                g.fill(px + x, py + y, px + x + 8, py + y + 8, dark ? 0xFF9AA0AA : 0xFFC8CCD4);
            }
        }
        if (textureReady) {
            g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, px, py, 0, 0, PREVIEW, PREVIEW, 1, 1, 1, 1);
        }
        int y = py + PREVIEW + 4;
        PhoneDraw.centeredText(g, MessengerApp.tr("sticker.drag_hint"), w / 2f, y, PhoneTheme.secondaryText(), 0.55f, false);
        y += 12;

        int third = (w - 24) / 3;
        StickerMaker.Shape[] shapes = StickerMaker.Shape.values();
        for (int i = 0; i < shapes.length; i++) {
            StickerMaker.Shape s = shapes[i];
            button(g, 8 + i * (third + 4), y, third, 16, MessengerApp.tr("sticker.shape." + s.name().toLowerCase()), mouseX, mouseY,
                    shape == s ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL, () -> {
                        shape = s;
                        dirty = true;
                    });
        }
        y += 20;
        int half = (w - 20) / 2;
        button(g, 8, y, half, 16, MessengerApp.tr(outline ? "sticker.outline_on" : "sticker.outline_off"), mouseX, mouseY,
                outline ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL, () -> {
                    outline = !outline;
                    dirty = true;
                });
        button(g, 12 + half, y, half / 2 - 2, 16, "−", mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, () -> setZoom(zoom - 0.25f));
        button(g, 12 + half + half / 2 + 2, y, half / 2 - 2, 16, "+", mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, () -> setZoom(zoom + 0.25f));
        y += 20;
        button(g, 8, y, w - 16, 16, MessengerApp.tr("sticker.other_photo"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, picker::open);
        y += 22;
        if (upload != null && upload.busy()) {
            PhoneDraw.centeredText(g, MessengerApp.tr("photo.uploading"), w / 2f, y, PhoneTheme.secondaryText(), 0.65f, false);
        } else if (error != null) {
            PhoneDraw.wrapped(g, PhoneLang.tr(error), 8, y, w - 16, PhoneTheme.danger(), 0.65f, false);
        }
    }

    private void setZoom(float value) {
        zoom = Math.max(1f, Math.min(4f, value));
        dirty = true;
    }

    private void updateTexture() {
        dirty = false;
        Picture preview = StickerMaker.make(source, PREVIEW * 2, shape, outline, zoom, offsetX, offsetY);
        Minecraft mc = Minecraft.getInstance();
        if (textureReady) {
            mc.getTextureManager().release(TEXTURE);
        }
        mc.getTextureManager().register(TEXTURE, new DynamicTexture(() -> "BlockPhone sticker preview", ImageOps.toNativeImage(preview)));
        textureReady = true;
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, PhoneDraw.ButtonStyle style, Runnable action) {
        PhoneDraw.button(g, x, y, w, h, label, mx, my, style);
        hits.add(new Hit(x, y, w, h, action));
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (picker.isOpen()) {
            return picker.mouseClicked(x, y, button);
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (button == 0 && PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                hit.action().run();
                return true;
            }
        }
        int px = (width() - PREVIEW) / 2;
        if (source != null && PhoneDraw.inside(x, y, px, 8, PREVIEW, PREVIEW)) {
            dragging = true;
            dragX = x;
            dragY = y;
            return true;
        }
        return false;
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        if (!dragging || source == null) {
            return false;
        }
        // Тянем картинку: сдвиг центра в обратную сторону.
        float range = PREVIEW / 2f * zoom;
        offsetX = Math.max(-1, Math.min(1, offsetX - (float) (x - dragX) / range));
        offsetY = Math.max(-1, Math.min(1, offsetY - (float) (y - dragY) / range));
        dragX = x;
        dragY = y;
        dirty = true;
        return true;
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        dragging = false;
        return false;
    }

    @Override
    boolean mouseScrolled(double x, double y, double amount) {
        if (picker.isOpen()) {
            return picker.mouseScrolled(amount);
        }
        if (source != null) {
            setZoom(zoom + (float) amount * 0.25f);
        }
        return true;
    }

    @Override
    boolean onBack() {
        if (picker.isOpen() && source != null) {
            picker.close();
            return true;
        }
        return false;
    }
}
