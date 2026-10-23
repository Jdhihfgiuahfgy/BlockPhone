package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.fabric.client.ui.Scrollbar;
import dev.blockphone.common.protocol.data.StickerData;
import dev.blockphone.common.protocol.data.StickerData.Limits;
import dev.blockphone.common.protocol.data.StickerData.PackInfo;
import dev.blockphone.common.protocol.data.StickerData.StickerRef;
import dev.blockphone.common.protocol.packet.StickerPackets;
import dev.blockphone.fabric.client.messenger.StickerClient;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.Images;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * «Мои стикеры»: свои наборы (создать, переименовать, удалить, добавить стикер из галереи, убрать стикер — ПКМ по нему)
 * и добавленные чужие (убрать). Ограничения сервера — внизу.
 */
final class StickersView extends MessengerView {
    static final int CELL = 34;

    private final TextArea title = new TextArea(false, 0.75f);
    private final List<Hit> hits = new ArrayList<>();
    private double scroll;
    private int total;
    /** Набор, который переименовывают (0 — создают новый, −1 — поле скрыто). */
    private long naming = -1;

    private record Hit(int x, int y, int w, int h, Runnable left, Runnable right) {
    }

    StickersView(MessengerApp app) {
        super(app);
        title.setMaxLength(StickerData.MAX_TITLE);
    }

    @Override
    String title() {
        return MessengerApp.tr("sticker.title");
    }

    @Override
    void onShown() {
        StickerClient.ensureLoaded();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        hits.clear();
        int w = width();
        Limits limits = StickerClient.limits();
        if (limits == null || !limits.enabled()) {
            PhoneDraw.wrapped(g, MessengerApp.tr(limits == null ? "messenger.loading" : "sticker.error.disabled"), 12, height() / 2f - 10,
                    w - 24, PhoneTheme.secondaryText(), 0.75f, false);
            return;
        }
        scroll = Math.max(0, Math.min(Math.max(0, total - height()), scroll));
        Scrollbar.report(0, height(), scroll, Math.max(0, total - height()), false, v -> scroll = v, () -> scroll);
        g.enableScissor(0, 0, w, height());
        int y = 6 - (int) scroll;
        int top = y;

        // Новый набор / переименование.
        if (naming >= 0) {
            PhoneDraw.text(g, MessengerApp.tr(naming == 0 ? "sticker.new_pack" : "sticker.rename"), 8, y, PhoneTheme.secondaryText(), 0.62f, false);
            y += 9;
            title.setBounds(8, y, w - 76, 16);
            title.setPlaceholder(MessengerApp.tr("sticker.pack_name"));
            title.render(g, mouseX, mouseY);
            button(g, w - 64, y, 56, 16, MessengerApp.tr("messenger.save"), mouseX, mouseY, PhoneDraw.ButtonStyle.PRIMARY, this::saveTitle);
            y += 22;
        } else if (StickerClient.ownCount() < limits.maxPacks()) {
            button(g, 8, y, w - 16, 18, MessengerApp.tr("sticker.new_pack"), mouseX, mouseY, PhoneDraw.ButtonStyle.PRIMARY, () -> {
                naming = 0;
                title.setText("");
                title.setFocused(true);
            });
            y += 24;
        }

        for (PackInfo pack : StickerClient.mine()) {
            y = renderPack(g, pack, limits, y, w, mouseX, mouseY);
        }
        if (StickerClient.mine().isEmpty()) {
            PhoneDraw.wrapped(g, MessengerApp.tr("sticker.empty"), 10, y + 4, w - 20, PhoneTheme.secondaryText(), 0.7f, false);
            y += 30;
        }
        String info = MessengerApp.tr("sticker.limits", limits.maxPacks(), limits.maxPerPack(), limits.maxBytes() / 1024, limits.maxSide());
        PhoneDraw.wrapped(g, info, 10, y + 4, w - 20, PhoneTheme.secondaryText(), 0.55f, false);
        y += PhoneDraw.wrappedHeight(info, w - 20, 0.55f) + 10;
        total = y - top;
        g.disableScissor();
    }

