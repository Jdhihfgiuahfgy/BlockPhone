package dev.blockphone.fabric.client.apps.home;

import dev.blockphone.fabric.client.config.PhoneSettings;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.AppRegistry;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneClock;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.PopupMenu;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Рабочий стол: часы и дата на первом экране, сетка иконок на нескольких экранах (свайп мышью влево/вправо, колесо,
 * точки внизу) и папки. ПКМ по иконке — меню (изменить рабочий стол, в новую папку…). В режиме правки иконки
 * перетаскиваются: на другую иконку — папка, между иконками — новое место, к краю экрана — на соседний экран.
 * Размер иконок — настройка {@link PhoneSettings#homeIconScale} (от него зависит число столбцов).
 */
public final class HomeApp extends PhoneApp {
    private static final int FIRST_TOP = 72;
    private static final int OTHER_TOP = 10;
    private static final int DOTS_H = 14;
    /** Сдвиг мыши, после которого нажатие считается перетаскиванием. */
    private static final int DRAG_START = 4;
    /** Сколько держать иконку у края, чтобы перелистнуть экран, мс. */
    private static final long EDGE_FLIP_MS = 550;

    private enum Drag { NONE, PRESSED, SWIPE, ICON }

    /** Ячейка на экране (для кликов и перетаскивания). */
    private record Cell(int index, int page, int x, int y, int w, int h, int iconX, int iconY) {
    }

    private List<HomeLayout.Item> items = List.of();
    private final Map<String, AppRegistry.Entry> entries = new HashMap<>();
    private final List<Cell> cells = new ArrayList<>();
    private int icon;
    private int columns;
    private int cellW;
    private int cellH;

    private int page;
    /** Отображаемая позиция (плавно догоняет page). */
    private float pagePos;
    private int pageCount = 1;

    private Drag drag = Drag.NONE;
    private int pressButton;
    private double pressX;
    private double pressY;
    private int pressIndex = -1;
    private double mouseX;
    private double mouseY;
    private long edgeSince;
    private int edgeDir;

    private boolean editing;
    private PopupMenu menu;
    /** Открытая папка (id) или null. */
    private String openFolder;
    private final List<Cell> folderCells = new ArrayList<>();
    private int folderX;
    private int folderY;
    private int folderW;
    private int folderH;
    private final TextArea folderName = new TextArea(false, 0.8f);

    public HomeApp() {
        super(PhoneState.HOME);
        folderName.setMaxLength(24);
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
    public boolean capturesKeyboard() {
        return folderName.isFocused();
    }

    @Override
    public void onHidden() {
        commitFolderName();
        editing = false;
        openFolder = null;
        menu = null;
        drag = Drag.NONE;
    }

    @Override
    public boolean onBack() {
        if (menu != null && menu.isOpen()) {
            menu.close();
            return true;
        }
        if (openFolder != null) {
            closeFolder();
            return true;
        }
        if (editing) {
            editing = false;
            return true;
        }
        if (page != 0) {
            page = 0;
            return true;
        }
        return false;
    }

    // --- Расположение ---

    private void layout() {
        PhoneSettings s = PhoneSettings.get();
        entries.clear();
        List<String> visible = new ArrayList<>();
        for (AppRegistry.Entry entry : AppRegistry.visible()) {
            if (s.homeHidden != null && s.homeHidden.contains(entry.id())) {
                continue;
            }
            entries.put(entry.id(), entry);
            visible.add(entry.id());
        }
        items = HomeLayout.arrange(visible, s.homeOrder, s.homeFolders);
        int w = contentWidth();
        icon = Math.round(28 * Math.max(0.7f, Math.min(1.4f, s.homeIconScale / 100f)));
        columns = Math.max(3, Math.min(6, w / (icon + 12)));
        cellW = w / columns;
        cellH = icon + (s.homeLabels ? 12 : 4) + 6;
        pageCount = 1;
        int left = items.size() - capacity(0);
        while (left > 0) {
            left -= capacity(pageCount);
            pageCount++;
        }
        if (drag == Drag.ICON) {
            // Пока тянем иконку, можно перейти на пустой экран после последнего.
            pageCount++;
        }
        page = Math.max(0, Math.min(pageCount - 1, page));
    }

    private int top(int p) {
        return p == 0 ? FIRST_TOP : OTHER_TOP;
    }

    private int rows(int p) {
        return Math.max(1, (contentHeight() - DOTS_H - top(p)) / cellH);
    }

    private int capacity(int p) {
        return rows(p) * columns;
    }

    private int pageStart(int p) {
        int start = 0;
        for (int i = 0; i < p; i++) {
            start += capacity(i);
        }
        return start;
    }

    // --- Отрисовка ---

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        mouseX = mx;
        mouseY = my;
        layout();
        int w = contentWidth();
        float target = page;
        float swipe = drag == Drag.SWIPE ? (float) (mx - pressX) / w : 0;
        boolean animate = PhoneSettings.get().animations;
        pagePos = animate ? pagePos + (target - pagePos) * 0.3f : target;
        if (Math.abs(target - pagePos) < 0.002f) {
            pagePos = target;
        }
        float pos = pagePos - swipe;
        cells.clear();
        g.enableScissor(0, 0, w, contentHeight());
        for (int p = Math.max(0, (int) Math.floor(pos)); p <= Math.min(pageCount - 1, (int) Math.ceil(pos)); p++) {
            int dx = Math.round((p - pos) * w);
            renderPage(g, p, dx, mx, my);
        }
        g.disableScissor();
        renderDots(g, w);
        if (editing) {
            int bw = 46;
            PhoneDraw.button(g, w - bw - 6, 2, bw, 14, PhoneLang.tr("home.done"), mx, my, PhoneDraw.ButtonStyle.PRIMARY);
            if (drag != Drag.ICON) {
                PhoneDraw.wrapped(g, PhoneLang.tr("home.edit_hint"), 8, contentHeight() - DOTS_H - 18, w - 16, 0xE6FFFFFF, 0.55f, true);
            }
        }
        if (drag == Drag.ICON) {
            renderDragged(g, mx, my);
        }
        if (openFolder != null) {
            renderFolder(g, mx, my);
        }
        if (menu != null) {
            menu.render(g, mx, my);
        }
    }

    private void renderPage(GuiGraphics g, int p, int dx, int mx, int my) {
        if (p == 0) {
            float cx = contentWidth() / 2f + dx;
            PhoneClock.drawBig(g, cx, 14);
            PhoneDraw.centeredText(g, PhoneClock.date(), cx, 44, 0xE6FFFFFF, 0.75f, true);
        }
        int start = pageStart(p);
        int count = Math.min(capacity(p), items.size() - start);
        int top = top(p);
        long now = System.currentTimeMillis();
        for (int k = 0; k < count; k++) {
            int i = start + k;
            HomeLayout.Item item = items.get(i);
            int cx = dx + (k % columns) * cellW;
            int cy = top + (k / columns) * cellH;
            int ix = cx + (cellW - icon) / 2;
            int iy = cy + 2;
            cells.add(new Cell(i, p, cx, cy, cellW, cellH, ix, iy));
            if (drag == Drag.ICON && i == pressIndex) {
                // Место перетаскиваемой иконки — пустая рамка.
                PhoneDraw.rawRoundedRect(g, ix, iy, ix + icon, iy + icon, icon / 5, 0x30FFFFFF);
                continue;
            }
            g.pose().pushMatrix();
            if (editing) {
                float wobble = (float) Math.sin(now / 90.0 + i * 1.7) * 0.7f;
                g.pose().translate(wobble, 0);
            }
            drawItem(g, item, ix, iy, icon, true);
            g.pose().popMatrix();
            boolean hover = drag != Drag.ICON && openFolder == null && PhoneDraw.inside(mx, my, ix, iy, icon, icon);
            if (hover) {
                PhoneDraw.rawRoundedRect(g, ix, iy, ix + icon, iy + icon, icon / 5, 0x30FFFFFF);
            }
            if (drag == Drag.ICON && dropTarget() == i) {
                boolean merge = mergeTarget(i);
                if (merge) {
                    PhoneDraw.rawRoundedRect(g, ix - 3, iy - 3, ix + icon + 3, iy + icon + 3, icon / 5 + 2, 0x60FFFFFF);
                } else {
                    g.fill(cx, iy, cx + 2, iy + icon, PhoneTheme.accent());
                }
            }
        }
    }

    /** Иконка приложения или папки с подписью и значком непрочитанного. */
    private void drawItem(GuiGraphics g, HomeLayout.Item item, int x, int y, int size, boolean label) {
        int badge = 0;
        String name;
        if (item.folder()) {
            PhoneSettings.HomeFolder folder = PhoneSettings.get().homeFolders.get(item.folderId());
            name = folder == null || folder.name.isBlank() ? PhoneLang.tr("home.folder") : folder.name;
            PhoneDraw.rawRoundedRect(g, x, y, x + size, y + size, Math.max(3, size / 5), 0x60FFFFFF);
            PhoneDraw.rawRoundedRect(g, x + 1, y + 1, x + size - 1, y + size - 1, Math.max(3, size / 5) - 1, 0x50202838);
            int mini = Math.max(5, (size - 8) / 2);
            int gap = (size - 2 * mini) / 3;
            for (int j = 0; j < Math.min(4, item.apps().size()); j++) {
                AppRegistry.Entry entry = entries.get(item.apps().get(j));
                if (entry != null) {
                    entry.drawIcon(g, x + gap + (j % 2) * (mini + gap), y + gap + (j / 2) * (mini + gap), mini);
                }
            }
            for (String app : item.apps()) {
                AppRegistry.Entry entry = entries.get(app);
                badge += entry != null ? entry.badge().getAsInt() : 0;
            }
        } else {
            AppRegistry.Entry entry = entries.get(item.key());
            if (entry == null) {
                return;
            }
            entry.drawIcon(g, x, y, size);
            badge = entry.badge().getAsInt();
            name = entry.label();
        }
        if (badge > 0) {
            String count = badge > 99 ? "99+" : String.valueOf(badge);
            float bw = Math.max(9, PhoneDraw.width(count, 0.6f) + 5);
            int bx = (int) (x + size - bw + 3);
            PhoneDraw.roundedRect(g, bx, y - 3, (int) (bx + bw), y + 6, 4, PhoneTheme.danger());
            PhoneDraw.centeredText(g, count, bx + bw / 2f, y - 1, 0xFFFFFFFF, 0.6f, false);
        }
        if (label && PhoneSettings.get().homeLabels) {
            float scale = Math.min(0.65f, Math.max(0.5f, 0.6f * size / 28f));
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(name, cellW, scale), x + size / 2f, y + size + 3, 0xFFFFFFFF, scale, true);
        }
    }

    private void renderDots(GuiGraphics g, int w) {
        if (pageCount <= 1) {
            return;
        }
        int y = contentHeight() - DOTS_H / 2 - 2;
        int total = pageCount * 8 - 3;
        int x = (w - total) / 2;
        for (int p = 0; p < pageCount; p++) {
            int color = p == page ? 0xF0FFFFFF : 0x70FFFFFF;
            PhoneDraw.rawRoundedRect(g, x + p * 8, y - 2, x + p * 8 + 5, y + 3, 2, color);
        }
    }

    private void renderDragged(GuiGraphics g, int mx, int my) {
        if (pressIndex < 0 || pressIndex >= items.size()) {
            return;
        }
        int size = Math.round(icon * 1.12f);
        g.pose().pushMatrix();
        g.pose().translate(0, 0);
        drawItem(g, items.get(pressIndex), mx - size / 2, my - size / 2, size, false);
        g.pose().popMatrix();
    }

    // --- Папка ---

    private HomeLayout.Item folderItem() {
        for (HomeLayout.Item item : items) {
            if (item.key().equals(HomeLayout.FOLDER + openFolder)) {
                return item;
            }
        }
        return null;
    }

    private void renderFolder(GuiGraphics g, int mx, int my) {
        HomeLayout.Item item = folderItem();
        PhoneSettings.HomeFolder folder = PhoneSettings.get().homeFolders.get(openFolder);
        if (item == null || folder == null) {
            openFolder = null;
            return;
        }
        int w = contentWidth();
        g.fill(0, 0, w, contentHeight(), 0xA0000000);
        int cols = Math.min(4, Math.max(3, columns));
        int fw = w - 20;
        int cw = fw / cols;
        int rowsCount = (item.apps().size() + cols - 1) / cols;
        int fh = 30 + rowsCount * cellH + 6;
        folderX = 10;
        folderW = fw;
        folderH = fh;
        folderY = Math.max(16, (contentHeight() - fh) / 2);
        PhoneDraw.panel(g, folderX, folderY, folderX + fw, folderY + fh, PhoneTheme.card());
        folderName.setBounds(folderX + 8, folderY + 6, fw - 16, 16);
        if (folderName.isFocused()) {
            folderName.render(g, mx, my);
        } else {
            String name = folder.name.isBlank() ? PhoneLang.tr("home.folder") : folder.name;
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(name, fw - 30, 0.85f), folderX + fw / 2f, folderY + 10, PhoneTheme.text(), 0.85f, false);
            PhoneDraw.text(g, "✎", folderX + fw - 14, folderY + 9, PhoneTheme.secondaryText(), 0.8f, false);
        }
        folderCells.clear();
        for (int k = 0; k < item.apps().size(); k++) {
            AppRegistry.Entry entry = entries.get(item.apps().get(k));
            int cx = folderX + (k % cols) * cw;
            int cy = folderY + 28 + (k / cols) * cellH;
            int ix = cx + (cw - icon) / 2;
            int iy = cy + 2;
            folderCells.add(new Cell(k, 0, cx, cy, cw, cellH, ix, iy));
            if (entry == null) {
                continue;
            }
            entry.drawIcon(g, ix, iy, icon);
            if (PhoneDraw.inside(mx, my, ix, iy, icon, icon) && (menu == null || !menu.isOpen())) {
                PhoneDraw.rawRoundedRect(g, ix, iy, ix + icon, iy + icon, icon / 5, 0x30FFFFFF);
            }
            int badge = entry.badge().getAsInt();
            if (badge > 0) {
                String count = badge > 99 ? "99+" : String.valueOf(badge);
                float bw = Math.max(9, PhoneDraw.width(count, 0.6f) + 5);
                int bx = (int) (ix + icon - bw + 3);
                PhoneDraw.roundedRect(g, bx, iy - 3, (int) (bx + bw), iy + 6, 4, PhoneTheme.danger());
                PhoneDraw.centeredText(g, count, bx + bw / 2f, iy - 1, 0xFFFFFFFF, 0.6f, false);
            }
            PhoneDraw.centeredText(g, PhoneDraw.ellipsize(entry.label(), cw, 0.6f), ix + icon / 2f, iy + icon + 3, PhoneTheme.text(), 0.6f, false);
        }
    }

    private void openFolder(String id) {
        openFolder = id;
        folderName.setFocused(false);
    }

    private void closeFolder() {
        commitFolderName();
        openFolder = null;
    }

    private void startRename() {
        PhoneSettings.HomeFolder folder = PhoneSettings.get().homeFolders.get(openFolder);
        if (folder != null) {
            folderName.setText(folder.name.isBlank() ? PhoneLang.tr("home.folder") : folder.name);
            folderName.setFocused(true);
            folderName.moveCursorToEnd();
        }
    }

    private void commitFolderName() {
        if (!folderName.isFocused()) {
            return;
        }
        folderName.setFocused(false);
        PhoneSettings s = PhoneSettings.get();
        PhoneSettings.HomeFolder folder = openFolder != null ? s.homeFolders.get(openFolder) : null;
        if (folder != null) {
            folder.name = folderName.text().strip();
            s.save();
        }
    }

    // --- Действия с расположением ---

    private List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (HomeLayout.Item item : items) {
            keys.add(item.key());
        }
        return keys;
    }

    private void moveItem(int from, int to) {
        List<String> keys = keys();
        to = Math.max(0, Math.min(keys.size() - 1, to));
        if (from == to || from < 0 || from >= keys.size()) {
            return;
        }
        keys.add(to, keys.remove(from));
        HomeLayout.store(keys, PhoneSettings.get());
    }

    /** Приложение from — на элемент target: в папку target или новая папка из двух приложений. */
    private void mergeInto(int from, int target) {
        PhoneSettings s = PhoneSettings.get();
        List<String> keys = keys();
        String app = keys.get(from);
        String onto = keys.get(target);
        if (onto.startsWith(HomeLayout.FOLDER)) {
            PhoneSettings.HomeFolder folder = s.homeFolders.get(onto.substring(HomeLayout.FOLDER.length()));
            if (folder == null) {
                return;
            }
            folder.apps.remove(app);
            folder.apps.add(app);
            keys.remove(from);
        } else {
            String id = HomeLayout.newFolderId(s);
            PhoneSettings.HomeFolder folder = new PhoneSettings.HomeFolder();
            folder.apps.add(onto);
            folder.apps.add(app);
            s.homeFolders.put(id, folder);
            keys.set(target, HomeLayout.FOLDER + id);
            keys.remove(from);
        }
        HomeLayout.store(keys, s);
    }

    private void toNewFolder(int index) {
        PhoneSettings s = PhoneSettings.get();
        List<String> keys = keys();
        String app = keys.get(index);
        String id = HomeLayout.newFolderId(s);
        PhoneSettings.HomeFolder folder = new PhoneSettings.HomeFolder();
        folder.apps.add(app);
        s.homeFolders.put(id, folder);
        keys.set(index, HomeLayout.FOLDER + id);
        HomeLayout.store(keys, s);
        openFolder(id);
        startRename();
    }

    private void removeFromFolder(String folderId, String app) {
        PhoneSettings s = PhoneSettings.get();
        PhoneSettings.HomeFolder folder = s.homeFolders.get(folderId);
        if (folder == null) {
            return;
        }
        folder.apps.remove(app);
        List<String> keys = keys();
        int at = keys.indexOf(HomeLayout.FOLDER + folderId);
        keys.add(at < 0 ? keys.size() : at + 1, app);
        HomeLayout.store(keys, s);
        if (folder.apps.isEmpty()) {
            openFolder = null;
        }
    }

    private void disband(String folderId) {
        PhoneSettings s = PhoneSettings.get();
        PhoneSettings.HomeFolder folder = s.homeFolders.remove(folderId);
        if (folder == null) {
            return;
        }
        List<String> keys = keys();
        int at = keys.indexOf(HomeLayout.FOLDER + folderId);
        if (at >= 0) {
            keys.remove(at);
            keys.addAll(at, folder.apps);
        } else {
            keys.addAll(folder.apps);
        }
        HomeLayout.store(keys, s);
        openFolder = null;
    }

    private void resetLayout() {
        PhoneSettings s = PhoneSettings.get();
        s.homeOrder.clear();
        s.homeFolders.clear();
        s.homeHidden.clear();
        s.save();
        page = 0;
    }

    // --- Ввод ---

    private Cell cellAt(double x, double y) {
        for (Cell cell : cells) {
            if (PhoneDraw.inside(x, y, cell.iconX() - 3, cell.iconY() - 3, icon + 6, icon + 6)) {
                return cell;
            }
        }
        return null;
    }

    /** Индекс элемента, на который сейчас бросят перетаскиваемую иконку. */
    private int dropTarget() {
        for (Cell cell : cells) {
            if (cell.page() == page && PhoneDraw.inside(mouseX, mouseY, cell.x(), cell.y(), cell.w(), cell.h())) {
                return cell.index();
            }
        }
        return -1;
    }

    /** Бросок на центр иконки (не на край ячейки) — объединить в папку. Папку в папку нельзя. */
    private boolean mergeTarget(int target) {
        if (pressIndex < 0 || pressIndex >= items.size() || target == pressIndex || items.get(pressIndex).folder()) {
            return false;
        }
        for (Cell cell : cells) {
            if (cell.index() == target) {
                int inset = icon / 6;
                return PhoneDraw.inside(mouseX, mouseY, cell.iconX() + inset, cell.iconY() + inset, icon - 2 * inset, icon - 2 * inset);
            }
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        mouseX = x;
        mouseY = y;
        if (menu != null && menu.isOpen()) {
            return menu.mouseClicked(x, y);
        }
        if (openFolder != null) {
            return folderClicked(x, y, button);
        }
        if (editing && PhoneDraw.inside(x, y, contentWidth() - 52, 2, 46, 14)) {
            editing = false;
            return true;
        }
        Cell cell = cellAt(x, y);
        if (button == 1) {
            showMenu(cell, x, y);
            return true;
        }
        if (button != 0) {
            return false;
        }
        drag = Drag.PRESSED;
        pressButton = button;
        pressX = x;
        pressY = y;
        pressIndex = cell != null ? cell.index() : -1;
        // Точки внизу — переход на экран.
        if (pageCount > 1 && y > contentHeight() - DOTS_H) {
            int total = pageCount * 8 - 3;
            int dx = (int) x - (contentWidth() - total) / 2;
            if (dx >= -2 && dx < total + 2) {
                page = Math.max(0, Math.min(pageCount - 1, (dx + 1) / 8));
                drag = Drag.NONE;
            }
        }
        return true;
    }

    private void showMenu(Cell cell, double x, double y) {
        menu = new PopupMenu();
        if (cell != null) {
            HomeLayout.Item item = items.get(cell.index());
            int index = cell.index();
            if (item.folder()) {
                String id = item.folderId();
                menu.add(PhoneLang.tr("home.rename"), () -> {
                    openFolder(id);
                    startRename();
                });
                menu.add(PhoneLang.tr("home.disband"), () -> disband(id));
            } else {
                menu.add(PhoneLang.tr("home.new_folder"), () -> toNewFolder(index));
                addRemoveItem(item.key());
            }
        }
        if (!editing) {
            menu.add(PhoneLang.tr("home.edit"), () -> editing = true);
        } else {
            menu.add(PhoneLang.tr("home.done"), () -> editing = false);
        }
        PhoneSettings settings = PhoneSettings.get();
        if (settings.homeHidden != null && !settings.homeHidden.isEmpty()) {
            menu.add(PhoneLang.tr("home.restore_hidden", settings.homeHidden.size()), () -> {
                settings.homeHidden.clear();
                settings.save();
            });
        }
        menu.addDanger(PhoneLang.tr("home.reset"), this::resetLayout);
        menu.show((int) x, (int) y, contentWidth(), contentHeight());
    }

    /**
     * «Удалить» в меню иконки: своё приложение — удалить совсем (папку и данные приложения не трогаем — только его файлы),
     * встроенное — убрать с рабочего стола (возвращается из меню рабочего стола). Настройки убрать нельзя.
     */
    private void addRemoveItem(String appId) {
        if (appId.equals("settings")) {
            return;
        }
        AppRegistry.Entry entry = entries.get(appId);
        String name = entry != null ? entry.label() : appId;
        if (appId.startsWith(dev.blockphone.fabric.client.apps.custom.CustomApps.PREFIX)) {
            String id = appId.substring(dev.blockphone.fabric.client.apps.custom.CustomApps.PREFIX.length());
            menu.addDanger(PhoneLang.tr("home.uninstall"), () -> phone.showDialog(PhoneDialog.confirmDelete(
                    PhoneLang.tr("home.uninstall_title", name), PhoneLang.tr("home.uninstall_text"), () -> {
                        PhoneState.forget(appId);
                        dev.blockphone.fabric.client.store.AppStore.uninstall(id);
                    })));
        } else {
            menu.addDanger(PhoneLang.tr("home.hide"), () -> {
                PhoneSettings s = PhoneSettings.get();
                if (!s.homeHidden.contains(appId)) {
                    s.homeHidden.add(appId);
                }
                s.save();
            });
        }
    }

    private boolean folderClicked(double x, double y, int button) {
        if (!PhoneDraw.inside(x, y, folderX, folderY, folderW, folderH)) {
            closeFolder();
            return true;
        }
        if (folderName.isFocused() && folderName.mouseClicked(x, y, button)) {
            return true;
        }
        commitFolderName();
        if (y < folderY + 26) {
            if (button == 0) {
                startRename();
            }
            return true;
        }
        HomeLayout.Item item = folderItem();
        if (item == null) {
            return true;
        }
        for (Cell cell : folderCells) {
            if (PhoneDraw.inside(x, y, cell.iconX() - 3, cell.iconY() - 3, icon + 6, icon + 6) && cell.index() < item.apps().size()) {
                String app = item.apps().get(cell.index());
                String folderId = openFolder;
                if (button == 1) {
                    menu = new PopupMenu();
                    menu.add(PhoneLang.tr("home.remove_from_folder"), () -> removeFromFolder(folderId, app));
                    addRemoveItem(app);
                    menu.show((int) x, (int) y, contentWidth(), contentHeight());
                } else if (button == 0) {
                    openFolder = null;
                    phone.openApp(app);
                }
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        mouseX = x;
        mouseY = y;
        if (openFolder != null) {
            return folderName.isFocused() && folderName.mouseDragged(x, y);
        }
        if (drag == Drag.PRESSED && Math.hypot(x - pressX, y - pressY) > DRAG_START) {
            drag = editing && pressIndex >= 0 ? Drag.ICON : Drag.SWIPE;
            edgeSince = 0;
        }
        if (drag == Drag.ICON) {
            int dir = x < 8 ? -1 : x > contentWidth() - 8 ? 1 : 0;
            long now = System.currentTimeMillis();
            if (dir != edgeDir) {
                edgeDir = dir;
                edgeSince = now;
            } else if (dir != 0 && now - edgeSince > EDGE_FLIP_MS) {
                page = Math.max(0, Math.min(pageCount - 1, page + dir));
                edgeSince = now;
            }
        }
        return drag != Drag.NONE;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        mouseX = x;
        mouseY = y;
        if (openFolder != null) {
            return folderName.isFocused() && folderName.mouseReleased();
        }
        Drag was = drag;
        drag = Drag.NONE;
        switch (was) {
            case PRESSED -> {
                Cell cell = cellAt(x, y);
                if (cell != null && cell.index() == pressIndex && pressButton == 0) {
                    HomeLayout.Item item = items.get(cell.index());
                    if (item.folder()) {
                        openFolder(item.folderId());
                    } else if (!editing) {
                        phone.openApp(item.key());
                    }
                }
            }
            case SWIPE -> {
                int w = contentWidth();
                double moved = x - pressX;
                // Отпустили посреди свайпа: продолжаем плавно с того места, где страница сейчас.
                pagePos = pagePos - (float) (moved / w);
                if (moved < -w * 0.18 && page < pageCount - 1) {
                    page++;
                } else if (moved > w * 0.18 && page > 0) {
                    page--;
                }
            }
            case ICON -> {
                int target = dropTarget();
                if (target >= 0 && target != pressIndex) {
                    if (mergeTarget(target)) {
                        mergeInto(pressIndex, target);
                    } else {
                        moveItem(pressIndex, target > pressIndex ? target - 1 : target);
                    }
                } else if (target < 0) {
                    // Пустое место экрана — в конец этого экрана.
                    int end = Math.min(items.size() - 1, pageStart(page) + capacity(page) - 1);
                    moveItem(pressIndex, end);
                }
            }
            default -> {
            }
        }
        pressIndex = -1;
        return was != Drag.NONE;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (openFolder != null || drag != Drag.NONE) {
            return true;
        }
        if (amount < 0 && page < pageCount - 1) {
            page++;
        } else if (amount > 0 && page > 0) {
            page--;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (folderName.isFocused()) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                commitFolderName();
                return true;
            }
            return folderName.keyPressed(event);
        }
        if (openFolder == null && !editing) {
            if (event.key() == GLFW.GLFW_KEY_RIGHT && page < pageCount - 1) {
                page++;
                return true;
            }
            if (event.key() == GLFW.GLFW_KEY_LEFT && page > 0) {
                page--;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return folderName.isFocused() && folderName.charTyped(event);
    }
}
