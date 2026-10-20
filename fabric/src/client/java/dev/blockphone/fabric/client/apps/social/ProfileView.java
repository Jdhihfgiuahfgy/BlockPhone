package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.SocialData;
import dev.blockphone.common.protocol.data.SocialData.FeedKind;
import dev.blockphone.common.protocol.data.SocialData.PostInfo;
import dev.blockphone.common.protocol.data.SocialData.ProfileInfo;
import dev.blockphone.common.protocol.data.SocialData.ReportTarget;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.ProfileLooks;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.social.SocialFormat;
import dev.blockphone.fabric.client.ui.PopupMenu;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.List;
import java.util.UUID;

/**
 * Профиль игрока: аватар, ник, «о себе», посты/подписчики/подписки, «Подписаться», «Написать», меню
 * (жалоба; модератору — заглушить). Свой профиль — вкладка «Профиль»: «Изменить», «Новый пост».
 */
final class ProfileView extends SocialView {
    private static final int AVATAR = 44;
    private static final int BANNER = 52;
    private static final int GAP = 4;

    private final UUID uuid;
    private final boolean rootTab;
    private final TextArea bioInput = new TextArea(true, 0.75f);
    private boolean editing;

    ProfileView(SocialApp app, UUID uuid, boolean rootTab) {
        super(app);
        this.uuid = uuid;
        this.rootTab = rootTab;
        bioInput.setMaxLength(SocialData.MAX_BIO_LENGTH);
    }

    @Override
    boolean root() {
        return rootTab;
    }

    private boolean self() {
        return uuid != null && SocialClient.isSelf(uuid);
    }

    @Override
    String title() {
        ProfileInfo profile = uuid != null ? SocialClient.profile(uuid) : null;
        return rootTab ? tr("social.tab.profile") : profile != null ? profile.user().name() : tr("social.profile");
    }

    @Override
    String headerAction() {
        return self() && ClientSession.has(Rights.POST) ? "+" : null;
    }

    @Override
    void onHeaderAction() {
        app.openCompose(0);
    }

    @Override
    void onShown() {
        if (uuid == null) {
            return;
        }
        SocialClient.requestProfile(uuid);
        SocialClient.Feed feed = SocialClient.feed(FeedKind.USER, uuid, 0);
        if (feed.loaded && !feed.loading) {
            SocialClient.refresh(feed);
        }
    }

    @Override
    void onHidden() {
        bioInput.setFocused(false);
    }

    @Override
    boolean capturesKeyboard() {
        return editing && bioInput.isFocused();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        ProfileInfo profile = uuid != null ? SocialClient.profile(uuid) : null;
        if (profile == null) {
            hint(g, tr("messenger.loading"), height() / 2 - 10);
            return;
        }
        SocialClient.Feed feed = SocialClient.feed(FeedKind.USER, uuid, 0);
        List<PostInfo> posts = SocialClient.posts(feed);
        int headerH = headerHeight(profile, w);
        int total = headerH;
        for (PostInfo post : posts) {
            total += PostCard.height(post, w - 8, false) + GAP;
        }
        total += 20;
        clampScroll(total, height());
        if (feed.hasMore && scroll >= total - height() - 60) {
            SocialClient.loadMore(feed);
        }

        beginList(g, 0, height());
        int y = -(int) scroll;
        renderHeader(g, profile, y, w, mouseX, mouseY);
        y += headerH;
        if (feed.loaded && posts.isEmpty()) {
            PhoneDraw.centeredText(g, tr("social.no_posts"), w / 2f, y + 6, PhoneTheme.secondaryText(), 0.7f, false);
        }
        for (PostInfo post : posts) {
            int h = PostCard.height(post, w - 8, false);
            if (y + h >= 0 && y <= height()) {
                PostCard.draw(this, g, post, 4, y, w - 8, mouseX, mouseY, false);
            }
            y += h + GAP;
        }
        endList(g);
    }

    private int bioHeight(ProfileInfo profile, int w) {
        if (editing) {
            return 50;
        }
        String bio = profile.bio().isEmpty() ? tr(self() ? "social.bio_empty_self" : "social.bio_empty") : profile.bio();
        return PhoneDraw.wrappedHeight(bio, w - 16, 0.7f);
    }

    private int headerHeight(ProfileInfo profile, int w) {
        int h = BANNER + 34 + bioHeight(profile, w) + 6 + 24 + 6 + 20 + (self() ? 20 : 0);
        if (profile.mutedUntil() > 0) {
            h += 12;
        }
        return h + 14;
    }

