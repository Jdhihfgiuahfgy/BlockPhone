package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.data.SocialData.ProfileInfo;
import dev.blockphone.common.protocol.data.SocialData.ProfileLook;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.ImageUploads;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.social.ProfileLooks;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.ui.PhotoPicker;
import net.minecraft.client.gui.GuiGraphics;

import java.util.UUID;

/**
 * Оформление своей страницы: аватар (голова, фигура из скина в одной из поз или своё фото из галереи / с компьютера), фон
 * аватара, баннер (градиент или фото). Сверху — предпросмотр; «Сохранить» в заголовке.
 */
final class ProfileLookView extends SocialView {
    private final UUID uuid;
    private final PhotoPicker picker = new PhotoPicker(this::attach);
    private int avatarType;
    private int pose;
    private int background;
    private int bannerStyle;
    /** −1 — оставить фото баннера, 0 — без фото, иначе — новое загруженное. */
    private long bannerImageId = -1;
    private ImageUploads.Upload upload;
    /** Загрузка фото-аватара (null — оставить прежнее). */
    private ImageUploads.Upload avatarUpload;
    /** Для чего выбирается фото: true — аватар, false — баннер. */
    private boolean pickingAvatar;
    private boolean loaded;

    ProfileLookView(SocialApp app, UUID uuid) {
        super(app);
        this.uuid = uuid;
    }

    @Override
    String title() {
        return tr("profile.look");
    }

    @Override
    String headerAction() {
        return (upload != null && upload.busy()) || (avatarUpload != null && avatarUpload.busy()) ? null : tr("messenger.save");
    }

    @Override
    void onHeaderAction() {
        long banner = bannerImageId;
        if (upload != null && upload.state() == ImageUploads.State.DONE && upload.image() != null) {
            banner = upload.image().id();
        }
        long avatar = avatarUpload != null && avatarUpload.state() == ImageUploads.State.DONE && avatarUpload.image() != null
                ? avatarUpload.image().id() : -1;
        SocialClient.send(new SocialPackets.UpdateLook(avatarType, pose, background, bannerStyle, banner, avatar));
        app.pop();
    }

    @Override
    void onHidden() {
        if (upload != null && upload.busy()) {
            ImageUploads.cancel(upload);
        }
        if (avatarUpload != null && avatarUpload.busy()) {
            ImageUploads.cancel(avatarUpload);
        }
    }

    private void attach(PhotoStore.Photo photo) {
        java.util.function.Consumer<ImageUploads.Upload> done = u -> {
            if (u.state() == ImageUploads.State.FAILED) {
                SocialClient.showMessage(tr(u.error()), true);
            }
        };
        if (pickingAvatar) {
            ImageUploads.cancel(avatarUpload);
            avatarUpload = ImageUploads.start(photo, ImageRef.Purpose.POST, done);
            avatarType = ProfileLook.AVATAR_PHOTO;
        } else {
            ImageUploads.cancel(upload);
            upload = ImageUploads.start(photo, ImageRef.Purpose.POST, done);
        }
    }

    private void pick(boolean avatar) {
        pickingAvatar = avatar;
        picker.open();
    }

    /** Для автоскриншотов: выбрать оформление. */
    void preset(int type, int newPose, int bg, int banner) {
        loaded = true;
        avatarType = type;
        pose = newPose;
        background = bg;
        bannerStyle = banner;
    }

