package dev.blockphone.fabric.client.apps.gallery;

import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneClock;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.phone.Wallpapers;
import dev.blockphone.fabric.client.photo.CameraFilter;
import dev.blockphone.fabric.client.photo.ImageOps;
import dev.blockphone.fabric.client.photo.PhotoMeta;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.photo.Picture;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Галерея: сетка миниатюр → просмотр (сведения о снимке, листание ←/→, удаление) → редактор
 * (поворот, отражение, кадрирование, фильтр, яркость/контраст/насыщенность; сохранить копию или заменить).
 */
public final class GalleryApp extends PhoneApp {
    private enum Mode { GRID, VIEWER, EDITOR }

    private static final int COLUMNS = 3;
    private static final int GAP = 2;
    private static final int CELL = (160 - GAP * (COLUMNS - 1)) / COLUMNS;
    private static final int VIEW_H = 170;
    private static final int EDIT_PREVIEW_H = 118;
    private static final int ROW_H = 18;

    /** Кликабельная область, записанная при последней отрисовке. */
    private record Hit(int x, int y, int w, int h, Runnable action) {
    }

    private static @Nullable PhotoStore.Photo pendingOpen;

    private final List<Hit> hits = new ArrayList<>();
    private Mode mode = Mode.GRID;
    private List<PhotoStore.Photo> photos = List.of();
    private int loadedVersion = -1;
    private boolean loadingList;
    private double scroll;

    private int index;
    private @Nullable PhotoMeta meta;

    private final PhotoEdit edit = new PhotoEdit();
    private @Nullable Picture editSource;
    private PhotoTextures.@Nullable Image editPreview;
    private boolean editDirty;
    private boolean editLoading;
    private boolean saving;
    private long wallpaperSetAt;

    public GalleryApp() {
        super("gallery");
    }

    /** При следующем показе галереи сразу открыть это фото (например, из камеры по G). */
    public static void openOnShow(@Nullable PhotoStore.Photo photo) {
        pendingOpen = photo;
    }

    // --- Данные ---

    @Override
    public void onShown() {
        if (pendingOpen == null && mode == Mode.EDITOR) {
            return;
        }
        refreshList();
    }

    @Override
    public void tick() {
        if (PhotoStore.version() != loadedVersion) {
            refreshList();
        }
    }

    private void refreshList() {
        if (loadingList) {
            return;
        }
        loadingList = true;
        int version = PhotoStore.version();
        ClientFiles.IO.execute(() -> {
            List<PhotoStore.Photo> list = PhotoStore.list();
            Minecraft.getInstance().execute(() -> {
                loadingList = false;
                loadedVersion = version;
                photos = list;
                if (pendingOpen != null) {
                    int found = indexOf(pendingOpen);
                    pendingOpen = null;
                    if (found >= 0) {
                        openViewer(found);
                    }
                } else if (mode == Mode.VIEWER && photos.isEmpty()) {
                    mode = Mode.GRID;
                } else if (mode == Mode.VIEWER) {
                    openViewer(Math.min(index, photos.size() - 1));
                }
            });
        });
    }

    private int indexOf(PhotoStore.Photo photo) {
        for (int i = 0; i < photos.size(); i++) {
            if (photos.get(i).file().equals(photo.file())) {
                return i;
            }
        }
        return -1;
    }

    private @Nullable PhotoStore.Photo current() {
        return index >= 0 && index < photos.size() ? photos.get(index) : null;
    }

    private void openViewer(int i) {
        index = i;
        mode = Mode.VIEWER;
        PhotoStore.Photo photo = current();
        meta = photo == null ? null : PhotoStore.readMeta(photo);
    }

    // --- Заголовок и навигация ---

    @Override
    public String title() {
        return switch (mode) {
            case GRID -> super.title();
            case VIEWER -> current() == null ? super.title() : formatDate(takenAt(), "d MMM, HH:mm");
            case EDITOR -> PhoneLang.tr("gallery.editor");
        };
    }

    @Override
    public String headerAction() {
        return switch (mode) {
            case GRID -> PhoneLang.tr("gallery.folder");
            case VIEWER -> PhoneLang.tr("dialog.delete");
            case EDITOR -> PhoneLang.tr("gallery.reset");
        };
    }

    @Override
    public int headerActionColor() {
        return mode == Mode.VIEWER ? PhoneTheme.danger() : PhoneTheme.accent();
    }

    @Override
    public void onHeaderAction() {
        switch (mode) {
            case GRID -> openFolder();
            case VIEWER -> askDelete();
            case EDITOR -> {
                edit.reset();
                editDirty = true;
            }
        }
    }