    private void renderHeader(GuiGraphics g, ProfileInfo profile, int top, int w, int mx, int my) {
        UserInfo user = profile.user();
        // Баннер и аватар из оформления страницы.
        ProfileLooks.drawBanner(g, profile.look(), 0, top, w, BANNER);
        int ay = top + BANNER - AVATAR / 2 - 4;
        PhoneDraw.rawRoundedRect(g, 6, ay - 2, 6 + AVATAR + 4, ay + AVATAR + 2, 9, PhoneTheme.background());
        ProfileLooks.drawAvatar(g, user, profile.look(), 8, ay, AVATAR);
        boolean online = MessengerClient.isOnline(user);
        if (online) {
            Avatars.drawOnlineDot(g, 8, ay, AVATAR, PhoneTheme.background());
        }
        int tx = 8 + AVATAR + 8;
        int y = top + BANNER + 3;
        PhoneDraw.text(g, PhoneDraw.ellipsize(user.name(), w - tx - 6, 1f), tx, y, PhoneTheme.text(), 1f, false);
        String status = online ? tr("messenger.online")
                : profile.lastSeen() > 0 ? tr("social.last_seen", SocialFormat.ago(profile.lastSeen())) : tr("messenger.offline");
        PhoneDraw.text(g, status, tx, y + 11, online ? PhoneTheme.accent() : PhoneTheme.secondaryText(), 0.65f, false);
        if (profile.followsYou() && !self()) {
            PhoneDraw.text(g, tr("social.follows_you"), tx, y + 19, PhoneTheme.secondaryText(), 0.55f, false);
        }
        if (!self()) {
            int mx0 = w - 18;
            int my0 = top + 4;
            boolean hover = PhoneDraw.inside(mx, my, mx0 - 2, my0, 16, 14);
            PhoneDraw.rawRoundedRect(g, mx0 - 2, my0, mx0 + 14, my0 + 14, 4, hover ? 0xA0000000 : 0x60000000);
            PhoneDraw.tinted(g, PostCard.MORE, mx0, my0 + 1, 12, 12, 32, 32, 0xFFFFFFFF);
            int menuY = my0 + 14;
            hit(mx0 - 2, my0, 16, 14, () -> showMenu(profileMenu(profile), mx0, menuY));
        }
        y = top + BANNER + 34;

        // О себе.
        if (editing) {
            bioInput.setBounds(8, y, w - 16, 32);
            bioInput.setPlaceholder(tr("social.bio_placeholder"));
            bioInput.render(g, mx, my);
            button(g, w - 70, y + 35, 62, 13, tr("messenger.save"), mx, my, PhoneDraw.ButtonStyle.PRIMARY, () -> {
                SocialClient.send(new SocialPackets.UpdateBio(bioInput.text().strip()));
                editing = false;
            });
            button(g, w - 134, y + 35, 60, 13, tr("dialog.cancel"), mx, my, PhoneDraw.ButtonStyle.NORMAL, () -> editing = false);
        } else {
            boolean empty = profile.bio().isEmpty();
            String bio = empty ? tr(self() ? "social.bio_empty_self" : "social.bio_empty") : profile.bio();
            PhoneDraw.wrapped(g, bio, 8, y, w - 16, empty ? PhoneTheme.secondaryText() : PhoneTheme.text(), 0.7f, false);
        }
        y += bioHeight(profile, w) + 6;

        // Счётчики.
        int cw = (w - 16) / 3;
        counter(g, 8, y, cw, profile.posts(), tr("social.posts_count"), mx, my, null);
        counter(g, 8 + cw, y, cw, profile.followers(), tr("social.followers"), mx, my,
                () -> app.push(new UserListView(app, uuid, true, user.name())));
        counter(g, 8 + cw * 2, y, cw, profile.following(), tr("social.following_count"), mx, my,
                () -> app.push(new UserListView(app, uuid, false, user.name())));
        y += 24 + 6;

        // Кнопки.
        if (self()) {
            int half = (w - 20) / 2;
            button(g, 8, y, half, 16, tr("social.edit_bio"), mx, my, PhoneDraw.ButtonStyle.NORMAL, () -> {
                editing = true;
                bioInput.setText(profile.bio());
                bioInput.setFocused(true);
            });
            button(g, 12 + half, y, half, 16, tr("profile.look"), mx, my, PhoneDraw.ButtonStyle.PRIMARY,
                    () -> app.push(new ProfileLookView(app, uuid)));
            y += 20;
            button(g, 8, y, w - 16, 16, tr("privacy.title"), mx, my, PhoneDraw.ButtonStyle.NORMAL,
                    () -> app.push(new PrivacyView(app)));
        } else {
            boolean messenger = ClientNetwork.messengerAvailable();
            int bw = messenger ? (w - 20) / 2 : w - 16;
            button(g, 8, y, bw, 16, tr(profile.followed() ? "social.unfollow" : "social.follow"), mx, my,
                    profile.followed() ? PhoneDraw.ButtonStyle.NORMAL : PhoneDraw.ButtonStyle.PRIMARY,
                    () -> SocialClient.send(new SocialPackets.Follow(uuid, !profile.followed())));
            if (messenger) {
                if (profile.canMessage()) {
                    button(g, 12 + bw, y, bw, 16, tr("social.message"), mx, my, PhoneDraw.ButtonStyle.NORMAL,
                            () -> app.openMessenger(uuid));
                } else {
                    PhoneDraw.card(g, 12 + bw, y, 12 + 2 * bw, y + 16, false);
                    PhoneDraw.centeredText(g, PhoneDraw.ellipsize(tr("privacy.closed"), bw - 4, 0.65f), 12 + bw + bw / 2f, y + 5,
                            PhoneTheme.secondaryText(), 0.65f, false);
                }
            }
        }
        y += 20;
        if (profile.mutedUntil() > 0) {
            String until = profile.mutedUntil() == Long.MAX_VALUE ? tr("social.forever") : SocialFormat.dateTime(profile.mutedUntil());
            PhoneDraw.text(g, PhoneDraw.ellipsize(tr("social.muted_until", until), w - 16, 0.65f), 8, y + 1, PhoneTheme.danger(),
                    0.65f, false);
            y += 12;
        }
        g.fill(0, y + 2, w, y + 3, PhoneTheme.separator());
        section(g, tr("social.posts_title"), y + 6);
    }

