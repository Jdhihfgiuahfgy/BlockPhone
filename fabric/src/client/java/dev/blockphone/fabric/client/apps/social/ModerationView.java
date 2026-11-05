package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.ModerationData;
import dev.blockphone.common.protocol.data.ModerationData.LogEntry;
import dev.blockphone.common.protocol.data.ModerationData.PlayerStatus;
import dev.blockphone.common.protocol.data.SocialData.ReportInfo;
import dev.blockphone.common.protocol.data.SocialData.ReportTarget;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.common.protocol.packet.SocialPackets.ResolveReport.Resolution;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.ModerationClient;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.social.SocialFormat;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.List;
import java.util.Locale;

/**
 * Панель модерации: вкладки «Жалобы» (право blockphone.moderate.reports: открыть, удалить, удалить и заглушить, отклонить),
 * «Игроки» (поиск → карточка игрока: мут, бан, снятие, его чаты), «Наказанные» (мут или бан сейчас) и «Журнал»
 * (действия модераторов). Все действия пишутся в журнал на сервере.
 */
final class ModerationView extends SocialView {
    private static final int GAP = 4;
    private static final int SEG_H = 16;
    private static final int TOP = SEG_H + 4;
    private static final int ROW_H = 26;

    enum Section { REPORTS, PLAYERS, PUNISHED, LOG }

    private Section section;
    private long lastRequest;
    private final TextArea search = new TextArea(false, 0.75f);

    ModerationView(SocialApp app) {
        super(app);
        section = ClientSession.has(Rights.MOD_REPORTS) ? Section.REPORTS : Section.PLAYERS;
        search.setMaxLength(16);
        search.setOnChange(text -> ModerationClient.search(text.strip()));
    }

    @Override
    boolean root() {
        return true;
    }

    @Override
    String title() {
        return tr("social.tab.moderation");
    }

    @Override
    void onShown() {
        refresh();
    }

    private void refresh() {
        switch (section) {
            case REPORTS -> SocialClient.requestReports();
            case PLAYERS -> ModerationClient.search(search.text().strip());
            case PUNISHED -> ModerationClient.requestPunished();
            case LOG -> ModerationClient.requestLog(false);
        }
    }

    @Override
    void onHidden() {
        search.setFocused(false);
    }

    @Override
    boolean capturesKeyboard() {
        return search.isFocused();
    }

    @Override
    void tick() {
        long now = System.currentTimeMillis();
        if (section == Section.REPORTS && SocialClient.reports() == null && now - lastRequest > 2000) {
            lastRequest = now;
            // Пришла новая жалоба — список сброшен, перезапросить.
            SocialClient.requestReports();
        }
        if (section == Section.PUNISHED && ModerationClient.punished() == null && now - lastRequest > 2000) {
            lastRequest = now;
            ModerationClient.requestPunished();
        }
    }

    private List<Section> sections() {
        java.util.ArrayList<Section> list = new java.util.ArrayList<>();
        if (ClientSession.has(Rights.MOD_REPORTS)) {
            list.add(Section.REPORTS);
        }
        list.add(Section.PLAYERS);
        list.add(Section.PUNISHED);
        list.add(Section.LOG);
        return list;
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        // Переключатель разделов.
        List<Section> list = sections();
        int sw = (w - 8) / list.size();
        for (int i = 0; i < list.size(); i++) {
            Section s = list.get(i);
            int x = 4 + i * sw;
            boolean on = s == section;
            PhoneDraw.button(g, x, 2, sw - 2, SEG_H - 2, tr("moderation.section." + s.name().toLowerCase(Locale.ROOT)), mouseX, mouseY,
                    on ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL);
            if (s == Section.REPORTS && ClientSession.openReports() > 0) {
                PhoneDraw.roundedRect(g, x + sw - 9, 1, x + sw - 2, 8, 3, PhoneTheme.danger());
            }
            hit(x, 2, sw - 2, SEG_H - 2, () -> {
                section = s;
                scroll = 0;
                refresh();
            });
        }
        switch (section) {
            case REPORTS -> renderReports(g, mouseX, mouseY);
            case PLAYERS -> renderPlayers(g, mouseX, mouseY);
            case PUNISHED -> renderPlayerList(g, ModerationClient.punished(), TOP, mouseX, mouseY, tr("moderation.none_punished"));
            case LOG -> renderLog(g, mouseX, mouseY);
        }
    }

    // --- Игроки ---

    private void renderPlayers(GuiGraphics g, int mx, int my) {
        int w = width();
        search.setBounds(6, TOP, w - 12, 16);
        search.setPlaceholder(tr("moderation.search"));
        search.render(g, mx, my);
        renderPlayerList(g, ModerationClient.searchResults(), TOP + 20, mx, my, tr("moderation.nobody"));
    }

