package dev.blockphone.fabric.client.ui;

import dev.blockphone.common.protocol.data.ImageRef.PhotoPolicy;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.PhotoMeta;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Выбор фото из галереи поверх приложения (для поста или сообщения). Снимки, которые не подходят под политику
 * сервера (например, «только снятые на этом сервере»), затемнены и не выбираются.
 */
public final class PhotoPicker {
    private static final int COLUMNS = 3;
    private static final int GAP = 2;
    private static final int TOP = 30;

    private record Item(PhotoStore.Photo photo, boolean allowed) {
    }

    private final Consumer<PhotoStore.Photo> onPick;
    private boolean open;
    private boolean loading;
    private List<Item> items = List.of();
    private double scroll;
    private int width = 160;
    private long deniedAt;

    /** Не null — выбор нескольких: окно не закрывается, отмеченные — с галочкой, onPick переключает отметку. */
    private final @Nullable Predicate<PhotoStore.Photo> selected;
    /** Не для сервера (например, Telegram): политика сервера не действует. */
    private final boolean anyPhoto;

    public PhotoPicker(Consumer<PhotoStore.Photo> onPick) {
        this(onPick, false, null);
    }

    public PhotoPicker(Consumer<PhotoStore.Photo> onPick, boolean anyPhoto, @Nullable Predicate<PhotoStore.Photo> selected) {
        this.onPick = onPick;
        this.anyPhoto = anyPhoto;
        this.selected = selected;
    }

    public boolean isOpen() {
        return open;
    }

    public void open() {
        open = true;
        scroll = 0;
        loading = true;
        ClientFiles.IO.execute(() -> {
            List<Item> list = new ArrayList<>();
            for (PhotoStore.Photo photo : PhotoStore.list()) {
                PhotoMeta meta = PhotoStore.readMeta(photo);
                list.add(new Item(photo, anyPhoto || ClientSession.photoAllowed(meta)));
            }
            Minecraft.getInstance().execute(() -> {
                items = list;
                loading = false;
            });
        });
    }

    public void close() {
        open = false;
    }

    private int cell() {
        return (width - 8 - GAP * (COLUMNS - 1)) / COLUMNS;
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, int w, int h) {
        if (!open) {
            return;
        }
        width = w;
        g.fill(0, 0, w, h, PhoneTheme.background());
        PhoneDraw.text(g, PhoneLang.tr("photo.pick"), 8, 5, PhoneTheme.text(), 0.85f, false);
        PhoneDraw.button(g, w - 22, 2, 18, 13, selected != null ? "✔" : "×", mouseX, mouseY,
                selected != null ? PhoneDraw.ButtonStyle.PRIMARY : PhoneDraw.ButtonStyle.NORMAL);
        String hint = selected != null ? PhoneLang.tr("photo.pick_many")
                : ClientSession.photoPolicy() == PhotoPolicy.SERVER ? PhoneLang.tr("photo.only_server") : PhoneLang.tr("photo.any");
        PhoneDraw.text(g, PhoneDraw.ellipsize(hint, w - 16, 0.6f), 8, 18, PhoneTheme.secondaryText(), 0.6f, false);
        if (loading) {
            PhoneDraw.centeredText(g, PhoneLang.tr("messenger.loading"), w / 2f, h / 2f, PhoneTheme.secondaryText(), 0.75f, false);
            return;
        }
        if (items.isEmpty()) {
            PhoneDraw.wrapped(g, PhoneLang.tr("photo.no_photos"), 12, h / 2f - 10, w - 24, PhoneTheme.secondaryText(), 0.75f, true);
            return;
        }
        clampScroll(h);
        int cell = cell();
        g.enableScissor(0, TOP, w, h);
        for (int i = 0; i < items.size(); i++) {
            int x = 4 + (i % COLUMNS) * (cell + GAP);
            int y = TOP + (i / COLUMNS) * (cell + GAP) - (int) scroll;
            if (y + cell < TOP || y > h) {
                continue;
            }
            Item item = items.get(i);
            PhotoTextures.Image thumb = PhotoTextures.thumbnail(item.photo());
            if (thumb != null) {
                PhoneDraw.texture(g, thumb.id(), x, y, cell, cell, thumb.width(), thumb.height());
            } else {
                g.fill(x, y, x + cell, y + cell, PhoneTheme.card());
            }
            if (selected != null && selected.test(item.photo())) {
                PhoneDraw.outline(g, x, y, cell, cell, PhoneTheme.accent());
                PhoneDraw.outline(g, x + 1, y + 1, cell - 2, cell - 2, PhoneTheme.accent());
                PhoneDraw.roundedRect(g, x + cell - 12, y + 2, x + cell - 2, y + 12, 5, PhoneTheme.accent());
                PhoneDraw.centeredText(g, "✔", x + cell - 7, y + 4, PhoneTheme.onAccent(), 0.6f, false);
            }
            if (!item.allowed()) {
                g.fill(x, y, x + cell, y + cell, 0xB0000000);
                PhoneDraw.centeredText(g, "×", x + cell / 2f, y + cell / 2f - 4, 0xFFB0B8C8, 1f, false);
            } else if (PhoneDraw.inside(mouseX, mouseY, x, y, cell, cell) && mouseY >= TOP) {
                g.fill(x, y, x + cell, y + cell, 0x30FFFFFF);
            }
        }
        g.disableScissor();
        if (System.currentTimeMillis() - deniedAt < 3000) {
            String text = PhoneLang.tr("photo.error.not_this_server");
            int th = PhoneDraw.wrappedHeight(text, w - 28, 0.75f) + 10;
            PhoneDraw.roundedRect(g, 8, h - th - 8, w - 8, h - 8, 4, 0xE0B8403A);
            PhoneDraw.wrapped(g, text, 14, h - th - 3, w - 28, 0xFFFFFFFF, 0.75f, true);
        }
    }

    private void clampScroll(int h) {
        int rows = (items.size() + COLUMNS - 1) / COLUMNS;
        double max = Math.max(0, rows * (cell() + GAP) - (h - TOP));
        scroll = Math.max(0, Math.min(max, scroll));
        Scrollbar.report(TOP, h, scroll, max, false, v -> scroll = v);
    }

    public boolean mouseClicked(double x, double y, int button) {
        if (!open) {
            return false;
        }
        if (PhoneDraw.inside(x, y, width - 22, 2, 18, 13)) {
            close();
            return true;
        }
        if (y < TOP || loading) {
            return true;
        }
        int cell = cell();
        int col = (int) ((x - 4) / (cell + GAP));
        int row = (int) ((y - TOP + scroll) / (cell + GAP));
        int index = row * COLUMNS + col;
        if (col >= 0 && col < COLUMNS && index >= 0 && index < items.size()) {
            Item item = items.get(index);
            if (item.allowed()) {
                if (selected == null) {
                    close();
                }
                onPick.accept(item.photo());
            } else {
                deniedAt = System.currentTimeMillis();
            }
        }
        return true;
    }

    public boolean mouseScrolled(double amount) {
        if (!open) {
            return false;
        }
        scroll -= amount * 20;
        return true;
    }
}