    private void counter(GuiGraphics g, int x, int y, int w, int value, String label, int mx, int my, Runnable action) {
        boolean hover = action != null && PhoneDraw.inside(mx, my, x, y, w, 24);
        if (hover) {
            PhoneDraw.roundedRect(g, x + 2, y, x + w - 2, y + 24, 4, PhoneTheme.cardHover());
        }
        PhoneDraw.centeredText(g, SocialFormat.count(value), x + w / 2f, y + 3, PhoneTheme.text(), 1f, false);
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(label, w - 2, 0.55f), x + w / 2f, y + 15, PhoneTheme.secondaryText(), 0.55f, false);
        if (action != null) {
            hit(x, y, w, 24, action);
        }
    }

    private PopupMenu profileMenu(ProfileInfo profile) {
        PopupMenu m = new PopupMenu();
        if (ClientNetwork.messengerAvailable()) {
            if (profile.blocked()) {
                m.add(tr("privacy.unblock"), () -> dev.blockphone.fabric.client.social.PrivacyClient.list(uuid, true, false));
            } else {
                m.addDanger(tr("privacy.block"), () -> dev.blockphone.fabric.client.social.PrivacyClient.list(uuid, true, true));
            }
        }
        m.add(tr("social.report"), () -> showMenu(PostCard.reportMenu(ReportTarget.USER, 0, uuid), width() - 10, 40));
        if (ClientSession.has(Rights.MOD_MUTE)) {
            if (profile.mutedUntil() > 0) {
                m.add(tr("social.unmute"), () -> SocialClient.send(new SocialPackets.Mute(uuid, 0)));
            }
            m.addDanger(tr("social.mute"), () -> showMenu(muteMenu(), width() - 10, 40));
        }
        return m;
    }

    private PopupMenu muteMenu() {
        PopupMenu m = new PopupMenu();
        int[] hours = {1, 24, 24 * 7, -1};
        String[] labels = {"social.mute_1h", "social.mute_1d", "social.mute_7d", "social.mute_forever"};
        for (int i = 0; i < hours.length; i++) {
            int h = hours[i];
            m.addDanger(tr(labels[i]), () -> SocialClient.send(new SocialPackets.Mute(uuid, h)));
        }
        return m;
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (menu != null && menu.isOpen()) {
            return super.mouseClicked(x, y, button);
        }
        if (editing && bioInput.mouseClicked(x, y, button)) {
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        return editing && bioInput.mouseDragged(x, y);
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        return editing && bioInput.mouseReleased();
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        return editing && bioInput.isFocused() && bioInput.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return editing && bioInput.isFocused() && bioInput.charTyped(event);
    }

    @Override
    boolean onBack() {
        if (super.onBack()) {
            return true;
        }
        if (editing) {
            editing = false;
            return true;
        }
        return false;
    }
}