    private void renderPlayerList(GuiGraphics g, List<PlayerStatus> players, int top, int mx, int my, String empty) {
        int w = width();
        if (players == null) {
            hint(g, tr("messenger.loading"), top + 20);
            return;
        }
        if (players.isEmpty()) {
            hint(g, empty, top + 20);
            return;
        }
        int total = players.size() * ROW_H + 4;
        clampScroll(total, height() - top);
        beginList(g, top, height());
        int y = top + 2 - (int) scroll;
        for (PlayerStatus s : players) {
            if (y + ROW_H >= top && y <= height()) {
                playerRow(g, s, 4, y, w - 8, mx, my);
            }
            y += ROW_H;
        }
        endList(g);
    }

    /** Строка игрока: голова, ник, состояние (в сети / мут / бан). */
    static void playerRow(GuiGraphics g, PlayerStatus s, int x, int y, int w, int mx, int my, SocialView view, SocialApp app) {
        boolean hover = PhoneDraw.inside(mx, my, x, y, w, ROW_H - 2);
        PhoneDraw.card(g, x, y, x + w, y + ROW_H - 2, hover);
        Avatars.drawUser(g, s.user(), x + 4, y + 3, 18);
        PhoneDraw.text(g, PhoneDraw.ellipsize(s.user().name(), w - 30, 0.75f), x + 26, y + 4, PhoneTheme.text(), 0.75f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(statusLine(s), w - 30, 0.55f), x + 26, y + 14,
                s.bannedUntil() > 0 || s.mutedUntil() > 0 ? PhoneTheme.danger() : PhoneTheme.secondaryText(), 0.55f, false);
        view.hit(x, y, w, ROW_H - 2, () -> app.push(new PlayerModView(app, s.user().uuid(), s.user().name())));
    }

    private void playerRow(GuiGraphics g, PlayerStatus s, int x, int y, int w, int mx, int my) {
        playerRow(g, s, x, y, w, mx, my, this, app);
    }

    static String statusLine(PlayerStatus s) {
        StringBuilder sb = new StringBuilder();
        if (s.bannedUntil() > 0) {
            sb.append(tr("moderation.banned_until", until(s.bannedUntil())));
        }
        if (s.mutedUntil() > 0) {
            if (!sb.isEmpty()) {
                sb.append(" · ");
            }
            sb.append(tr("moderation.muted_until", until(s.mutedUntil())));
        }
        if (sb.isEmpty()) {
            sb.append(s.user().online() ? tr("messenger.online") : tr("moderation.last_seen", SocialFormat.ago(s.lastSeen())));
        }
        return sb.toString();
    }

    static String until(long time) {
        return time == ModerationData.FOREVER ? tr("social.forever") : SocialFormat.dateTime(time);
    }

    // --- Журнал ---

    private void renderLog(GuiGraphics g, int mx, int my) {
        int w = width();
        List<LogEntry> log = ModerationClient.log();
        if (log == null) {
            hint(g, tr("messenger.loading"), TOP + 20);
            return;
        }
        if (log.isEmpty()) {
            hint(g, tr("moderation.log_empty"), TOP + 20);
            return;
        }
        int total = 4;
        for (LogEntry e : log) {
            total += logHeight(e, w - 8) + 3;
        }
        boolean more = ModerationClient.logHasMore();
        total += more ? 20 : 0;
        clampScroll(total, height() - TOP);
        beginList(g, TOP, height());
        int y = TOP + 2 - (int) scroll;
        for (LogEntry e : log) {
            int h = logHeight(e, w - 8);
            if (y + h >= TOP && y <= height()) {
                PhoneDraw.card(g, 4, y, w - 4, y + h, false);
                PhoneDraw.text(g, PhoneDraw.ellipsize(e.moderator() + " → " + e.target(), w - 60, 0.65f), 9, y + 4, PhoneTheme.text(),
                        0.65f, false);
                PhoneDraw.rightText(g, SocialFormat.ago(e.time()), w - 9, y + 4, PhoneTheme.secondaryText(), 0.5f);
                String action = actionLabel(e.action()) + (e.details().isEmpty() ? "" : ": " + e.details());
                PhoneDraw.wrapped(g, action, 9, y + 14, w - 26, PhoneTheme.secondaryText(), 0.6f, false);
            }
            y += h + 3;
        }
        if (more) {
            button(g, 4, y, w - 8, 16, tr("moderation.more"), mx, my, PhoneDraw.ButtonStyle.NORMAL, () -> ModerationClient.requestLog(true));
        }
        endList(g);
    }

    private static int logHeight(LogEntry e, int w) {
        String action = actionLabel(e.action()) + (e.details().isEmpty() ? "" : ": " + e.details());
        return 16 + PhoneDraw.wrappedHeight(action, w - 18, 0.6f) + 2;
    }

    private static String actionLabel(String action) {
        String key = "moderation.action." + action;
        String text = tr(key);
        return text.equals(key) ? action : text;
    }

    // --- Жалобы ---

    private int excerptHeight(ReportInfo r, int w) {
        return r.excerpt().isEmpty() ? 0 : PhoneDraw.wrappedHeight(r.excerpt(), w - 20, 0.65f) + 3;
    }

    private int cardHeight(ReportInfo r, int w) {
        return 6 + 9 + 9 + excerptHeight(r, w) + 9 + 4 + 16 + 16 + 4;
    }

