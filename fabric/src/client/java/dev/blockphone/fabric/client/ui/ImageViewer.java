package dev.blockphone.fabric.client.ui;

import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

/** Просмотр фото поверх приложения: фото целиком на тёмном фоне, клик или «назад» — закрыть. */
public final class ImageViewer {
    private @Nullable ImageRef image;

    public void open(ImageRef image) {
        this.image = image;
    }

    public void close() {
        image = null;
    }

    public boolean isOpen() {
        return image != null;
    }

    public void render(GuiGraphics g, int w, int h) {
        if (image == null) {
            return;
        }
        g.fill(0, 0, w, h, 0xF0000000);
        Images.drawFit(g, image, 2, 10, w - 4, h - 26);
        PhoneDraw.centeredText(g, PhoneLang.tr("social.tap_to_close"), w / 2f, h - 11, 0xFF8A95AB, 0.6f, false);
    }

    /** Клик закрывает просмотр. */
    public boolean mouseClicked() {
        if (image == null) {
            return false;
        }
        image = null;
        return true;
    }
}
