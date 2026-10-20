package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.SocialData.PublicInfo;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.packet.SocialPackets.RequestPublics.Kind;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.List;

/**
 * Вкладка «Паблики»: поиск игроков и пабликов; без запроса — «Мои паблики», «Подписки», «Популярные»
 * и кнопка «Создать паблик».
 */
final class PublicsView extends SocialView {
    private static final int SEARCH_H = 16;
    private static final int LIST_TOP = SEARCH_H + 8;

    private final TextArea search = new TextArea(false, 0.75f);

    PublicsView(SocialApp app) {
        super(app);
        search.setMaxLength(32);
        search.setOnChange(text -> {
            scroll = 0;
            if (!text.isBlank()) {
                SocialClient.search(text.strip());
            }
        });
    }

    @Override
    boolean root() {
        return true;
    }

    @Override
    String title() {
        return tr("social.tab.publics");
    }

    @Override
    void onShown() {
        for (Kind kind : Kind.values()) {
            SocialClient.requestPublics(kind);
        }
        if (!search.text().isBlank()) {
            SocialClient.search(search.text().strip());
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
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        search.setBounds(6, 4, w - 12, SEARCH_H);
        search.setPlaceholder(tr("social.search_placeholder"));
        search.render(g, mouseX, mouseY);

        int listMouseY = mouseY >= LIST_TOP ? mouseY : -1000;
        beginList(g, LIST_TOP, height());
        int y = LIST_TOP - (int) scroll;
        int start = y;
        if (search.text().isBlank()) {
            if (ClientSession.has(Rights.CREATE_PUBLIC)) {
                button(g, 6, y, w - 12, 16, "+  " + tr("social.create_public"), mouseX, listMouseY, PhoneDraw.ButtonStyle.NORMAL,
                        () -> app.push(new CreatePublicView(app)));
                y += 22;
            }
            y = list(g, Kind.MANAGED, "social.my_publics", y, w, mouseX, listMouseY, false);
            y = list(g, Kind.SUBSCRIBED, "social.subscriptions", y, w, mouseX, listMouseY, false);
            y = list(g, Kind.POPULAR, "social.popular", y, w, mouseX, listMouseY, true);
        } else if (!SocialClient.searchLoaded()) {
            PhoneDraw.text(g, tr("messenger.loading"), 8, y + 4, PhoneTheme.secondaryText(), 0.7f, false);
            y += 16;
        } else {
            List<UserInfo> users = SocialClient.searchUsers();
            List<PublicInfo> publics = SocialClient.searchPublics();
            if (users.isEmpty() && publics.isEmpty()) {
                PhoneDraw.text(g, tr("social.nothing_found"), 8, y + 4, PhoneTheme.secondaryText(), 0.7f, false);
                y += 16;
            }
            if (!publics.isEmpty()) {
                section(g, tr("social.publics_found"), y + 2);
                y += 12;
                for (PublicInfo info : publics) {
                    Rows.pub(this, g, info, y, w, mouseX, listMouseY);
                    y += Rows.H;
                }
            }
            if (!users.isEmpty()) {
                section(g, tr("social.people_found"), y + 2);
                y += 12;
                for (UserInfo user : users) {
                    Rows.user(this, g, user, y, w, mouseX, listMouseY, null, () -> app.openProfile(user.uuid()));
                    y += Rows.H;
                }
            }
        }
        endList(g);
        clampScroll(y - start + 8, height() - LIST_TOP);
    }

    private int list(GuiGraphics g, Kind kind, String titleKey, int y, int w, int mx, int my, boolean showEmpty) {
        List<PublicInfo> list = SocialClient.publics(kind);
        if (list == null || (list.isEmpty() && !showEmpty)) {
            return y;
        }
        section(g, tr(titleKey), y + 2);
        y += 12;
        if (list.isEmpty()) {
            PhoneDraw.text(g, tr("social.no_publics"), 8, y + 2, PhoneTheme.secondaryText(), 0.7f, false);
            return y + 16;
        }
        for (PublicInfo info : list) {
            Rows.pub(this, g, info, y, w, mx, my);
            y += Rows.H;
        }
        return y + 4;
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (search.mouseClicked(x, y, button)) {
            return true;
        }
        search.setFocused(false);
        return super.mouseClicked(x, y, button);
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        return search.mouseDragged(x, y);
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        return search.mouseReleased();
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        return search.isFocused() && search.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return search.isFocused() && search.charTyped(event);
    }

    @Override
    boolean onBack() {
        if (!search.text().isEmpty()) {
            search.setText("");
            return true;
        }
        return super.onBack();
    }
}
