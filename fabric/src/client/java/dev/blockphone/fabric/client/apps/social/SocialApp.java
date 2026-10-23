package dev.blockphone.fabric.client.apps.social;

import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.packet.SessionPacket;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDialog;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.SocialClient;
import dev.blockphone.fabric.client.ui.ImageViewer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Лента сервера: посты игроков и пабликов, профили, подписки, паблики, модерация.
 * Внизу — вкладки «Лента», «Паблики», «Профиль» (+ «Модерация» для модераторов); экраны каждой вкладки — стеком
 * наследников {@link SocialView}. Данные — в {@link SocialClient}. Видно только на сервере с BlockPhone и включённой лентой.
 */
public final class SocialApp extends PhoneApp {
    static final int TAB_H = 18;

    public enum Tab { FEED, PUBLICS, PROFILE, MODERATION }

    private final Deque<SocialView> stack = new ArrayDeque<>();
    /** Внутри общего приложения «Мессенджер» (HubApp): свою панель вкладок не рисует, «Написать» — через хозяина. */
    private boolean embedded;
    private java.util.function.Consumer<UUID> openChat;
    private Runnable openStickers;
    private final ImageViewer viewer = new ImageViewer();
    private Tab tab = Tab.FEED;

    public SocialApp() {
        super("feed");
        stack.push(new FeedView(this));
    }

    SocialView view() {
        return stack.peek();
    }

    /** Вложить в общее приложение: openChat — открыть личный чат с игроком во вкладке «Чаты», openStickers — «Мои стикеры». */
    public void embed(java.util.function.Consumer<UUID> openChat, Runnable openStickers) {
        this.embedded = true;
        this.openChat = openChat;
        this.openStickers = openStickers;
    }

    /** «Поделиться постом в чате» (общее приложение «Мессенджер»; null — нельзя). */
    private java.util.function.LongConsumer sharePost;

    public void setSharePost(java.util.function.LongConsumer share) {
        sharePost = share;
    }

    java.util.function.LongConsumer sharePost() {
        return embedded ? sharePost : null;
    }

    /** «Мои стикеры» (есть только во вложенном режиме, null — нет). */
    Runnable stickers() {
        return embedded ? openStickers : null;
    }

    public boolean atRoot() {
        return stack.size() == 1 && view().root();
    }

    /** Значок у кнопки «Модерация» (необработанные жалобы). */
    public static int moderationBadge() {
        return ClientSession.openReports();
    }

    ImageViewer viewer() {
        return viewer;
    }

    Tab tab() {
        return tab;
    }

    List<Tab> tabs() {
        List<Tab> tabs = new ArrayList<>(List.of(Tab.FEED, Tab.PUBLICS, Tab.PROFILE));
        if (ClientSession.moderator()) {
            tabs.add(Tab.MODERATION);
        }
        return tabs;
    }

    /** Перейти на вкладку (её корневой экран). */
    public void switchTab(Tab next) {
        view().onHidden();
        stack.clear();
        tab = next;
        SocialView root = switch (next) {
            case FEED -> new FeedView(this);
            case PUBLICS -> new PublicsView(this);
            case PROFILE -> new ProfileView(this, SocialClient.self(), true);
            case MODERATION -> new ModerationView(this);
        };
        stack.push(root);
        root.onShown();
    }

    void push(SocialView next) {
        view().onHidden();
        viewer.close();
        stack.push(next);
        next.onShown();
    }

    void pop() {
        if (stack.size() > 1) {
            stack.pop().onHidden();
            view().onShown();
        }
    }

    // --- Переходы ---

    public void openProfile(UUID uuid) {
        if (uuid != null) {
            push(new ProfileView(this, uuid, false));
        }
    }

    public void openPublic(long id) {
        push(new PublicView(this, id));
    }

    public void openPost(long id) {
        push(new PostView(this, id));
    }

    public void openCompose(long publicId) {
        push(new ComposeView(this, publicId));
    }

    // --- Для автоскриншотов (DevShots) ---

    /** Открыть «Новый пост» с готовым текстом и фото. */
    public void debugCompose(String text, dev.blockphone.fabric.client.photo.PhotoStore.Photo photo) {
        openCompose(0);
        if (view() instanceof ComposeView compose) {
            compose.prefill(text, photo);
        }
    }

    /** Открыть «Оформление» своей страницы с выбранными настройками. */
    public void debugLook(UUID self, int type, int pose, int background, int banner) {
        ProfileLookView view = new ProfileLookView(this, self);
        view.preset(type, pose, background, banner);
        push(view);
    }

    /** Нажать «Сохранить» в «Оформлении». */
    public void debugSaveLook() {
        if (view() instanceof ProfileLookView look) {
            look.onHeaderAction();
        }
    }

    /** Открыть «Приватность». */
    public void debugPrivacy() {
        push(new PrivacyView(this));
    }

    /** Нажать «Опубликовать» в открытом «Новом посте». */
    public void debugPublish() {
        if (view() instanceof ComposeView compose) {
            compose.publish();
        }
    }

    /** Открыть первый пост текущей ленты «Все». */
    public void debugOpenFirstPost() {
        java.util.List<dev.blockphone.common.protocol.data.SocialData.PostInfo> posts =
                SocialClient.posts(SocialClient.feed(dev.blockphone.common.protocol.data.SocialData.FeedKind.ALL, null, 0));
        if (!posts.isEmpty()) {
            openPost(posts.get(0).id());
        }
    }

    void showDialog(PhoneDialog dialog) {
        if (phone != null) {
            phone.showDialog(dialog);
        }
    }

