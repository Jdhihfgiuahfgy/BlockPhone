package dev.blockphone.fabric.client.apps;

import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.AppRegistry;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;

/** Заглушка для приложений, которые появятся на следующих этапах. */
public final class PlaceholderApp extends PhoneApp {

    public PlaceholderApp(String id) {
        super(id);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        int size = 48;
        int x = (contentWidth() - size) / 2;
        int y = contentHeight() / 2 - 60;
        AppRegistry.Entry entry = AppRegistry.get(id());
        if (entry != null) {
            PhoneDraw.texture(g, entry.icon(), x, y, size, size, 64, 64);
        }
        float cx = contentWidth() / 2f;
        PhoneDraw.centeredText(g, title(), cx, y + size + 10, PhoneTheme.text(), 1f, false);
        PhoneDraw.centeredText(g, PhoneLang.tr("placeholder.soon"), cx, y + size + 24, PhoneTheme.secondaryText(), 0.75f, false);
    }
}