    @Override
    public boolean onBack() {
        switch (mode) {
            case EDITOR -> {
                closeEditor();
                mode = Mode.VIEWER;
                return true;
            }
            case VIEWER -> {
                mode = Mode.GRID;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public void onHidden() {
        if (mode == Mode.EDITOR && !saving) {
            closeEditor();
            mode = Mode.VIEWER;
        }
    }

    @Override
    public int backgroundColor() {
        return mode == Mode.GRID ? PhoneTheme.background() : 0xFF0B0F18;
    }

    @Override
    public int headerColor() {
        return mode == Mode.GRID ? PhoneTheme.header() : 0xFF0B0F18;
    }

    @Override
    public int headerTextColor() {
        return mode == Mode.GRID ? PhoneTheme.text() : 0xFFFFFFFF;
    }

    @Override
    public boolean lightStatusBar() {
        return mode != Mode.GRID || PhoneTheme.dark();
    }

    private void openFolder() {
        try {
            Files.createDirectories(ClientFiles.gallery());
            Util.getPlatform().openPath(ClientFiles.gallery());
        } catch (Exception e) {
            BlockPhoneFabric.LOGGER.warn("Failed to open gallery folder", e);
        }
    }

    private void askDelete() {
        PhotoStore.Photo photo = current();
        if (photo == null) {
            return;
        }
        phone.showDialog(PhoneDialog.confirmDelete(PhoneLang.tr("gallery.delete_title"), PhoneLang.tr("gallery.delete_message"), () -> {
            PhotoTextures.invalidate(photo);
            ClientFiles.IO.execute(() -> PhotoStore.delete(photo));
        }));
    }

    // --- Отрисовка ---

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        hits.clear();
        switch (mode) {
            case GRID -> renderGrid(g, mouseX, mouseY);
            case VIEWER -> renderViewer(g, mouseX, mouseY);
            case EDITOR -> renderEditor(g, mouseX, mouseY);
        }
    }

    private void renderGrid(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        if (photos.isEmpty()) {
            String text = PhoneLang.tr(loadingList && loadedVersion < 0 ? "gallery.loading" : "gallery.empty");
            PhoneDraw.wrapped(g, text, 12, contentHeight() / 2f - 10, w - 24, PhoneTheme.secondaryText(), 0.75f, true);
            return;
        }
        clampScroll();
        int top = -(int) scroll;
        for (int i = 0; i < photos.size(); i++) {
            int x = (i % COLUMNS) * (CELL + GAP);
            int y = top + (i / COLUMNS) * (CELL + GAP);
            if (y + CELL < 0 || y > contentHeight()) {
                continue;
            }
            PhotoTextures.Image thumb = PhotoTextures.thumbnail(photos.get(i));
            if (thumb != null) {
                PhoneDraw.texture(g, thumb.id(), x, y, CELL, CELL, thumb.width(), thumb.height());
            } else {
                g.fill(x, y, x + CELL, y + CELL, PhoneTheme.cardHover());
            }
            if (PhoneDraw.inside(mouseX, mouseY, x, y, CELL, CELL)) {
                g.fill(x, y, x + CELL, y + CELL, 0x30FFFFFF);
            }
            int photoIndex = i;
            hits.add(new Hit(x, y, CELL, CELL, () -> openViewer(photoIndex)));
        }
    }

    private void clampScroll() {
        int rows = (photos.size() + COLUMNS - 1) / COLUMNS;
        double max = Math.max(0, rows * (CELL + GAP) - contentHeight());
        scroll = Math.max(0, Math.min(max, scroll));
    }

    private void renderViewer(GuiGraphics g, int mouseX, int mouseY) {
        PhotoStore.Photo photo = current();
        if (photo == null) {
            return;
        }
        int w = contentWidth();
        PhotoTextures.Image image = PhotoTextures.preview(photo, 320);
        // Высота области фото = высота вписанного снимка (но не больше VIEW_H), чтобы не было пустоты.
        int viewH = image != null ? Math.min(VIEW_H, Math.round(w * image.height() / (float) image.width())) : 90;
        if (image != null) {
            drawFitted(g, image, 0, 0, w, viewH);
        } else {
            PhoneDraw.centeredText(g, PhoneLang.tr("gallery.loading"), w / 2f, viewH / 2f - 3, 0xFF8E8E93, 0.75f, false);
        }

        // Листание.
        if (index > 0) {
            arrow(g, "<", 2, viewH / 2 - 10, mouseX, mouseY, () -> openViewer(index - 1));
        }
        if (index < photos.size() - 1) {
            arrow(g, ">", w - 18, viewH / 2 - 10, mouseX, mouseY, () -> openViewer(index + 1));
        }

        int y = viewH + 8;
        List<String> lines = new ArrayList<>();
        lines.add(formatDate(takenAt(), "d MMMM yyyy, HH:mm:ss"));
        if (meta != null) {
            if (meta.server != null) {
                lines.add(PhoneLang.tr("gallery.server", meta.server));
            } else if (meta.world != null) {
                lines.add(PhoneLang.tr("gallery.world", meta.world));
            }
            if (meta.dimension != null) {
                String dim = meta.dimension.substring(meta.dimension.indexOf(':') + 1);
                lines.add(String.format(Locale.ROOT, "%s  X %d  Y %d  Z %d", dim,
                        (int) Math.floor(meta.x), (int) Math.floor(meta.y), (int) Math.floor(meta.z)));
            }
            String filter = CameraFilter.byId(meta.filter).displayName();
            lines.add(String.format(Locale.ROOT, "ISO %d · %s · EV %+.1f · %s · %.1fx",
                    meta.effectiveIso, meta.shutter, meta.ev, filter, meta.zoom));
            if (meta.edited) {
                lines.add(PhoneLang.tr("gallery.edited"));
            }
        }
        lines.add(photo.name() + (meta != null && meta.width > 0 ? "  " + meta.width + "×" + meta.height : ""));
        for (String line : lines) {
            PhoneDraw.text(g, PhoneDraw.ellipsize(line, w - 16, 0.6f), 8, y, 0xFFCCCCCC, 0.6f, false);
            y += 9;
        }

        int by = contentHeight() - 24;
        int half = (w - 16 - 4) / 2;
        // Верхний ряд: копировать в буфер обмена и папка; нижний: изменить и обои.
        int ty = by - 22;
        PhoneDraw.button(g, 8, ty, half, 18, PhoneLang.tr("gallery.copy"), mouseX, mouseY, 0xFF1A2232, 0xFFFFFFFF);
        hits.add(new Hit(8, ty, half, 18, () -> {
            showNotice(PhoneLang.tr("gallery.copying"));
            dev.blockphone.fabric.client.storage.ClipboardImage.copy(photo.file(),
                    ok -> showNotice(PhoneLang.tr(ok ? "gallery.copied" : "gallery.copy_failed")));
        }));
        PhoneDraw.button(g, 12 + half, ty, half, 18, PhoneLang.tr("gallery.folder"), mouseX, mouseY, 0xFF1A2232, 0xFFFFFFFF);
        hits.add(new Hit(12 + half, ty, half, 18, this::openFolder));
        PhoneDraw.button(g, 8, by, half, 18, PhoneLang.tr("gallery.edit"), mouseX, mouseY, 0xFF1A2232, 0xFFFFFFFF);
        hits.add(new Hit(8, by, half, 18, this::openEditor));
        PhoneDraw.button(g, 12 + half, by, half, 18, PhoneLang.tr("gallery.set_wallpaper"), mouseX, mouseY, PhoneTheme.accent(), PhoneTheme.onAccent());
        hits.add(new Hit(12 + half, by, half, 18, () -> {
            Wallpapers.importAndSet(photo.file());
            showNotice(PhoneLang.tr("gallery.wallpaper_set"));
        }));
        if (System.currentTimeMillis() - wallpaperSetAt < 2500) {
            String done = notice;
            float tw = PhoneDraw.width(done, 0.75f) + 12;
            int tx = (int) (w / 2f - tw / 2);
            PhoneDraw.roundedRect(g, tx, ty - 18, (int) (tx + tw), ty - 4, 4, PhoneTheme.accent());
            PhoneDraw.centeredText(g, done, w / 2f, ty - 14, PhoneTheme.onAccent(), 0.75f, false);
        }
    }

    private String notice = "";

    private void showNotice(String text) {
        notice = text;
        wallpaperSetAt = System.currentTimeMillis();
    }

    private void arrow(GuiGraphics g, String label, int x, int y, int mx, int my, Runnable action) {
        boolean hovered = PhoneDraw.inside(mx, my, x, y, 16, 20);
        PhoneDraw.roundedRect(g, x, y, x + 16, y + 20, 6, hovered ? 0x99000000 : 0x55000000);
        PhoneDraw.centeredText(g, label, x + 8, y + 6, 0xFFFFFFFF, 1f, false);
        hits.add(new Hit(x, y, 16, 20, action));
    }

    private static void drawFitted(GuiGraphics g, PhotoTextures.Image image, int x, int y, int w, int h) {
        float k = Math.min(w / (float) image.width(), h / (float) image.height());
        int dw = Math.max(1, Math.round(image.width() * k));
        int dh = Math.max(1, Math.round(image.height() * k));
        PhoneDraw.texture(g, image.id(), x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, image.width(), image.height());
    }

    private long takenAt() {
        PhotoStore.Photo photo = current();
        if (meta != null && meta.takenAt > 0) {
            return meta.takenAt;
        }
        return photo == null ? 0 : photo.modified();
    }

    private static String formatDate(long millis, String pattern) {
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern(pattern, PhoneClock.locale()));
    }

    // --- Редактор ---

    public void openEditor() {
        PhotoStore.Photo photo = current();
        if (photo == null) {
            return;
        }
        mode = Mode.EDITOR;
        edit.reset();
        editLoading = true;
        editSource = null;
        ClientFiles.IO.execute(() -> {
            Picture source;
            try {
                source = ImageOps.downscale(ImageOps.read(photo.file()), 320, 320);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Failed to open photo for editing", e);
                source = null;
            }
            Picture result = source;
            Minecraft.getInstance().execute(() -> {
                editLoading = false;
                if (mode == Mode.EDITOR && result != null) {
                    editSource = result;
                    editDirty = true;
                } else if (result == null) {
                    mode = Mode.VIEWER;
                }
            });
        });
    }

    private void closeEditor() {
        if (editPreview != null) {
            PhotoTextures.release(editPreview);
            editPreview = null;
        }
        editSource = null;
    }

    private void renderEditor(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        if (editDirty && editSource != null) {
            if (editPreview != null) {
                PhotoTextures.release(editPreview);
            }
            editPreview = PhotoTextures.upload(edit.apply(editSource), "edit");
            editDirty = false;
        }
        if (editPreview != null) {
            drawFitted(g, editPreview, 0, 0, w, EDIT_PREVIEW_H);
        }
        if (editLoading || saving) {
            String text = PhoneLang.tr(saving ? "gallery.saving" : "gallery.loading");
            PhoneDraw.centeredText(g, text, w / 2f, EDIT_PREVIEW_H / 2f - 3, 0xFFFFFFFF, 0.75f, true);
        }
        if (editSource == null || saving) {
            return;
        }

        int y = EDIT_PREVIEW_H + 6;
        int bw = (w - 16 - 8) / 3;
        int gray = 0xFF1A2232;
        int white = 0xFFFFFFFF;
        PhoneDraw.button(g, 8, y, bw, ROW_H, PhoneLang.tr("gallery.rotate"), mouseX, mouseY, gray, white);
        hits.add(new Hit(8, y, bw, ROW_H, () -> change(() -> edit.rotation = (edit.rotation + 1) % 4)));
        PhoneDraw.button(g, 12 + bw, y, bw, ROW_H, PhoneLang.tr("gallery.flip"), mouseX, mouseY, edit.flip ? PhoneTheme.accentTint(0x90) : gray, white);
        hits.add(new Hit(12 + bw, y, bw, ROW_H, () -> change(() -> edit.flip = !edit.flip)));
        String crop = PhotoEdit.CROP_LABELS[edit.crop] == null ? PhoneLang.tr("gallery.crop_original") : PhotoEdit.CROP_LABELS[edit.crop];
        PhoneDraw.button(g, 16 + 2 * bw, y, bw, ROW_H, crop, mouseX, mouseY, edit.crop != 0 ? PhoneTheme.accentTint(0x90) : gray, white);
        hits.add(new Hit(16 + 2 * bw, y, bw, ROW_H, () -> change(() -> edit.crop = (edit.crop + 1) % PhotoEdit.CROPS.length)));

        y += ROW_H + 6;
        stepper(g, y, PhoneLang.tr("gallery.filter"), edit.filter.displayName(), mouseX, mouseY,
                dir -> edit.filter = CameraFilter.values()[Math.floorMod(edit.filter.ordinal() + dir, CameraFilter.values().length)]);
        y += ROW_H + 4;
        stepper(g, y, PhoneLang.tr("gallery.brightness"), signed(edit.brightness), mouseX, mouseY,
                dir -> edit.brightness = clampAdjust(edit.brightness + dir));
        y += ROW_H + 4;
        stepper(g, y, PhoneLang.tr("gallery.contrast"), signed(edit.contrast), mouseX, mouseY,
                dir -> edit.contrast = clampAdjust(edit.contrast + dir));
        y += ROW_H + 4;
        stepper(g, y, PhoneLang.tr("gallery.saturation"), signed(edit.saturation), mouseX, mouseY,
                dir -> edit.saturation = clampAdjust(edit.saturation + dir));

        int by = contentHeight() - 24;
        int half = (w - 24) / 2;
        PhoneDraw.button(g, 8, by, half, 18, PhoneLang.tr("gallery.save_copy"), mouseX, mouseY, PhoneTheme.accent(), PhoneTheme.onAccent());
        hits.add(new Hit(8, by, half, 18, () -> save(false)));
        PhoneDraw.button(g, 16 + half, by, half, 18, PhoneLang.tr("gallery.replace"), mouseX, mouseY, gray, white);
        hits.add(new Hit(16 + half, by, half, 18, () -> save(true)));
    }

    /** Строка «Подпись   [-] значение [+]». */
    private void stepper(GuiGraphics g, int y, String label, String value, int mx, int my, java.util.function.IntConsumer onStep) {
        int w = contentWidth();
        PhoneDraw.text(g, label, 10, y + 6, 0xFFFFFFFF, 0.75f, false);
        int right = w - 8;
        int bs = ROW_H;
        PhoneDraw.button(g, right - bs, y, bs, bs, "+", mx, my, 0xFF1A2232, 0xFFFFFFFF);
        hits.add(new Hit(right - bs, y, bs, bs, () -> change(() -> onStep.accept(1))));
        int valueW = 40;
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(value, valueW - 4, 0.75f), right - bs - valueW / 2f, y + 6, PhoneTheme.accent(), 0.75f, false);
        PhoneDraw.button(g, right - bs - valueW - bs, y, bs, bs, "-", mx, my, 0xFF1A2232, 0xFFFFFFFF);
        hits.add(new Hit(right - bs - valueW - bs, y, bs, bs, () -> change(() -> onStep.accept(-1))));
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    private static int clampAdjust(int v) {
        return Math.max(-PhotoEdit.ADJUST_STEPS, Math.min(PhotoEdit.ADJUST_STEPS, v));
    }

    private void change(Runnable mutation) {
        mutation.run();
        editDirty = true;
    }

    private void save(boolean replace) {
        PhotoStore.Photo photo = current();
        if (photo == null || saving) {
            return;
        }
        if (edit.isIdentity()) {
            onBack();
            return;
        }
        saving = true;
        PhotoEdit snapshot = edit.copy();
        ClientFiles.IO.execute(() -> {
            PhotoStore.Photo saved = null;
            try {
                Picture result = snapshot.apply(ImageOps.read(photo.file()));
                PhotoMeta original = PhotoStore.readMeta(photo);
                PhotoMeta newMeta = original != null ? original.copy() : new PhotoMeta();
                if (newMeta.takenAt == 0) {
                    newMeta.takenAt = photo.modified();
                }
                newMeta.edited = true;
                String name;
                if (replace) {
                    name = photo.name();
                } else {
                    name = PhotoStore.uniqueName(photo.name() + "_edit");
                    newMeta.editedFrom = photo.name();
                }
                saved = PhotoStore.save(result, newMeta, name);
            } catch (Exception e) {
                BlockPhoneFabric.LOGGER.warn("Failed to save edited photo", e);
            }
            PhotoStore.Photo savedPhoto = saved;
            Minecraft.getInstance().execute(() -> {
                saving = false;
                closeEditor();
                if (replace) {
                    PhotoTextures.invalidate(photo);
                }
                mode = Mode.VIEWER;
                if (savedPhoto != null) {
                    openOnShow(savedPhoto);
                }
                refreshList();
            });
        });
    }

    // --- Ввод ---

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) {
            return false;
        }
        for (Hit hit : List.copyOf(hits)) {
            if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                hit.action().run();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (mode == Mode.GRID) {
            scroll -= amount * 20;
            clampScroll();
            return true;
        }
        if (mode == Mode.VIEWER) {
            int next = index + (amount < 0 ? 1 : -1);
            if (next >= 0 && next < photos.size()) {
                openViewer(next);
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (mode == Mode.VIEWER) {
            if (event.key() == GLFW.GLFW_KEY_LEFT && index > 0) {
                openViewer(index - 1);
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_RIGHT && index < photos.size() - 1) {
                openViewer(index + 1);
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_DELETE) {
                askDelete();
                return true;
            }
        }
        return false;
    }
}