    /** Открыть личный чат с игроком в мессенджере. */
    void openMessenger(UUID uuid) {
        if (embedded && openChat != null && ClientNetwork.messengerAvailable()) {
            openChat.accept(uuid);
            return;
        }
        if (phone != null && ClientNetwork.messengerAvailable()) {
            ClientNetwork.send(new dev.blockphone.common.protocol.packet.MessengerPackets.OpenDirect(uuid));
            phone.openApp("messenger");
        }
    }

    boolean embedded() {
        return embedded;
    }

    int viewHeight(SocialView view) {
        return contentHeight() - (view.root() ? TAB_H : 0);
    }

    // --- PhoneApp ---

    @Override
    public String title() {
        return view().title();
    }

    @Override
    public String headerAction() {
        return view().headerAction();
    }

    @Override
    public void onHeaderAction() {
        view().onHeaderAction();
    }

    @Override
    public void onShown() {
        dev.blockphone.fabric.client.phone.PhoneNotifications.remove("feed:");
        // Права могли измениться (например, выдали модератора) — перезапросить.
        ClientNetwork.send(new SessionPacket.Refresh());
        view().onShown();
    }

    @Override
    public void onHidden() {
        view().onHidden();
    }

    @Override
    public void tick() {
        if (tab == Tab.MODERATION && !ClientSession.moderator()) {
            switchTab(Tab.FEED);
        }
        view().tick();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        SocialView view = view();
        boolean overlay = viewer.isOpen();
        int vh = viewHeight(view);
        g.enableScissor(0, 0, contentWidth(), vh);
        view.renderView(g, overlay ? -1000 : mouseX, overlay ? -1000 : (mouseY < vh ? mouseY : -1000));
        g.disableScissor();
        if (view.root() && !embedded) {
            renderTabs(g, mouseX, mouseY);
        }
        String message = SocialClient.recentMessage();
        if (message != null) {
            int w = contentWidth();
            int h = PhoneDraw.wrappedHeight(message, w - 28, 0.75f) + 10;
            int y = vh - h - 8;
            PhoneDraw.roundedRect(g, 8, y, w - 8, y + h, 4, SocialClient.recentMessageIsError() ? 0xE0B8403A : 0xE02F7D4F);
            PhoneDraw.wrapped(g, message, 14, y + 5, w - 28, 0xFFFFFFFF, 0.75f, true);
        }
        viewer.render(g, contentWidth(), contentHeight());
    }

    private void renderTabs(GuiGraphics g, int mouseX, int mouseY) {
        int top = contentHeight() - TAB_H;
        int w = contentWidth();
        g.fill(0, top, w, contentHeight(), PhoneTheme.header());
        g.fill(0, top, w, top + 1, PhoneTheme.separator());
        List<Tab> tabs = tabs();
        int tw = w / tabs.size();
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            int x = i * tw;
            boolean selected = t == tab;
            boolean hovered = PhoneDraw.inside(mouseX, mouseY, x, top, tw, TAB_H);
            if (selected) {
                g.fill(x + 6, top + 1, x + tw - 6, top + 2, PhoneTheme.accent());
            }
            int color = selected ? PhoneTheme.accent() : hovered ? PhoneTheme.text() : PhoneTheme.secondaryText();
            String label = PhoneDraw.ellipsize(SocialView.tr("social.tab." + t.name().toLowerCase()), tw - 4, 0.65f);
            PhoneDraw.centeredText(g, label, x + tw / 2f, top + 7, color, 0.65f, false);
            int badge = switch (t) {
                case FEED -> tab == Tab.FEED ? 0 : SocialClient.newPosts();
                case MODERATION -> ClientSession.openReports();
                default -> 0;
            };
            if (badge > 0) {
                String count = badge > 99 ? "99+" : String.valueOf(badge);
                float bw = Math.max(8, PhoneDraw.width(count, 0.5f) + 4);
                int bx = (int) (x + tw - bw - 2);
                PhoneDraw.roundedRect(g, bx, top + 2, (int) (bx + bw), top + 9, 3, PhoneTheme.danger());
                PhoneDraw.centeredText(g, count, bx + bw / 2f, top + 3.5f, 0xFFFFFFFF, 0.5f, false);
            }
        }
    }

    @Override
    public boolean onBack() {
        if (viewer.isOpen()) {
            viewer.close();
            return true;
        }
        if (view().onBack()) {
            return true;
        }
        if (stack.size() > 1) {
            pop();
            return true;
        }
        if (tab != Tab.FEED && !embedded) {
            switchTab(Tab.FEED);
            return true;
        }
        if (embedded && (tab == Tab.PUBLICS || tab == Tab.MODERATION)) {
            // Паблики и модерация открываются из профиля — «назад» возвращает туда.
            switchTab(Tab.PROFILE);
            return true;
        }
        return false;
    }

    @Override
    public boolean capturesKeyboard() {
        return view().capturesKeyboard();
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (viewer.mouseClicked()) {
            return true;
        }
        SocialView view = view();
        if (view.root() && !embedded && y >= contentHeight() - TAB_H) {
            List<Tab> tabs = tabs();
            int index = (int) (x / (contentWidth() / tabs.size()));
            if (index >= 0 && index < tabs.size()) {
                Tab target = tabs.get(index);
                if (target == tab && view instanceof FeedView feed) {
                    feed.scrollToTop();
                } else {
                    switchTab(target);
                }
            }
            return true;
        }
        return view.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return view().mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return view().mouseDragged(x, y, button);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        return viewer.isOpen() || view().mouseScrolled(x, y, amount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return view().keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return view().charTyped(event);
    }
}
