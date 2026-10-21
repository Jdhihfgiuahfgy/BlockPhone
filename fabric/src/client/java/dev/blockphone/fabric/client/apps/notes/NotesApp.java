package dev.blockphone.fabric.client.apps.notes;

import dev.blockphone.fabric.client.ui.Scrollbar;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneClock;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Заметки: список с поиском и редактор. Первая строка заметки — её заголовок. */
public final class NotesApp extends PhoneApp {
    private static final int MARGIN = 8;
    private static final int ROW_H = 30;
    private static final int SEARCH_H = 16;

    private final TextArea search = new TextArea(false, 0.75f);
    private final TextArea editor = new TextArea(true, 0.75f);
    private NotesStore.Note editing;
    private double scroll;

    public NotesApp() {
        super("notes");
        search.setMaxLength(64);
        search.setOnChange(s -> scroll = 0);
        editor.setMaxLength(20_000);
        editor.setOnChange(text -> {
            if (editing != null) {
                editing.text = text;
                editing.updated = System.currentTimeMillis();
            }
        });
    }

    @Override
    public String title() {
        return editing != null ? PhoneLang.tr("notes.note") : super.title();
    }

    @Override
    public String headerAction() {
        return editing != null ? PhoneLang.tr("notes.delete") : "+";
    }

    @Override
    public int headerActionColor() {
        return editing != null ? PhoneTheme.danger() : PhoneTheme.accent();
    }

    @Override
    public void onHeaderAction() {
        if (editing == null) {
            open(NotesStore.create());
            return;
        }
        if (editing.text.isBlank()) {
            closeEditor();
            return;
        }
        NotesStore.Note note = editing;
        phone.showDialog(PhoneDialog.confirmDelete(PhoneLang.tr("notes.delete_title"), PhoneLang.tr("notes.delete_message"), () -> {
            editing = null;
            NotesStore.delete(note);
        }));
    }

    private void open(NotesStore.Note note) {
        editing = note;
        editor.setText(note.text);
        editor.setFocused(true);
        editor.moveCursorToEnd();
    }

    private void closeEditor() {
        editing = null;
        editor.setFocused(false);
        NotesStore.save();
    }

    @Override
    public boolean onBack() {
        if (editing != null) {
            closeEditor();
            return true;
        }
        if (search.isFocused()) {
            search.setFocused(false);
            return true;
        }
        return false;
    }

    @Override
    public void onHidden() {
        editor.setFocused(false);
        search.setFocused(false);
        NotesStore.save();
    }

    @Override
    public boolean capturesKeyboard() {
        return editor.isFocused() || search.isFocused();
    }

    private List<NotesStore.Note> filtered() {
        String query = search.text().strip().toLowerCase(Locale.ROOT);
        List<NotesStore.Note> all = NotesStore.all();
        if (query.isEmpty()) {
            return all;
        }
        return all.stream().filter(n -> n.text.toLowerCase(Locale.ROOT).contains(query)).toList();
    }

    // --- Отрисовка ---

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        if (editing != null) {
            renderEditor(g, mouseX, mouseY);
        } else {
            renderList(g, mouseX, mouseY);
        }
    }

    private void renderEditor(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        PhoneDraw.centeredText(g, formatDate(editing.updated, true), w / 2f, 4, PhoneTheme.secondaryText(), 0.6f, false);
        editor.setBounds(MARGIN, 14, w - 2 * MARGIN, contentHeight() - 14 - MARGIN);
        editor.render(g, mouseX, mouseY);
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        int w = contentWidth();
        search.setBounds(MARGIN, 4, w - 2 * MARGIN, SEARCH_H);
        search.setPlaceholder(PhoneLang.tr("notes.search"));
        search.render(g, mouseX, mouseY);

        List<NotesStore.Note> notes = filtered();
        int top = 4 + SEARCH_H + 6;
        if (notes.isEmpty()) {
            String key = NotesStore.all().isEmpty() ? "notes.empty" : "notes.not_found";
            PhoneDraw.wrapped(g, PhoneLang.tr(key), MARGIN, top + 40, w - 2 * MARGIN, PhoneTheme.secondaryText(), 0.75f, true);
            return;
        }
        clampScroll(notes.size(), top);
        g.enableScissor(0, top, w, contentHeight());
        int listTop = top - (int) scroll;
        for (int i = 0; i < notes.size(); i++) {
            NotesStore.Note note = notes.get(i);
            int y = listTop + i * ROW_H;
            if (y + ROW_H < top || y > contentHeight()) {
                continue;
            }
            boolean hovered = mouseY >= top && PhoneDraw.inside(mouseX, mouseY, MARGIN, y, w - 2 * MARGIN, ROW_H - 3);
            PhoneDraw.card(g, MARGIN, y, w - MARGIN, y + ROW_H - 3, hovered);
            String title = note.title().isEmpty() ? PhoneLang.tr("notes.untitled") : note.title();
            PhoneDraw.text(g, PhoneDraw.ellipsize(title, w - 2 * MARGIN - 12, 0.85f), MARGIN + 6, y + 5,
                    PhoneTheme.text(), 0.85f, false);
            String date = formatDate(note.updated, false);
            PhoneDraw.text(g, date, MARGIN + 6, y + 17, PhoneTheme.accent(), 0.6f, false);
            float previewX = MARGIN + 6 + PhoneDraw.width(date, 0.6f) + 5;
            PhoneDraw.text(g, PhoneDraw.ellipsize(note.preview(), w - MARGIN - 6 - previewX, 0.6f), previewX, y + 17,
                    PhoneTheme.secondaryText(), 0.6f, false);
        }
        g.disableScissor();
    }

    private void clampScroll(int count, int top) {
        double max = Math.max(0, count * ROW_H - (contentHeight() - top) + 4);
        scroll = Math.max(0, Math.min(max, scroll));
        Scrollbar.report(top, contentHeight(), scroll, max, false, v -> scroll = v);
    }

    private static String formatDate(long millis, boolean full) {
        ZonedDateTime time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault());
        Locale locale = PhoneClock.locale();
        if (full) {
            return time.format(DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", locale));
        }
        if (time.toLocalDate().equals(LocalDate.now())) {
            return time.format(DateTimeFormatter.ofPattern("HH:mm", locale));
        }
        return time.format(DateTimeFormatter.ofPattern("dd.MM.yy", locale));
    }

    // --- Ввод ---

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (editing != null) {
            return editor.mouseClicked(x, y, button);
        }
        if (search.mouseClicked(x, y, button)) {
            return true;
        }
        int top = 4 + SEARCH_H + 6;
        if (y < top) {
            return false;
        }
        int index = (int) Math.floor((y - top + scroll) / ROW_H);
        List<NotesStore.Note> notes = filtered();
        if (button == 0 && x >= MARGIN && x < contentWidth() - MARGIN && index >= 0 && index < notes.size()) {
            open(notes.get(index));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return editing != null ? editor.mouseDragged(x, y) : search.mouseDragged(x, y);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return editing != null ? editor.mouseReleased() : search.mouseReleased();
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        if (editing != null) {
            return editor.mouseScrolled(x, y, amount);
        }
        scroll -= amount * 16;
        clampScroll(filtered().size(), 4 + SEARCH_H + 6);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return editing != null ? editor.keyPressed(event) : search.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return editing != null ? editor.charTyped(event) : search.charTyped(event);
    }
}
