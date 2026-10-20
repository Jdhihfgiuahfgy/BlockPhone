package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.data.SocialData;
import dev.blockphone.common.protocol.data.SocialData.PublicInfo;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.photo.ImageUploads;
import dev.blockphone.fabric.client.photo.PhotoStore;
import dev.blockphone.fabric.client.photo.PhotoTextures;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.social.SocialFormat;
import dev.blockphone.fabric.client.ui.PhotoPicker;
import dev.blockphone.fabric.client.ui.PopupMenu;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.List;

/**
 * Новый пост: от кого (от себя или от паблика, где игрок администратор), текст, фото из галереи
 * (загружается сразу после выбора), «Опубликовать».
 */
final class ComposeView extends SocialView {
    private static final int TEXT_TOP = 24;
    private static final int TEXT_H = 104;
    private static final long SEND_TIMEOUT_MS = 8000;

    private final TextArea text = new TextArea(true, 0.75f);
    private final PhotoPicker picker = new PhotoPicker(this::attach);
    private long publicId;
    private ImageUploads.Upload upload;
    private String nonce;
    private long sentAt;

    ComposeView(SocialApp app, long publicId) {
        super(app);
        this.publicId = publicId;
        text.setMaxLength(SocialData.MAX_POST_LENGTH);
    }

    @Override
    String title() {
        return tr("social.new_post");
    }

    @Override
    void onShown() {
        SocialClient.requestPublics(SocialPackets.RequestPublics.Kind.MANAGED);
        text.setFocused(true);
    }

    @Override
    void onHidden() {
        text.setFocused(false);
    }

    @Override
    boolean capturesKeyboard() {
        return text.isFocused();
    }

    @Override
    void tick() {
        if (nonce == null) {
            return;
        }
        if (SocialClient.takeCreated(nonce) != null) {
            nonce = null;
            app.pop();
        } else if (System.currentTimeMillis() - sentAt > SEND_TIMEOUT_MS
                || (SocialClient.recentMessage() != null && SocialClient.recentMessageIsError())) {
            nonce = null; // Ошибка (её показывает приложение) — можно попробовать снова.
        }
    }

    private String targetName() {
        if (publicId <= 0) {
            return tr("social.from_me");
        }
        PublicInfo info = SocialClient.publicInfo(publicId);
        return info != null ? info.title() : "...";
    }

    private void attach(PhotoStore.Photo photo) {
        ImageUploads.cancel(upload);
        upload = ImageUploads.start(photo, ImageRef.Purpose.POST, done -> {
            if (done.state() == ImageUploads.State.FAILED) {
                SocialClient.showMessage(tr(done.error()), true);
            }
        });
    }

    /** Для автоскриншотов: задать текст и фото. */
    void prefill(String value, PhotoStore.Photo photo) {
        text.setText(value);
        if (photo != null) {
            attach(photo);
        }
    }

    private boolean canPublish() {
        boolean photoReady = upload != null && upload.state() == ImageUploads.State.DONE;
        boolean photoBusy = upload != null && upload.busy();
        return nonce == null && !photoBusy && (!text.text().isBlank() || photoReady) && ClientSession.mutedUntil() == 0;
    }