    private int renderPack(GuiGraphics g, PackInfo pack, Limits limits, int y, int w, int mx, int my) {
        PhoneDraw.text(g, PhoneDraw.ellipsize(pack.title(), w - 90, 0.8f), 8, y + 2, PhoneTheme.text(), 0.8f, false);
        String sub = pack.own() ? MessengerApp.tr("sticker.count", pack.stickers().size(), limits.maxPerPack())
                : MessengerApp.tr("sticker.by", pack.owner().name());
        PhoneDraw.text(g, PhoneDraw.ellipsize(sub, w - 90, 0.55f), 8, y + 11, PhoneTheme.secondaryText(), 0.55f, false);
        if (pack.own()) {
            button(g, w - 76, y, 34, 14, MessengerApp.tr("sticker.rename_short"), mx, my, PhoneDraw.ButtonStyle.NORMAL, () -> {
                naming = pack.id();
                title.setText(pack.title());
                title.setFocused(true);
            });
            button(g, w - 40, y, 32, 14, "×", mx, my, PhoneDraw.ButtonStyle.DANGER, () -> app.showDialog(PhoneDialog.confirmDelete(
                    MessengerApp.tr("sticker.delete_title"), MessengerApp.tr("sticker.delete_text", pack.title()),
                    () -> StickerClient.edit(StickerPackets.PackAction.DELETE, pack.id(), ""))));
        } else {
            button(g, w - 70, y, 62, 14, MessengerApp.tr("sticker.uninstall"), mx, my, PhoneDraw.ButtonStyle.NORMAL,
                    () -> StickerClient.edit(StickerPackets.PackAction.UNINSTALL, pack.id(), ""));
        }
        y += 22;
        int perRow = Math.max(1, (w - 12) / (CELL + 4));
        List<StickerRef> stickers = pack.stickers();
        int cells = stickers.size() + (pack.own() && stickers.size() < limits.maxPerPack() ? 1 : 0);
        for (int i = 0; i < cells; i++) {
            int x = 8 + (i % perRow) * (CELL + 4);
            int cy = y + (i / perRow) * (CELL + 4);
            boolean hover = PhoneDraw.inside(mx, my, x, cy, CELL, CELL);
            PhoneDraw.roundedRect(g, x, cy, x + CELL, cy + CELL, 4, hover ? PhoneTheme.cardHover() : PhoneTheme.card());
            if (i < stickers.size()) {
                StickerRef sticker = stickers.get(i);
                Images.drawFit(g, sticker.image(), x + 2, cy + 2, CELL - 4, CELL - 4);
                if (pack.own()) {
                    hits.add(new Hit(x, cy, CELL, CELL, null, () -> app.showDialog(PhoneDialog.confirmDelete(
                            MessengerApp.tr("sticker.remove_title"), MessengerApp.tr("sticker.remove_text"),
                            () -> StickerClient.removeSticker(sticker.id())))));
                }
            } else {
                PhoneDraw.centeredText(g, "+", x + CELL / 2f, cy + CELL / 2f - 6, PhoneTheme.accent(), 1.5f, false);
                hits.add(new Hit(x, cy, CELL, CELL, () -> app.push(new StickerEditorView(app, pack.id())), null));
            }
        }
        y += ((cells + perRow - 1) / perRow) * (CELL + 4) + 8;
        g.fill(8, y - 4, w - 8, y - 3, PhoneTheme.separator());
        return y + 2;
    }

    private void saveTitle() {
        String value = title.text().strip();
        if (value.isEmpty()) {
            return;
        }
        if (naming == 0) {
            StickerClient.edit(StickerPackets.PackAction.CREATE, 0, value);
        } else {
            StickerClient.edit(StickerPackets.PackAction.RENAME, naming, value);
        }
        naming = -1;
        title.setFocused(false);
    }

    private void button(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my, PhoneDraw.ButtonStyle style, Runnable action) {
        PhoneDraw.button(g, x, y, w, h, label, mx, my, style);
        hits.add(new Hit(x, y, w, h, action, null));
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (naming >= 0 && title.mouseClicked(x, y, button)) {
            return true;
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (PhoneDraw.inside(x, y, hit.x(), hit.y(), hit.w(), hit.h())) {
                Runnable action = button == 0 ? hit.left() : button == 1 ? hit.right() : null;
                if (action != null) {
                    action.run();
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    boolean mouseScrolled(double x, double y, double amount) {
        scroll -= amount * 20;
        return true;
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        if (naming >= 0 && title.isFocused()) {
            if (event.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER) {
                saveTitle();
                return true;
            }
            return title.keyPressed(event);
        }
        return false;
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return naming >= 0 && title.isFocused() && title.charTyped(event);
    }

    @Override
    boolean capturesKeyboard() {
        return naming >= 0 && title.isFocused();
    }

    @Override
    boolean onBack() {
        if (naming >= 0) {
            naming = -1;
            title.setFocused(false);
            return true;
        }
        return false;
    }
}
