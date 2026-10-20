package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.PrivacyData.Kind;
import dev.blockphone.common.protocol.data.PrivacyData.Privacy;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.PrivacyClient;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.Locale;

/**
 * Приватность: кто может писать в личку, слать голосовые и фото, приглашать в группы и звонить
 * (все / на кого я подписан / взаимные / никто), списки «всегда можно» и «заблокированы».
 * ЛКМ по строке — следующее значение, ПКМ — предыдущее.
 */
final class PrivacyView extends SocialView {
    private static final int ROW = 20;

    PrivacyView(SocialApp app) {
        super(app);
    }

    @Override
    String title() {
        return tr("privacy.title");
    }

    @Override
    void onShown() {
        PrivacyClient.request();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        Privacy p = PrivacyClient.privacy();
        if (p == null) {
            hint(g, tr("messenger.loading"), height() / 2 - 10);
            return;
        }
        int w = width();
        List<UserInfo> allowed = PrivacyClient.allowed();
        List<UserInfo> blocked = PrivacyClient.blocked();
        int total = 14 + Kind.values().length * ROW + 30 + 14 + 22 + allowed.size() * 18 + 14 + 22 + blocked.size() * 18 + 20;
        clampScroll(total, height());
        beginList(g, 0, height());
        int y = 4 - (int) scroll;
        section(g, tr("privacy.who_can"), y);
        y += 14;
        PhoneDraw.card(g, 6, y, w - 6, y + Kind.values().length * ROW, false);
        for (int i = 0; i < Kind.values().length; i++) {
            Kind kind = Kind.values()[i];
            int ry = y + i * ROW;
            boolean hover = PhoneDraw.inside(mouseX, mouseY, 6, ry, w - 12, ROW);
            if (hover) {
                g.fill(7, ry + 1, w - 7, ry + ROW - 1, PhoneTheme.cardHover());
            }
            if (i > 0) {
                g.fill(12, ry, w - 12, ry + 1, PhoneTheme.separator());
            }
            PhoneDraw.text(g, tr("privacy.kind." + kind.name().toLowerCase(Locale.ROOT)), 12, ry + 6.5f, PhoneTheme.text(), 0.72f, false);
            PhoneDraw.rightText(g, tr("privacy.audience." + p.get(kind).name().toLowerCase(Locale.ROOT)), w - 12, ry + 6.5f,
                    PhoneTheme.accent(), 0.72f);
            hitDir(6, ry, w - 12, ROW, dir -> PrivacyClient.cycle(kind, dir));
        }
        y += Kind.values().length * ROW + 4;
        PhoneDraw.wrapped(g, tr("privacy.hint"), 8, y, w - 16, PhoneTheme.secondaryText(), 0.55f, false);
        y += 26;

        y = list(g, tr("privacy.allowed"), allowed, false, y, w, mouseX, mouseY);
        list(g, tr("privacy.blocked"), blocked, true, y, w, mouseX, mouseY);
        endList(g);
    }

    private int list(GuiGraphics g, String title, List<UserInfo> users, boolean blockList, int y, int w, int mx, int my) {
        section(g, title + " · " + users.size(), y);
        y += 14;
        button(g, 6, y, w - 12, 16, tr("privacy.add"), mx, my, PhoneDraw.ButtonStyle.NORMAL,
                () -> app.push(new UserPickView(app, title, user -> PrivacyClient.list(user.uuid(), blockList, true))));
        y += 22;
        for (UserInfo user : users) {
            Avatars.drawUser(g, user, 10, y + 2, 12);
            PhoneDraw.text(g, PhoneDraw.ellipsize(user.name(), w - 60, 0.72f), 28, y + 5, PhoneTheme.text(), 0.72f, false);
            boolean hover = PhoneDraw.inside(mx, my, w - 24, y, 18, 16);
            PhoneDraw.centeredText(g, "×", w - 15, y + 3, hover ? PhoneTheme.danger() : PhoneTheme.secondaryText(), 1f, false);
            hit(w - 24, y, 18, 16, () -> PrivacyClient.list(user.uuid(), blockList, false));
            y += 18;
        }
        return y + 4;
    }
}