    private void renderReports(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        List<ReportInfo> reports = SocialClient.reports();
        if (reports == null) {
            hint(g, tr("messenger.loading"), height() / 2 - 10);
            return;
        }
        if (reports.isEmpty()) {
            hint(g, tr("social.no_reports"), height() / 2 - 10);
            return;
        }
        int total = 4;
        for (ReportInfo r : reports) {
            total += cardHeight(r, w - 8) + GAP;
        }
        clampScroll(total, height() - TOP);
        beginList(g, TOP, height());
        int y = TOP + 2 - (int) scroll;
        for (ReportInfo r : reports) {
            int h = cardHeight(r, w - 8);
            if (y + h >= TOP && y <= height()) {
                renderReport(g, r, 4, y, w - 8, mouseX, mouseY);
            }
            y += h + GAP;
        }
        endList(g);
    }

    private void renderReport(GuiGraphics g, ReportInfo r, int x, int y, int w, int mx, int my) {
        int h = cardHeight(r, w);
        PhoneDraw.panel(g, x, y, x + w, y + h, PhoneTheme.card());
        int tx = x + 6;
        int ty = y + 6;
        String type = tr("social.report_type." + r.type().name().toLowerCase());
        PhoneDraw.text(g, type, tx, ty, PhoneTheme.danger(), 0.7f, false);
        PhoneDraw.rightText(g, SocialFormat.ago(r.createdAt()), x + w - 6, ty, PhoneTheme.secondaryText(), 0.55f);
        ty += 9;
        String target = tr("social.report_author", r.targetUser().name());
        PhoneDraw.text(g, PhoneDraw.ellipsize(target, w - 12, 0.65f), tx, ty, PhoneTheme.text(), 0.65f, false);
        hit(tx, ty, (int) PhoneDraw.width(target, 0.65f), 8,
                () -> app.push(new PlayerModView(app, r.targetUser().uuid(), r.targetUser().name())));
        ty += 9;
        if (!r.excerpt().isEmpty()) {
            g.fill(tx, ty, tx + 1, ty + excerptHeight(r, w) - 3, PhoneTheme.border());
            PhoneDraw.wrapped(g, r.excerpt(), tx + 4, ty, w - 20, PhoneTheme.secondaryText(), 0.65f, false);
            ty += excerptHeight(r, w);
        }
        String reason = tr("social.report_reason", tr("social.reason." + r.reason()), r.reporter().name());
        PhoneDraw.text(g, PhoneDraw.ellipsize(reason, w - 12, 0.6f), tx, ty, PhoneTheme.secondaryText(), 0.6f, false);
        ty += 13;

        // Ряд 1: открыть / отклонить; ряд 2: удалить / удалить и заглушить на сутки.
        int bw = (w - 12 - 4) / 2;
        button(g, tx, ty, bw, 14, tr("social.open"), mx, my, PhoneDraw.ButtonStyle.NORMAL, () -> open(r));
        button(g, tx + bw + 4, ty, bw, 14, tr("social.dismiss"), mx, my, PhoneDraw.ButtonStyle.NORMAL,
                () -> resolve(r, Resolution.DISMISS));
        ty += 16;
        boolean canDelete = r.type() != ReportTarget.USER
                && ClientSession.has(r.type() == ReportTarget.PUBLIC ? Rights.MOD_PUBLICS : Rights.MOD_POSTS);
        boolean canMute = ClientSession.has(Rights.MOD_MUTE);
        if (canDelete) {
            button(g, tx, ty, canMute ? bw : w - 12, 14, tr("dialog.delete"), mx, my, PhoneDraw.ButtonStyle.DANGER,
                    () -> resolve(r, Resolution.DELETE));
        }
        if (canMute && (canDelete || r.type() == ReportTarget.USER)) {
            int x0 = canDelete ? tx + bw + 4 : tx;
            button(g, x0, ty, canDelete ? bw : w - 12, 14, tr(canDelete ? "social.delete_mute" : "social.mute_1d"), mx, my,
                    PhoneDraw.ButtonStyle.DANGER, () -> resolve(r, Resolution.DELETE_AND_MUTE));
        }
    }

    private void open(ReportInfo r) {
        switch (r.type()) {
            case POST -> app.openPost(r.targetId());
            case PUBLIC -> app.openPublic(r.targetId());
            default -> app.openProfile(r.targetUser().uuid());
        }
    }

    private void resolve(ReportInfo r, Resolution resolution) {
        SocialClient.send(new SocialPackets.ResolveReport(r.id(), resolution));
    }

    // --- Ввод ---

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (section == Section.PLAYERS && search.mouseClicked(x, y, button)) {
            return true;
        }
        search.setFocused(false);
        return super.mouseClicked(x, y, button);
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        return section == Section.PLAYERS && search.mouseDragged(x, y);
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        return section == Section.PLAYERS && search.mouseReleased();
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        return search.isFocused() && search.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return search.isFocused() && search.charTyped(event);
    }
}