    void publish() {
        if (!canPublish()) {
            return;
        }
        long imageId = upload != null && upload.image() != null ? upload.image().id() : 0;
        nonce = SocialClient.createPost(publicId, text.text().strip(), imageId);
        sentAt = System.currentTimeMillis();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        if (picker.isOpen()) {
            picker.render(g, mouseX, mouseY, w, height());
            return;
        }
        // От кого.
        PhoneDraw.text(g, tr("social.post_as"), 8, 8, PhoneTheme.secondaryText(), 0.7f, false);
        int bx = 8 + (int) PhoneDraw.width(tr("social.post_as"), 0.7f) + 6;
        List<PublicInfo> managed = SocialClient.publics(SocialPackets.RequestPublics.Kind.MANAGED);
        String label = targetName() + (managed != null && !managed.isEmpty() ? " ▾" : "");
        button(g, bx, 3, w - bx - 8, 15, label, mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, () -> {
            if (managed != null && !managed.isEmpty()) {
                PopupMenu m = new PopupMenu().add(tr("social.from_me"), () -> publicId = 0);
                for (PublicInfo info : managed) {
                    m.add(info.title(), () -> publicId = info.id());
                }
                showMenu(m, w - 8, 18);
            }
        });

        text.setBounds(6, TEXT_TOP, w - 12, TEXT_H);
        text.setPlaceholder(tr("social.post_placeholder"));
        text.render(g, mouseX, mouseY);
        String counter = text.text().length() + "/" + SocialData.MAX_POST_LENGTH;
        PhoneDraw.rightText(g, counter, w - 8, TEXT_TOP + TEXT_H + 2, PhoneTheme.secondaryText(), 0.55f);

        int y = TEXT_TOP + TEXT_H + 10;
        if (ClientSession.photosAllowed()) {
            if (upload == null) {
                button(g, 6, y, 70, 16, tr("social.add_photo"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, picker::open);
                y += 22;
            } else {
                PhoneDraw.panel(g, 6, y, w - 6, y + 46, PhoneTheme.card());
                PhotoTextures.Image thumb = PhotoTextures.thumbnail(upload.photo);
                if (thumb != null) {
                    PhoneDraw.texture(g, thumb.id(), 9, y + 3, 40, 40, thumb.width(), thumb.height());
                }
                String status = switch (upload.state()) {
                    case PREPARING, UPLOADING -> tr("photo.uploading");
                    case DONE -> tr("photo.ready");
                    case FAILED -> tr(upload.error());
                };
                int color = upload.state() == ImageUploads.State.FAILED ? PhoneTheme.danger() : PhoneTheme.secondaryText();
                PhoneDraw.wrapped(g, status, 54, y + 6, w - 84, color, 0.7f, false);
                button(g, 54, y + 26, 50, 14, tr("social.change_photo"), mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, picker::open);
                button(g, w - 24, y + 4, 15, 15, "×", mouseX, mouseY, PhoneDraw.ButtonStyle.NORMAL, () -> {
                    ImageUploads.cancel(upload);
                    upload = null;
                });
                y += 52;
            }
        }

        long muted = ClientSession.mutedUntil();
        if (muted > 0) {
            String until = muted == Long.MAX_VALUE ? tr("social.forever") : SocialFormat.dateTime(muted);
            int h = PhoneDraw.wrappedHeight(tr("social.muted_banner", until), w - 24, 0.7f) + 8;
            PhoneDraw.roundedRect(g, 6, y, w - 6, y + h, 4, 0x40E5534B);
            PhoneDraw.wrapped(g, tr("social.muted_banner", until), 12, y + 4, w - 24, PhoneTheme.danger(), 0.7f, false);
        }

        int py = height() - 24;
        boolean enabled = canPublish();
        String publishLabel = nonce != null ? tr("social.publishing") : tr("social.publish");
        if (enabled) {
            button(g, 6, py, w - 12, 18, publishLabel, mouseX, mouseY, PhoneDraw.ButtonStyle.PRIMARY, this::publish);
        } else {
            PhoneDraw.button(g, 6, py, w - 12, 18, publishLabel, -1000, -1000, PhoneDraw.ButtonStyle.NORMAL);
        }
    }

    @Override
    boolean mouseClicked(double x, double y, int button) {
        if (picker.isOpen()) {
            return picker.mouseClicked(x, y, button);
        }
        if (menu != null && menu.isOpen()) {
            return super.mouseClicked(x, y, button);
        }
        if (text.mouseClicked(x, y, button)) {
            return true;
        }
        text.setFocused(false);
        return super.mouseClicked(x, y, button);
    }

    @Override
    boolean mouseScrolled(double x, double y, double amount) {
        return picker.mouseScrolled(amount) || text.mouseScrolled(x, y, amount);
    }

    @Override
    boolean mouseDragged(double x, double y, int button) {
        return text.mouseDragged(x, y);
    }

    @Override
    boolean mouseReleased(double x, double y, int button) {
        return text.mouseReleased();
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        return text.isFocused() && text.keyPressed(event);
    }

    @Override
    boolean charTyped(CharacterEvent event) {
        return text.isFocused() && text.charTyped(event);
    }

    @Override
    boolean onBack() {
        if (picker.isOpen()) {
            picker.close();
            return true;
        }
        if (super.onBack()) {
            return true;
        }
        ImageUploads.cancel(upload);
        return false;
    }
}
