package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.protocol.data.SocialData.PostInfo;
import dev.blockphone.common.protocol.data.SocialData.ReportTarget;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.social.SocialFormat;
import dev.blockphone.fabric.client.ui.Images;
import dev.blockphone.fabric.client.ui.PopupMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Карточка поста: автор (или паблик), время, текст, фото, лайки и комментарии, меню «⋯».
 * В ленте длинный текст свёрнут (клик — открыть пост), в {@link PostView} — целиком.
 */
final class PostCard {
    static final float TEXT = 0.75f;
    private static final int PAD = 5;
    private static final int AVATAR = 16;
    private static final int HEADER_H = AVATAR + PAD + 4;
    private static final int LINE_H = 8;
    private static final int FOOTER_H = 15;
    private static final int COLLAPSED_LINES = 6;

    private static final Identifier LIKE = icon("like");
    private static final Identifier LIKED = icon("liked");
    private static final Identifier COMMENT = icon("comment");
    static final Identifier MORE = icon("more");

    private static Identifier icon(String name) {
        return Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "textures/gui/social/" + name + ".png");
    }

    /** Кэш переноса строк: текст + ширина → строки. */
    private static final Map<String, List<FormattedCharSequence>> WRAP = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<FormattedCharSequence>> eldest) {
            return size() > 300;
        }
    };

    static List<FormattedCharSequence> lines(String text, int width, float scale) {
        if (text.isEmpty()) {
            return List.of();
        }
        return WRAP.computeIfAbsent(width + "|" + scale + "|" + text,
                k -> PhoneDraw.font().split(PhoneDraw.styled(text), (int) (width / scale)));
    }

    /** Нарисовать строки текста; возвращает высоту. */
    static int drawLines(GuiGraphics g, List<FormattedCharSequence> lines, int x, int y, int color, float scale, int lineH) {
        for (int i = 0; i < lines.size(); i++) {
            g.pose().pushMatrix();
            g.pose().translate(x, y + i * lineH);
            g.pose().scale(scale, scale);
            g.drawString(PhoneDraw.font(), lines.get(i), 0, 0, color, false);
            g.pose().popMatrix();
        }
        return lines.size() * lineH;
    }

    private static boolean collapsed(List<FormattedCharSequence> lines, boolean full) {
        return !full && lines.size() > COLLAPSED_LINES + 1;
    }

    static int height(PostInfo post, int w, boolean full) {
        int inner = w - PAD * 2;
        List<FormattedCharSequence> lines = lines(post.text(), inner, TEXT);
        int h = HEADER_H;
        if (!lines.isEmpty()) {
            h += (collapsed(lines, full) ? COLLAPSED_LINES + 1 : lines.size()) * LINE_H + 3;
        }
        if (post.image() != null) {
            h += Images.height(post.image(), inner) + 4;
        }
        return h + FOOTER_H;
    }

    /** Нарисовать карточку и записать её кликабельные области в view. */
    static void draw(SocialView view, GuiGraphics g, PostInfo post, int x, int y, int w, int mx, int my, boolean full) {
        int h = height(post, w, full);
        PhoneDraw.panel(g, x, y, x + w, y + h, PhoneTheme.card());
        int inner = w - PAD * 2;
        int cx = x + PAD;

        // Шапка: паблик или автор.
        int ay = y + PAD;
        String name;
        String sub = SocialFormat.ago(post.createdAt());
        if (post.pub() != null) {
            Avatars.drawPublic(g, post.pub().title(), post.pub().color(), post.pub().avatar(), cx, ay, AVATAR);
            name = post.pub().title();
            sub = sub + " · " + post.author().name();
        } else {
            Avatars.drawUser(g, post.author(), cx, ay, AVATAR);
            name = post.author().name();
        }
        int nameX = cx + AVATAR + 5;
        PhoneDraw.text(g, PhoneDraw.ellipsize(name, inner - AVATAR - 20, 0.8f), nameX, ay, PhoneTheme.text(), 0.8f, false);
        PhoneDraw.text(g, PhoneDraw.ellipsize(sub, inner - AVATAR - 20, 0.6f), nameX, ay + 10, PhoneTheme.secondaryText(), 0.6f, false);
        view.hit(cx, ay, inner - 16, AVATAR, () -> {
            if (post.pub() != null) {
                view.app.openPublic(post.pub().id());
            } else {
                view.app.openProfile(post.author().uuid());
            }
        });
        int moreX = x + w - PAD - 12;
        boolean moreHover = PhoneDraw.inside(mx, my, moreX - 2, ay - 2, 16, 14);
        PhoneDraw.tinted(g, MORE, moreX, ay + 1, 12, 12, 32, 32, moreHover ? PhoneTheme.text() : PhoneTheme.secondaryText());
        view.hit(moreX - 2, ay - 2, 16, 14, () -> view.showMenu(menu(view, post), moreX, ay + 12));

        int ty = y + HEADER_H;
        // Текст.
        List<FormattedCharSequence> lines = lines(post.text(), inner, TEXT);
        if (!lines.isEmpty()) {
            boolean cut = collapsed(lines, full);
            List<FormattedCharSequence> shown = cut ? lines.subList(0, COLLAPSED_LINES) : lines;
            int th = drawLines(g, shown, cx, ty, PhoneTheme.text(), TEXT, LINE_H);
            if (cut) {
                PhoneDraw.text(g, tr("social.more_text"), cx, ty + th, PhoneTheme.accent(), TEXT, false);
                th += LINE_H;
            }
            if (!full) {
                view.hit(cx, ty, inner, th, () -> view.app.openPost(post.id()));
            }
            ty += th + 3;
        }
        // Фото.
        if (post.image() != null) {
            int ih = Images.height(post.image(), inner);
            Images.draw(g, post.image(), cx, ty, inner, ih, Images.THUMB);
            view.hit(cx, ty, inner, ih, () -> view.app.viewer().open(post.image()));
            ty += ih + 4;
        }
        // Лайки и комментарии.
        int fy = ty + 2;
        String likes = SocialFormat.count(post.likes());
        int likeW = 14 + (int) PhoneDraw.width(likes, 0.7f);
        boolean likeHover = PhoneDraw.inside(mx, my, cx - 2, fy - 2, likeW + 4, 12);
        int likeColor = post.liked() ? PhoneTheme.danger() : likeHover ? PhoneTheme.text() : PhoneTheme.secondaryText();
        PhoneDraw.tinted(g, post.liked() ? LIKED : LIKE, cx, fy, 10, 10, 32, 32, likeColor);
        PhoneDraw.text(g, likes, cx + 13, fy + 2, likeColor, 0.7f, false);
        view.hit(cx - 2, fy - 2, likeW + 4, 12, () -> SocialClient.toggleLike(post));

        int commentX = cx + likeW + 12;
        String comments = SocialFormat.count(post.comments());
        int commentW = 14 + (int) PhoneDraw.width(comments, 0.7f);
        boolean commentHover = PhoneDraw.inside(mx, my, commentX - 2, fy - 2, commentW + 4, 12);
        int commentColor = commentHover ? PhoneTheme.text() : PhoneTheme.secondaryText();
        PhoneDraw.tinted(g, COMMENT, commentX, fy, 10, 10, 32, 32, commentColor);
        PhoneDraw.text(g, comments, commentX + 13, fy + 2, commentColor, 0.7f, false);
        if (!full) {
            view.hit(commentX - 2, fy - 2, commentW + 4, 12, () -> view.app.openPost(post.id()));
        }
    }

    /** Меню «⋯»: копировать, пожаловаться, удалить. */
    static PopupMenu menu(SocialView view, PostInfo post) {
        PopupMenu menu = new PopupMenu();
        if (!post.text().isEmpty()) {
            menu.add(tr("messenger.copy"), () -> Minecraft.getInstance().keyboardHandler.setClipboard(post.text()));
        }
        if (!SocialClient.isSelf(post.author().uuid())) {
            menu.add(tr("social.report"), () -> view.showMenu(reportMenu(ReportTarget.POST, post.id(), null), view.width() - 10, 40));
        }
        if (post.canDelete()) {
            menu.addDanger(tr("dialog.delete"), () -> view.app.showDialog(PhoneDialog.confirmDelete(tr("social.delete_post_title"),
                    tr("social.delete_post_message"), () -> SocialClient.send(new SocialPackets.DeletePost(post.id())))));
        }
        return menu;
    }

    /** Меню причин жалобы. */
    static PopupMenu reportMenu(ReportTarget type, long id, java.util.UUID user) {
        PopupMenu menu = new PopupMenu();
        for (String reason : new String[]{"spam", "abuse", "photo", "other"}) {
            menu.add(tr("social.reason." + reason), () -> SocialClient.report(type, id, user, reason));
        }
        return menu;
    }

    private static String tr(String key, Object... args) {
        return SocialView.tr(key, args);
    }

    private PostCard() {
    }
}
