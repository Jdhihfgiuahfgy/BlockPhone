package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.fabric.client.social.SocialClient;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.UUID;

/** Подписчики или подписки игрока. */
final class UserListView extends SocialView {
    private final UUID user;
    private final boolean followers;
    private final String name;

    UserListView(SocialApp app, UUID user, boolean followers, String name) {
        super(app);
        this.user = user;
        this.followers = followers;
        this.name = name;
    }

    @Override
    String title() {
        return tr(followers ? "social.followers_of" : "social.following_of", name);
    }

    @Override
    void onShown() {
        SocialClient.requestFollows(user, followers);
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        List<UserInfo> users = SocialClient.userList();
        if (users == null) {
            hint(g, tr("messenger.loading"), height() / 2 - 10);
            return;
        }
        if (users.isEmpty()) {
            hint(g, tr(followers ? "social.no_followers" : "social.no_following"), height() / 2 - 10);
            return;
        }
        clampScroll(users.size() * Rows.H + 4, height());
        beginList(g, 0, height());
        int y = 2 - (int) scroll;
        for (UserInfo u : users) {
            if (y + Rows.H >= 0 && y <= height()) {
                Rows.user(this, g, u, y, width(), mouseX, mouseY, null, () -> app.openProfile(u.uuid()));
            }
            y += Rows.H;
        }
        endList(g);
    }
}
