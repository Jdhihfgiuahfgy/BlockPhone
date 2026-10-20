package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.SocialData;
import dev.blockphone.common.protocol.data.SocialData.PublicInfo;
import dev.blockphone.common.protocol.data.SocialData.Role;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.ui.TextArea;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Создание паблика: название и описание. После создания открывается страница нового паблика.
 */
final class CreatePublicView extends SocialView {
    private final TextArea title = new TextArea(false, 0.85f);
    private final TextArea description = new TextArea(true, 0.75f);
    private boolean waiting;
    private long sentAt;

    CreatePublicView(SocialApp app) {
        super(app);
        title.setMaxLength(SocialData.MAX_PUBLIC_TITLE);
        description.setMaxLength(SocialData.MAX_PUBLIC_DESCRIPTION);
    }

    @Override
    String title() {
        return tr("social.create_public");
    }

    @Override
    void onShown() {
        title.setFocused(true);
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

    @Override
    void tick() {
        if (!waiting) {
            return;
        }
        String wanted = title.text().strip().toLowerCase(Locale.ROOT);
        PublicInfo created = SocialClient.findOwnedPublic(wanted);
        if (created != null && created.role() == Role.OWNER) {
            waiting = false;
            app.pop();
            app.openPublic(created.id());
        } else if (System.currentTimeMillis() - sentAt > 8000 || (SocialClient.recentMessage() != null && SocialClient.recentMessageIsError())) {
            waiting = false;
        }
    }

    private void create() {
        if (waiting || title.text().isBlank()) {
            return;
        }
        SocialClient.send(new SocialPackets.CreatePublic(title.text().strip(), description.text().strip()));
        waiting = true;
        sentAt = System.currentTimeMillis();
    }

    @Override
    void render(GuiGraphics g, int mouseX, int mouseY) {
        int w = width();
        PhoneDraw.text(g, tr("social.public_title"), 8, 6, PhoneTheme.secondaryText(), 0.65f, false);
        title.setBounds(6, 14, w - 12, 18);
        title.setPlaceholder(tr("social.public_title_placeholder"));
        title.render(g, mouseX, mouseY);
        PhoneDraw.text(g, tr("social.public_description"), 8, 40, PhoneTheme.secondaryText(), 0.65f, false);
        description.setBounds(6, 48, w - 12, 80);
        description.setPlaceholder(tr("social.public_description_placeholder"));
        description.render(g, mouseX, mouseY);
        PhoneDraw.wrapped(g, tr("social.public_hint"), 8, 136, w - 16, PhoneTheme.secondaryText(), 0.6f, false);
        boolean ready = !waiting && !title.text().isBlank();
        String label = waiting ? tr("social.creating") : tr("messenger.create");
        if (ready) {
            button(g, 6, height() - 24, w - 12, 18, label, mouseX, mouseY, PhoneDraw.ButtonStyle.PRIMARY, this::create);
        } else {
            PhoneDraw.button(g, 6, height() - 24, w - 12, 18, label, -1000, -1000, PhoneDraw.ButtonStyle.NORMAL);
        }
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
    boolean mouseScrolled(double x, double y, double amount) {
        return description.mouseScrolled(x, y, amount);
    }

    @Override
    boolean keyPressed(KeyEvent event) {
        if (title.isFocused()) {
            if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_TAB) {
                title.setFocused(false);
                description.setFocused(true);
                return true;
            }
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
