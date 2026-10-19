package dev.blockphone.fabric.client.apps.home;

import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.AppRegistry;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneClock;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneLayout;
import dev.blockphone.fabric.client.phone.PhoneState;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Рабочий стол: часы, дата и сетка иконок приложений. */
public final class HomeApp extends PhoneApp {
    private static final int COLUMNS = 4;
    private static final int CELL_W = PhoneLayout.APP_W / COLUMNS;
    private static final int CELL_H = 48;
    private static final int ICON = 28;
    private static final int GRID_TOP = 78;

    public HomeApp() {
        super(PhoneState.HOME);
    }

    @Override
    public boolean hasHeader() {
        return false;
    }

    @Override
    public boolean transparentBackground() {
        return true;
    }

    @Override
    public boolean lightStatusBar() {
        return true;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        float cx = contentWidth() / 2f;
        PhoneDraw.centeredText(g, PhoneClock.time(), cx, 14, 0xFFFFFFFF, 3f, true);
        PhoneDraw.centeredText(g, PhoneClock.date(), cx, 44, 0xE6FFFFFF, 0.75f, true);

        List<AppRegistry.Entry> apps = AppRegistry.visible();
        for (int i = 0; i < apps.size(); i++) {
            AppRegistry.Entry entry = apps.get(i);
            int x = (i % COLUMNS) * CELL_W + (CELL_W - ICON) / 2;
            int y = GRID_TOP + (i / COLUMNS) * CELL_H;
            PhoneDraw.texture(g, entry.icon(), x, y, ICON, ICON, 64, 64);
            if (PhoneDraw.inside(mouseX, mouseY, x, y, ICON, ICON)) {
                PhoneDraw.roundedRect(g, x, y, x + ICON, y + ICON, 7, 0x40FFFFFF);
            }
            int badge = entry.badge().getAsInt();
            if (badge > 0) {
                String count = badge > 99 ? "99+" : String.valueOf(badge);
                float bw = Math.max(9, PhoneDraw.width(count, 0.6f) + 5);
                int bx = (int) (x + ICON - bw + 3);
                PhoneDraw.roundedRect(g, bx, y - 3, (int) (bx + bw), y + 6, 4, 0xFFFF3B30);
                PhoneDraw.centeredText(g, count, bx + bw / 2f, y - 1, 0xFFFFFFFF, 0.6f, false);
            }
            String label = PhoneDraw.ellipsize(PhoneLang.tr("app." + entry.id()), CELL_W, 0.6f);
            PhoneDraw.centeredText(g, label, x + ICON / 2f, y + ICON + 3, 0xFFFFFFFF, 0.6f, true);
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button != 0) {
            return false;
        }
        List<AppRegistry.Entry> apps = AppRegistry.visible();
        for (int i = 0; i < apps.size(); i++) {
            int ix = (i % COLUMNS) * CELL_W + (CELL_W - ICON) / 2;
            int iy = GRID_TOP + (i / COLUMNS) * CELL_H;
            if (PhoneDraw.inside(x, y, ix, iy, ICON, ICON)) {
                phone.openApp(apps.get(i).id());
                return true;
            }
        }
        return false;
    }
}
