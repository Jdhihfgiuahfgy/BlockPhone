package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.SocialData.FeedKind;
import dev.blockphone.common.protocol.data.SocialData.PostInfo;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.SocialClient;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** Вкладка «Лента»: переключатель «Подписки / Все», посты с подгрузкой при прокрутке, «+» — новый пост. */
final class FeedView extends SocialView {
    private static final int LIST_TOP = 21;
    private static final int GAP = 4;

    private FeedKind kind = FeedKind.FOLLOWING;

    FeedView(SocialApp app) {
        super(app);
    }

    @Override
    boolean root() {
        return true;
    }

    @Override
    String title() {
        return tr("app.feed");
    }

    @Override
    String headerAction() {
        return ClientSession.has(Rights.POST) ? "+" : null;
    }

    @Override
    void onHeaderAction() {
        app.openCompose(0);
    }

    private SocialClient.Feed feed() {
        return SocialClient.feed(kind, null, 0);
    }

    @Override
    void onShown() {
        SocialClient.Feed feed = feed();
        if (feed.loaded && !feed.loading) {
            SocialClient.refresh(feed);
        }
        SocialClient.clearNewPosts();
    }

    void scrollToTop() {
        scroll = 0;
        SocialClient.refresh(feed());
        SocialClient.clearNewPosts();
    }

    private void setKind(FeedKind next) {
        if (kind != next) {
            kind = next;
            scroll = 0;
            feed();
        }
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        int half = (w - 12) / 2;
        button(g, 4, 3, half, 14, tr("social.following"), mouseX, mouseY,
                kind == FeedKind.FOLLOWING ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL,
                () -> setKind(FeedKind.FOLLOWING));
        button(g, 8 + half, 3, half, 14, tr("social.all"), mouseX, mouseY,
                kind == FeedKind.ALL ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL,
                () -> setKind(FeedKind.ALL));

        SocialClient.Feed feed = feed();
        List<PostInfo> posts = SocialClient.posts(feed);
        if (!feed.loaded) {
            hint(g, tr("messenger.loading"), height() / 2 - 10);
            return;
        }
        if (posts.isEmpty()) {
            if (kind == FeedKind.FOLLOWING) {
                hint(g, tr("social.empty_following"), height() / 2 - 30);
                button(g, w / 2 - 40, height() / 2 + 6, 80, 16, tr("social.show_all"), mouseX, mouseY,
                        PhoneDraw.ButtonStyle.PRIMARY, () -> setKind(FeedKind.ALL));
            } else {
                hint(g, tr("social.empty_all"), height() / 2 - 20);
            }
            return;
        }
        int viewport = height() - LIST_TOP;
        int total = 0;
        for (PostInfo post : posts) {
            total += PostCard.height(post, w - 8, false) + GAP;
        }
        total += feed.hasMore ? 16 : 4;
        clampScroll(total, viewport);
        if (feed.hasMore && scroll >= total - viewport - 60) {
            SocialClient.loadMore(feed);
        }

        int listMouseY = mouseY >= LIST_TOP ? mouseY : -1000;
        beginList(g, LIST_TOP, height());
        int y = LIST_TOP - (int) scroll;
        for (PostInfo post : posts) {
            int h = PostCard.height(post, w - 8, false);
            if (y + h >= LIST_TOP && y <= height()) {
                PostCard.draw(this, g, post, 4, y, w - 8, mouseX, listMouseY, false);
            }
            y += h + GAP;
        }
        if (feed.hasMore) {
            PhoneDraw.centeredText(g, tr("messenger.loading"), w / 2f, y + 3, PhoneTheme.secondaryText(), 0.6f, false);
        }
        endList(g);

        int fresh = SocialClient.newPosts();
        if (fresh > 0) {
            String label = tr("social.new_posts", fresh);
            int bw = (int) PhoneDraw.width(label, 0.7f) + 14;
            int bx = (w - bw) / 2;
            PhoneDraw.roundedRect(g, bx, LIST_TOP + 3, bx + bw, LIST_TOP + 16, 6, PhoneTheme.accent());
            PhoneDraw.centeredText(g, label, w / 2f, LIST_TOP + 6.5f, PhoneTheme.onAccent(), 0.7f, false);
            hit(bx, LIST_TOP + 3, bw, 13, this::scrollToTop);
        }
    }
}
