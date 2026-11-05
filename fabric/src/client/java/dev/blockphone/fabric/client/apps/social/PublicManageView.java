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
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.fabric.client.messenger.Avatars;
import dev.blockphone.fabric.client.photo.ImageUploads;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.ui.PhotoPicker;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * Управление пабликом: аватар (фото из галереи или с компьютера), название и описание, администраторы (добавить — владелец, убрать — владелец или сам себя),
 * удаление паблика (владелец или модератор).
 */
final class PublicManageView extends SocialView {
    private final long publicId;
    private final TextArea title = new TextArea(false, 0.85f);
    private final TextArea description = new TextArea(true, 0.75f);
    private boolean filled;
    private final PhotoPicker picker = new PhotoPicker(this::attach);
    private ImageUploads.Upload upload;

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

    /** Фото выбрано — загрузить и сразу поставить аватаром. */
    private void attach(PhotoStore.Photo photo) {
        ImageUploads.cancel(upload);
        upload = ImageUploads.start(photo, ImageRef.Purpose.POST, done -> {
            if (done.state() == ImageUploads.State.FAILED) {
                SocialClient.showMessage(tr(done.error()), true);
            } else if (done.image() != null) {
                SocialClient.send(new SocialPackets.SetPublicAvatar(publicId, done.image().id()));
            }
        });
    }

    @Override
    void onHidden() {
        if (upload != null && upload.busy()) {
            ImageUploads.cancel(upload);
        }
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
        if (picker.isOpen()) {
            picker.render(g, mouseX, mouseY, w, height());
            return;
        }
        boolean owner = info.role() == Role.OWNER;
        boolean mod = ClientSession.has(Rights.MOD_PUBLICS);

        int total = 250 + (info.admins().size() + 1) * Rows.H;
        clampScroll(total, height());
        beginList(g, 0, height());
        int y = 4 - (int) scroll;
        // Аватар паблика.
        Avatars.drawPublic(g, info.title(), info.color(), info.avatar(), 6, y, 32);
        int ax = 44;
        int aw = (w - ax - 10) / 2;
        button(g, ax, y + 8, aw, 16, tr("social.public_avatar"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, picker::open);
        if (info.avatar() != null) {
            button(g, ax + aw + 4, y + 8, aw, 16, tr("profile.banner.remove"), mouseX, mouseY, PhoneDraw.ButtonStyle.DANGER,
                    () -> SocialClient.send(new SocialPackets.SetPublicAvatar(publicId, 0)));
        }
        if (upload != null && upload.busy()) {
            PhoneDraw.text(g, tr("photo.uploading"), ax, y + 26, PhoneTheme.secondaryText(), 0.55f, false);
        }
        y += 38;
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
    boolean mouseScrolled(double x, double y, double amount) {
        return picker.isOpen() ? picker.mouseScrolled(amount) : super.mouseScrolled(x, y, amount);
    }

    @Override
    boolean onBack() {
        if (picker.isOpen()) {
            picker.close();
            return true;
        }
        return super.onBack();
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (picker.isOpen()) {
            return picker.mouseClicked(x, y, button);
        }
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
