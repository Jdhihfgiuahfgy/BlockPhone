package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.SocialData.FeedKind;
import dev.blockphone.common.protocol.data.SocialData.PostInfo;
import dev.blockphone.common.protocol.data.SocialData.PublicInfo;
import dev.blockphone.common.protocol.data.SocialData.ReportTarget;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.data.SocialData.Role;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.common.protocol.packet.SocialPackets.PublicAction.Action;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.social.SocialFormat;
import dev.blockphone.fabric.client.ui.PopupMenu;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Паблик: аватар, название, описание, подписчики, «Подписаться»; администраторам — «Новый пост» и «Управление». */
final class PublicView extends SocialView {
    private static final int AVATAR = 32;
    private static final int GAP = 4;

    private final long publicId;
    private boolean wasLoaded;

    PublicView(SocialApp app, long publicId) {
        super(app);
        this.publicId = publicId;
    }

    @Override
    String title() {
        PublicInfo info = SocialClient.publicInfo(publicId);
        return info != null ? info.title() : tr("social.public");
    }

    @Override
    void onShown() {
        SocialClient.requestPublic(publicId);
        SocialClient.Feed feed = SocialClient.feed(FeedKind.PUBLIC, null, publicId);
        if (feed.loaded && !feed.loading) {
            SocialClient.refresh(feed);
        }
    }

    private boolean canManage(PublicInfo info) {
        return info.role() != Role.NONE || ClientSession.has(Rights.MOD_PUBLICS);
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        PublicInfo info = SocialClient.publicInfo(publicId);
        if (info == null) {
            hint(g, tr(wasLoaded ? "social.public_deleted" : "messenger.loading"), height() / 2 - 10);
            return;
        }
        wasLoaded = true;
        SocialClient.Feed feed = SocialClient.feed(FeedKind.PUBLIC, null, publicId);
        List<PostInfo> posts = SocialClient.posts(feed);
        int headerH = headerHeight(info, w);
        int total = headerH + 20;
        for (PostInfo post : posts) {
            total += PostCard.height(post, w - 8, false) + GAP;
        }
        clampScroll(total, height());
        if (feed.hasMore && scroll >= total - height() - 60) {
            SocialClient.loadMore(feed);
        }

        beginList(g, 0, height());
        int y = -(int) scroll;
        renderHeader(g, info, y, w, mouseX, mouseY);
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

    private int descriptionHeight(PublicInfo info, int w) {
        String text = info.description().isEmpty() ? tr("social.no_description") : info.description();
        return PhoneDraw.wrappedHeight(text, w - 16, 0.7f);
    }

    private int headerHeight(PublicInfo info, int w) {
        int h = 6 + AVATAR + 6 + descriptionHeight(info, w) + 8 + 20;
        if (canManage(info)) {
            h += 20;
        }
        return h + 14;
    }

    private void renderHeader(GuiGraphics g, PublicInfo info, int top, int w, int mx, int my) {
        int y = top + 6;
        Avatars.drawPublic(g, info.title(), info.color(), info.avatar(), 8, y, AVATAR);
        PhoneDraw.text(g, PhoneDraw.ellipsize(info.title(), w - 70, 1f), 46, y + 4, PhoneTheme.text(), 1f, false);
        String stats = tr("social.subscribers", SocialFormat.count(info.subscribers())) + " · "
                + tr("social.posts_n", SocialFormat.count(info.posts()));
        PhoneDraw.text(g, PhoneDraw.ellipsize(stats, w - 70, 0.6f), 46, y + 16, PhoneTheme.secondaryText(), 0.6f, false);
        String owner = tr("social.owner_is", info.owner().name());
        PhoneDraw.text(g, PhoneDraw.ellipsize(owner, w - 70, 0.55f), 46, y + 24, PhoneTheme.secondaryText(), 0.55f, false);
        hit(46, y + 23, (int) PhoneDraw.width(owner, 0.55f), 7, () -> app.openProfile(info.owner().uuid()));
        if (info.role() != Role.OWNER) {
            int mx0 = w - 18;
            int my0 = y;
            boolean hover = PhoneDraw.inside(mx, my, mx0 - 2, my0, 16, 14);
            PhoneDraw.tinted(g, PostCard.MORE, mx0, my0 + 2, 12, 12, 32, 32, hover ? PhoneTheme.text() : PhoneTheme.secondaryText());
            hit(mx0 - 2, my0, 16, 14, () -> showMenu(new PopupMenu().add(tr("social.report"),
                    () -> showMenu(PostCard.reportMenu(ReportTarget.PUBLIC, publicId, null), width() - 10, 40)), mx0, my0 + 14));
        }
        y += AVATAR + 6;
        boolean empty = info.description().isEmpty();
        PhoneDraw.wrapped(g, empty ? tr("social.no_description") : info.description(), 8, y, w - 16,
                empty ? PhoneTheme.secondaryText() : PhoneTheme.text(), 0.7f, false);
        y += descriptionHeight(info, w) + 8;

        button(g, 8, y, w - 16, 16, tr(info.subscribed() ? "social.unsubscribe" : "social.subscribe"), mx, my,
                info.subscribed() ? PhoneDraw.ButtonStyle.NORMAL : PhoneDraw.ButtonStyle.PRIMARY,
                () -> SocialClient.send(new SocialPackets.PublicAction(publicId, info.subscribed() ? Action.UNSUBSCRIBE : Action.SUBSCRIBE,
                        "", "", null)));
        y += 20;
        if (canManage(info)) {
            int bw = (w - 20) / 2;
            if (info.canPost()) {
                button(g, 8, y, bw, 16, tr("social.new_post_short"), mx, my, PhoneDraw.ButtonStyle.NORMAL, () -> app.openCompose(publicId));
            }
            button(g, 12 + bw, y, bw, 16, tr("social.manage"), mx, my, PhoneDraw.ButtonStyle.NORMAL,
                    () -> app.push(new PublicManageView(app, publicId)));
            y += 20;
        }
        g.fill(0, y + 2, w, y + 3, PhoneTheme.separator());
        section(g, tr("social.posts_title"), y + 6);
    }
}
