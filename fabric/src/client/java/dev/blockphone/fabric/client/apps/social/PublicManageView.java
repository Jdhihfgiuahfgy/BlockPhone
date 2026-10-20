package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.SocialData;
import dev.blockphone.common.protocol.data.SocialData.PublicInfo;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.data.SocialData.Role;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.common.protocol.packet.SocialPackets.PublicAction.Action;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * Управление пабликом: название и описание, администраторы (добавить — владелец, убрать — владелец или сам себя),
 * удаление паблика (владелец или модератор).
 */
final class PublicManageView extends SocialView {
    private final long publicId;
    private final TextArea title = new TextArea(false, 0.85f);
    private final TextArea description = new TextArea(true, 0.75f);
    private boolean filled;

    PublicManageView(SocialApp app, long publicId) {
        super(app);
        this.publicId = publicId;
        title.setMaxLength(SocialData.MAX_PUBLIC_TITLE);
        description.setMaxLength(SocialData.MAX_PUBLIC_DESCRIPTION);
    }

    @Override
    String title() {
        return tr("social.manage");
    }

    @Override
    void onShown() {
        SocialClient.requestPublic(publicId);
    }

    @Override
    void onHidden() {
        title.setFocused(false);
        description.setFocused(false);
    }

    @Override
    boolean capturesKeyboard() {
        return title.isFocused() || description.isFocused();
    }

    private void send(Action action, String t, String d, java.util.UUID user) {
        SocialClient.send(new SocialPackets.PublicAction(publicId, action, t, d, user));
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        PublicInfo info = SocialClient.publicInfo(publicId);
        if (info == null) {
            hint(g, tr("social.public_deleted"), height() / 2 - 10);
            return;
        }
        if (!filled) {
            title.setText(info.title());
            description.setText(info.description());
            filled = true;
        }
        boolean owner = info.role() == Role.OWNER;
        boolean mod = ClientSession.has(Rights.MOD_PUBLICS);

        int total = 210 + (info.admins().size() + 1) * Rows.H;
        clampScroll(total, height());
        beginList(g, 0, height());
        int y = 4 - (int) scroll;
        PhoneDraw.text(g, tr("social.public_title"), 8, y + 2, PhoneTheme.secondaryText(), 0.65f, false);
        title.setBounds(6, y + 10, w - 12, 18);
        title.render(g, mouseX, mouseY);
        PhoneDraw.text(g, tr("social.public_description"), 8, y + 34, PhoneTheme.secondaryText(), 0.65f, false);
        description.setBounds(6, y + 42, w - 12, 60);
        description.render(g, mouseX, mouseY);
        y += 106;
        button(g, 6, y, w - 12, 16, tr("messenger.save"), mouseX, mouseY, PhoneDraw.ButtonStyle.PRIMARY,
                () -> send(Action.EDIT, title.text().strip(), description.text().strip(), null));
        y += 24;

        section(g, tr("social.admins"), y);
        y += 10;
        Rows.user(this, g, info.owner(), y, w, mouseX, mouseY, tr("social.role.owner"), () -> app.openProfile(info.owner().uuid()));
        y += Rows.H;
        for (UserInfo admin : info.admins()) {
            boolean removable = owner || mod || SocialClient.isSelf(admin.uuid());
            Rows.user(this, g, admin, y, w, mouseX, mouseY, removable ? null : tr("social.role.admin"),
                    () -> app.openProfile(admin.uuid()));
            if (removable) {
                int bx = w - 24;
                int by = y + 6;
                button(g, bx, by, 16, 14, "×", mouseX, mouseY, PhoneDraw.ButtonStyle.DANGER,
                        () -> send(Action.REMOVE_ADMIN, "", "", admin.uuid()));
            }
            y += Rows.H;
        }
        if ((owner || mod) && info.admins().size() < SocialData.MAX_PUBLIC_ADMINS) {
            button(g, 6, y + 4, w - 12, 16, "+  " + tr("social.add_admin"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL,
                    () -> app.push(new UserPickView(app, tr("social.add_admin"),
                            user -> send(Action.ADD_ADMIN, "", "", user.uuid()))));
            y += 24;
        }
        if (owner || mod) {
            button(g, 6, y + 8, w - 12, 16, tr("social.delete_public"), mouseX, mouseY, PhoneDraw.ButtonStyle.DANGER,
                    () -> app.showDialog(PhoneDialog.confirmDelete(tr("social.delete_public_title"),
                            tr("social.delete_public_message", info.title()), () -> {
                                send(Action.DELETE, "", "", null);
                                app.pop();
                            })));
        }
        endList(g);
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        boolean t = title.mouseClicked(x, y, button);
        boolean d = description.mouseClicked(x, y, button);
        if (t || d) {
            title.setFocused(t);
            description.setFocused(d);
            return true;
        }
        title.setFocused(false);
        description.setFocused(false);
        return super.mouseClicked(x, y, button);
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        return title.mouseDragged(x, y) || description.mouseDragged(x, y);
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        return title.mouseReleased() | description.mouseReleased();
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        if (title.isFocused()) {
            return title.keyPressed(event);
        }
        return description.isFocused() && description.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        if (title.isFocused()) {
            return title.charTyped(event);
        }
        return description.isFocused() && description.charTyped(event);
    }
}