    private ProfileLook current(ProfileInfo profile) {
        ImageRef banner = profile.look().bannerImage();
        if (upload != null && upload.state() == ImageUploads.State.DONE && upload.image() != null) {
            banner = upload.image();
        } else if (bannerImageId == 0) {
            banner = null;
        }
        ImageRef avatar = profile.look().avatarImage();
        if (avatarUpload != null && avatarUpload.state() == ImageUploads.State.DONE && avatarUpload.image() != null) {
            avatar = avatarUpload.image();
        }
        return new ProfileLook(avatarType, pose, background, bannerStyle, banner, avatar);
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        ProfileInfo profile = SocialClient.profile(uuid);
        if (profile == null) {
            hint(g, tr("messenger.loading"), height() / 2 - 10);
            return;
        }
        if (!loaded) {
            loaded = true;
            ProfileLook l = profile.look();
            avatarType = l.avatarType();
            pose = l.pose();
            background = l.background();
            bannerStyle = l.bannerStyle();
        }
        if (picker.isOpen()) {
            picker.render(g, mouseX, mouseY, width(), height());
            return;
        }
        int w = width();
        ProfileLook look = current(profile);
        clampScroll(370, height());
        beginList(g, 0, height());
        int y = 4 - (int) scroll;

        // Предпросмотр.
        ProfileLooks.drawBanner(g, look, 6, y, w - 12, 52);
        int av = 44;
        PhoneDraw.rawRoundedRect(g, 11, y + 30, 11 + av + 4, y + 30 + av + 4, 9, PhoneTheme.background());
        ProfileLooks.drawAvatar(g, profile.user(), look, 13, y + 32, av);
        PhoneDraw.text(g, PhoneDraw.ellipsize(profile.user().name(), w - 80, 0.9f), 64, y + 58, PhoneTheme.text(), 0.9f, false);
        y += 82;

        // Аватар.
        section(g, tr("profile.avatar"), y);
        y += 14;
        int half = (w - 16) / 2;
        int third = (w - 20) / 3;
        button(g, 6, y, third, 16, tr("profile.avatar.head"), mouseX, mouseY,
                avatarType == ProfileLook.AVATAR_HEAD ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL,
                () -> avatarType = ProfileLook.AVATAR_HEAD);
        button(g, 10 + third, y, third, 16, tr("profile.avatar.body"), mouseX, mouseY,
                avatarType == ProfileLook.AVATAR_BODY ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL,
                () -> avatarType = ProfileLook.AVATAR_BODY);
        button(g, 14 + 2 * third, y, third, 16, tr("profile.avatar.photo"), mouseX, mouseY,
                avatarType == ProfileLook.AVATAR_PHOTO ? PhoneDraw.ButtonStyle.SELECTED : PhoneDraw.ButtonStyle.NORMAL, () -> {
                    if (look.avatarImage() == null && avatarUpload == null) {
                        pick(true);
                    } else {
                        avatarType = ProfileLook.AVATAR_PHOTO;
                    }
                });
        y += 20;
        if (avatarType == ProfileLook.AVATAR_PHOTO) {
            button(g, 6, y, w - 12, 16, tr("profile.avatar.choose"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, () -> pick(true));
            y += 20;
            String avatarStatus = avatarUpload == null ? null : switch (avatarUpload.state()) {
                case PREPARING, UPLOADING -> tr("photo.uploading");
                case FAILED -> tr(avatarUpload.error());
                default -> null;
            };
            if (avatarStatus != null) {
                PhoneDraw.text(g, avatarStatus, 8, y, PhoneTheme.secondaryText(), 0.6f, false);
                y += 10;
            }
        }
        if (avatarType == ProfileLook.AVATAR_BODY) {
            button(g, 6, y, 18, 16, "<", mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL,
                    () -> pose = Math.floorMod(pose - 1, ProfileLook.POSES));
            PhoneDraw.card(g, 28, y, w - 28, y + 16, false);
            PhoneDraw.centeredText(g, tr("profile.pose") + ": " + tr(ProfileLooks.poseKey(pose)), w / 2f, y + 4.5f, PhoneTheme.text(),
                    0.7f, false);
            button(g, w - 24, y, 18, 16, ">", mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL,
                    () -> pose = Math.floorMod(pose + 1, ProfileLook.POSES));
            y += 20;
        }

        // Фон аватара.
        section(g, tr("profile.background"), y);
        y += 14;
        y = swatches(g, ProfileLook.BACKGROUNDS, background, y, w, mouseX, mouseY, true);

        // Баннер.
        section(g, tr("profile.banner"), y);
        y += 14;
        y = swatches(g, ProfileLook.BANNERS, bannerStyle, y, w, mouseX, mouseY, false);
        String status = upload == null ? null : switch (upload.state()) {
            case PREPARING, UPLOADING -> tr("photo.uploading");
            case FAILED -> tr(upload.error());
            default -> null;
        };
        button(g, 6, y, half, 16, tr("profile.banner.photo"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, () -> pick(false));
        boolean hasPhoto = look.bannerImage() != null;
        button(g, 10 + half, y, half, 16, tr("profile.banner.remove"), mouseX, mouseY,
                hasPhoto ? PhoneDraw.ButtonStyle.DANGER : PhoneDraw.ButtonStyle.NORMAL, () -> {
                    ImageUploads.cancel(upload);
                    upload = null;
                    bannerImageId = 0;
                });
        y += 20;
        if (status != null) {
            PhoneDraw.text(g, status, 8, y, PhoneTheme.secondaryText(), 0.6f, false);
        }
        endList(g);
    }

    /** Ряды образцов цвета; true в {@code avatar} — фоны аватара, иначе — баннеры. */
    private int swatches(GuiGraphics g, int count, int selected, int y, int w, int mx, int my, boolean avatar) {
        int perRow = 6;
        int size = (w - 12 - (perRow - 1) * 4) / perRow;
        int h = avatar ? size : size / 2 + 4;
        for (int i = 0; i < count; i++) {
            int x = 6 + (i % perRow) * (size + 4);
            int yy = y + (i / perRow) * (h + 4);
            ProfileLook sample = new ProfileLook(0, 0, avatar ? i : 0, avatar ? 0 : i, null, null);
            if (avatar) {
                PhoneDraw.rawRoundedRect(g, x, yy, x + size, yy + h, 3, ProfileLooks.backgroundTop(i));
                g.fillGradient(x + 1, yy + 1, x + size - 1, yy + h - 1, ProfileLooks.backgroundTop(i), ProfileLooks.backgroundBottom(i));
            } else {
                ProfileLooks.drawBanner(g, sample, x, yy, size, h);
            }
            if (i == selected) {
                PhoneDraw.rawRoundedRect(g, x - 1, yy + h + 1, x + size + 1, yy + h + 3, 1, PhoneTheme.accent());
            }
            int index = i;
            hit(x, yy, size, h, () -> {
                if (avatar) {
                    background = index;
                } else {
                    bannerStyle = index;
                    ImageUploads.cancel(upload);
                    upload = null;
                    bannerImageId = 0;
                }
            });
        }
        return y + ((count + perRow - 1) / perRow) * (h + 4) + 6;
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (picker.isOpen()) {
            return picker.mouseClicked(x, y, button);
        }
        return super.mouseClicked(x, y, button);
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
}
