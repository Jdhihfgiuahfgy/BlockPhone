package dev.blockphone.fabric.client.apps.messenger;

import dev.blockphone.common.protocol.data.StickerData.PackInfo;
import dev.blockphone.common.protocol.data.StickerData.StickerRef;
import dev.blockphone.common.protocol.packet.StickerPackets;
import dev.blockphone.fabric.client.messenger.StickerClient;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.Images;
import net.minecraft.client.gui.GuiGraphics;

/** Набор стикеров (открывается нажатием на стикер в чате): все стикеры, автор, «Добавить себе» / «Убрать». */
final class StickerPackView extends MessengerView {
    private final long packId;
    private int buttonY = -100;

    StickerPackView(MessengerApp app, long packId) {
        super(app);
        this.packId = packId;
    }

    @Override
    String title() {
        PackInfo pack = StickerClient.pack(packId);
        return pack != null ? pack.title() : MessengerApp.tr("sticker.pack");
    }

    @Override
    void onShown() {
        StickerClient.requestPack(packId);
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        PackInfo pack = StickerClient.pack(packId);
        if (pack == null) {
            PhoneDraw.centeredText(g, MessengerApp.tr(packId == 0 ? "sticker.pack_deleted" : "messenger.loading"), w / 2f,
                    height() / 2f - 6, PhoneTheme.secondaryText(), 0.75f, false);
            return;
        }
        PhoneDraw.text(g, PhoneDraw.ellipsize(MessengerApp.tr("sticker.by", pack.owner().name()), w - 16, 0.65f), 8, 6,
                PhoneTheme.secondaryText(), 0.65f, false);
        int cell = StickersView.CELL + 8;
        int perRow = Math.max(1, (w - 12) / (cell + 4));
        int y = 18;
        for (int i = 0; i < pack.stickers().size(); i++) {
            StickerRef sticker = pack.stickers().get(i);
            int x = 8 + (i % perRow) * (cell + 4);
            int cy = y + (i / perRow) * (cell + 4);
            Images.drawFit(g, sticker.image(), x, cy, cell, cell);
        }
        y += ((pack.stickers().size() + perRow - 1) / perRow) * (cell + 4) + 6;
        buttonY = Math.min(y, height() - 24);
        if (!pack.own()) {
            PhoneDraw.button(g, 8, buttonY, w - 16, 18, MessengerApp.tr(pack.installed() ? "sticker.uninstall" : "sticker.install"),
                    mouseX, mouseY, pack.installed() ? PhoneDraw.ButtonStyle.NORMAL : PhoneDraw.ButtonStyle.PRIMARY);
        }
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        PackInfo pack = StickerClient.pack(packId);
        if (pack != null && !pack.own() && button == 0 && PhoneDraw.inside(x, y, 8, buttonY, width() - 16, 18)) {
            StickerClient.edit(pack.installed() ? StickerPackets.PackAction.UNINSTALL : StickerPackets.PackAction.INSTALL, packId, "");
            return true;
        }
        return false;
    }
}
