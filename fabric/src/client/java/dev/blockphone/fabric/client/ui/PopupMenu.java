package dev.blockphone.fabric.client.ui;

import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/** Всплывающее меню (пункты-действия), например «Пожаловаться», «Удалить». Клик мимо — закрыть. */
public final class PopupMenu {
    private static final int ROW_H = 14;

    private record Item(String label, Runnable action, boolean danger) {
    }

    private final List<Item> items = new ArrayList<>();
    private boolean open;
    private int x;
    private int y;
    private int width;

    public PopupMenu add(String label, Runnable action) {
        items.add(new Item(label, action, false));
        return this;
    }

    public PopupMenu addDanger(String label, Runnable action) {
        items.add(new Item(label, action, true));
        return this;
    }

    /** Показать меню у точки (x, y), не выходя за область areaW×areaH. */
    public void show(int px, int py, int areaW, int areaH) {
        if (items.isEmpty()) {
            return;
        }
        width = 70;
        for (Item item : items) {
            width = Math.max(width, (int) PhoneDraw.width(item.label(), 0.75f) + 14);
        }
        width = Math.min(width, areaW - 8);
        int h = items.size() * ROW_H + 4;
        x = Math.max(4, Math.min(areaW - width - 4, px - width + 8));
        y = Math.max(2, Math.min(areaH - h - 2, py));
        open = true;
    }

    public boolean isOpen() {
        return open;
    }

    public void close() {
        open = false;
    }

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        if (!open) {
            return;
        }
        int h = items.size() * ROW_H + 4;
        PhoneDraw.roundedRect(g, x - 1, y, x + width + 2, y + h + 2, 4, 0x40000000);
        PhoneDraw.panel(g, x, y, x + width, y + h, PhoneTheme.card());
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            int ry = y + 2 + i * ROW_H;
            if (PhoneDraw.inside(mouseX, mouseY, x, ry, width, ROW_H)) {
                g.fill(x + 2, ry, x + width - 2, ry + ROW_H, PhoneTheme.cardHover());
            }
            PhoneDraw.text(g, PhoneDraw.ellipsize(item.label(), width - 10, 0.75f), x + 6, ry + 4,
                    item.danger() ? PhoneTheme.danger() : PhoneTheme.text(), 0.75f, false);
        }
    }

    /** Клик, пока меню открыто: выполняет пункт или закрывает меню. Всегда «поглощает» клик. */
    public boolean mouseClicked(double mx, double my) {
        if (!open) {
            return false;
        }
        open = false;
        for (int i = 0; i < items.size(); i++) {
            if (PhoneDraw.inside(mx, my, x, y + 2 + i * ROW_H, width, ROW_H)) {
                items.get(i).action().run();
                break;
            }
        }
        return true;
    }
}
