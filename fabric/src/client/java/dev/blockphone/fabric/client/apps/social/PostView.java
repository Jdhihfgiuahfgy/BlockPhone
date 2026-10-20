package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.SocialData;
import dev.blockphone.common.protocol.data.SocialData.CommentInfo;
import dev.blockphone.common.protocol.data.SocialData.PostInfo;
import dev.blockphone.common.protocol.data.SocialData.ReportTarget;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.social.SocialFormat;
import dev.blockphone.fabric.client.ui.PopupMenu;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Пост целиком и комментарии к нему; внизу — поле комментария (Enter — отправить). */
final class PostView extends SocialView {
    private static final int INPUT_H = 18;
    private static final int AVATAR = 12;
    private static final float TEXT = 0.7f;
    private static final int LINE_H = 8;

    private final long postId;
    private final TextArea input = new TextArea(false, 0.75f);
    private boolean wasLoaded;

    PostView(SocialApp app, long postId) {
        super(app);
        this.postId = postId;
        input.setMaxLength(SocialData.MAX_COMMENT_LENGTH);
    }

    @Override
    String title() {
        return tr("social.post");
    }

    @Override
    void onShown() {
        SocialClient.requestComments(postId);
    }

    @Override
    void onHidden() {
        input.setFocused(false);
    }

    @Override
    boolean capturesKeyboard() {
        return input.isFocused();
    }

    private boolean canComment() {
        return ClientSession.has(Rights.POST);
    }

    private int listBottom() {
        return canComment() ? height() - INPUT_H - 6 : height();
    }

    private int commentHeight(CommentInfo c, int w) {
        return 14 + PostCard.lines(c.text(), w - 30, TEXT).size() * LINE_H + 4;
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        PostInfo post = SocialClient.post(postId);
        SocialClient.Comments comments = SocialClient.comments(postId);
        if (post == null) {
            hint(g, tr(wasLoaded ? "social.post_deleted" : "messenger.loading"), height() / 2 - 10);
            return;
        }
        wasLoaded = true;
        int bottom = listBottom();
        int postH = PostCard.height(post, w - 8, true);
        int total = 4 + postH + 8 + 12;
        if (comments.hasMore) {
            total += 16;
        }
        for (CommentInfo c : comments.list) {
            total += commentHeight(c, w);
        }
        total += 6;
        clampScroll(total, bottom);

        beginList(g, 0, bottom);
        int listMouseY = mouseY < bottom ? mouseY : -1000;
        int y = 4 - (int) scroll;
        PostCard.draw(this, g, post, 4, y, w - 8, mouseX, listMouseY, true);
        y += postH + 8;
        section(g, tr("social.comments", post.comments()), y);
        y += 12;
        if (!comments.loaded) {
            PhoneDraw.text(g, tr("messenger.loading"), 8, y, PhoneTheme.secondaryText(), 0.7f, false);
        } else if (comments.list.isEmpty()) {
            PhoneDraw.text(g, tr("social.no_comments"), 8, y, PhoneTheme.secondaryText(), 0.7f, false);
        }
        if (comments.hasMore) {
            button(g, 8, y, w - 16, 13, tr("social.older_comments"), mouseX, listMouseY, PhoneDraw.ButtonStyle.NORMAL,
                    () -> SocialClient.requestOlderComments(postId));
            y += 16;
        }
        for (CommentInfo c : comments.list) {
            int h = commentHeight(c, w);
            if (y + h >= 0 && y <= bottom) {
                renderComment(g, c, y, w, mouseX, listMouseY);
            }
            y += h;
        }
        endList(g);

        if (canComment()) {
            int top = height() - INPUT_H - 3;
            g.fill(0, top - 3, w, top - 2, PhoneTheme.separator());
            input.setBounds(6, top, w - 30, INPUT_H);
            input.setPlaceholder(tr("social.comment_placeholder"));
            input.render(g, mouseX, mouseY);
            boolean canSend = !input.text().isBlank();
            int bx = w - 22;
            PhoneDraw.roundedRect(g, bx, top + 1, bx + 16, top + 17, 8, canSend ? PhoneTheme.accent() : PhoneTheme.separator());
            PhoneDraw.centeredText(g, "➤", bx + 8, top + 5, PhoneTheme.onAccent(), 0.8f, false);
            hit(bx, top + 1, 16, 16, this::submit);
        }
    }

    private void renderComment(GuiGraphics g, CommentInfo c, int y, int w, int mx, int my) {
        Avatars.drawUser(g, c.author(), 8, y + 2, AVATAR);
        int tx = 8 + AVATAR + 5;
        String name = c.author().name();
        PhoneDraw.text(g, name, tx, y + 2, PhoneTheme.text(), 0.7f, false);
        PhoneDraw.text(g, SocialFormat.ago(c.createdAt()), tx + PhoneDraw.width(name, 0.7f) + 4, y + 3, PhoneTheme.secondaryText(),
                0.55f, false);
        hit(8, y + 2, AVATAR + 5 + (int) PhoneDraw.width(name, 0.7f), AVATAR, () -> app.openProfile(c.author().uuid()));
        List<FormattedCharSequence> lines = PostCard.lines(c.text(), w - 30, TEXT);
        PostCard.drawLines(g, lines, tx, y + 11, PhoneTheme.text(), TEXT, LINE_H);
        int mx0 = w - 16;
        boolean hover = PhoneDraw.inside(mx, my, mx0 - 2, y, 14, 10);
        PhoneDraw.tinted(g, PostCard.MORE, mx0, y, 10, 10, 32, 32, hover ? PhoneTheme.text() : PhoneTheme.secondaryText());
        hit(mx0 - 2, y, 14, 10, () -> showMenu(commentMenu(c), mx0, y + 10));
    }

    private PopupMenu commentMenu(CommentInfo c) {
        PopupMenu m = new PopupMenu();
        m.add(tr("messenger.copy"), () -> Minecraft.getInstance().keyboardHandler.setClipboard(c.text()));
        if (!SocialClient.isSelf(c.author().uuid())) {
            m.add(tr("social.report"), () -> showMenu(PostCard.reportMenu(ReportTarget.COMMENT, c.id(), null), width() - 10, 40));
        }
        if (c.canDelete()) {
            m.addDanger(tr("dialog.delete"), () -> app.showDialog(PhoneDialog.confirmDelete(tr("social.delete_comment_title"),
                    tr("social.delete_comment_message"), () -> SocialClient.send(new SocialPackets.DeleteComment(c.id())))));
        }
        return m;
    }

    private void submit() {
        String text = input.text().strip();
        if (text.isEmpty()) {
            return;
        }
        SocialClient.send(new SocialPackets.AddComment(postId, text));
        input.setText("");
        scroll = Double.MAX_VALUE; // К новому комментарию внизу.
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (menu != null && menu.isOpen()) {
            return super.mouseClicked(x, y, button);
        }
        if (canComment() && input.mouseClicked(x, y, button)) {
            return true;
        }
        input.setFocused(false);
        return super.mouseClicked(x, y, button);
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        return input.mouseDragged(x, y);
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        return input.mouseReleased();
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        if (!input.isFocused()) {
            return false;
        }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        return input.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return input.isFocused() && input.charTyped(event);
    }
}
