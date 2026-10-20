package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.SocialData.ReportInfo;
import dev.blockphone.common.protocol.data.SocialData.ReportTarget;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.common.protocol.packet.SocialPackets.ResolveReport.Resolution;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.social.SocialFormat;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * Вкладка «Модерация» (право blockphone.moderate.reports): жалобы игроков. По каждой — открыть объект,
 * удалить, удалить и заглушить автора на сутки, отклонить. Действия пишутся в журнал на сервере.
 */
final class ModerationView extends SocialView {
    private static final int GAP = 4;

    private long lastRequest;

    ModerationView(SocialApp app) {
        super(app);
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
        SocialClient.requestReports();
    }

    @Override
    void tick() {
        long now = System.currentTimeMillis();
        if (SocialClient.reports() == null && now - lastRequest > 2000) {
            lastRequest = now;
            // Пришла новая жалоба — список сброшен, перезапросить.
            SocialClient.requestReports();
        }
    }

    private int excerptHeight(ReportInfo r, int w) {
        return r.excerpt().isEmpty() ? 0 : PhoneDraw.wrappedHeight(r.excerpt(), w - 20, 0.65f) + 3;
    }

    private int cardHeight(ReportInfo r, int w) {
        return 6 + 9 + 9 + excerptHeight(r, w) + 9 + 4 + 16 + 16 + 4;
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
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
        clampScroll(total, height());
        beginList(g, 0, height());
        int y = 4 - (int) scroll;
        for (ReportInfo r : reports) {
            int h = cardHeight(r, w - 8);
            if (y + h >= 0 && y <= height()) {
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
        hit(tx, ty, (int) PhoneDraw.width(target, 0.65f), 8, () -> app.openProfile(r.targetUser().uuid()));
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
}
