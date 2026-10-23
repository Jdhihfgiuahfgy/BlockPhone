package dev.blockphone.fabric.client.apps.hub;

import dev.blockphone.fabric.client.apps.messenger.MessengerApp;
import dev.blockphone.fabric.client.apps.social.SocialApp;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.messenger.MessengerClient;
import dev.blockphone.fabric.client.network.ClientNetwork;
import dev.blockphone.fabric.client.network.ClientSession;
import dev.blockphone.fabric.client.phone.PhoneApp;
import dev.blockphone.fabric.client.phone.PhoneDraw;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.client.phone.PhoneTheme;
import dev.blockphone.fabric.client.social.SocialClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * «Мессенджер» — общее приложение (как ВК): внизу вкладки «Лента» (слева), «Чаты» (по центру: личные и группы) и
 * «Профиль» (справа: своя страница, оформление, приватность, стикеры, паблики, модерация). Внутри — два приложения:
 * {@link SocialApp} (лента, профиль, паблики, модерация) и {@link MessengerApp} (чаты); хаб показывает нужное и рисует
 * общую панель вкладок, пока открыт корневой экран вкладки. Без ленты на сервере — только чаты (без панели),
 * без мессенджера — только лента и профиль.
 */
public final class HubApp extends PhoneApp {
    public static final String ID = "messenger";
    private static final int TAB_H = 18;

    public enum Tab { FEED, CHATS, PROFILE }

    private final MessengerApp chats = new MessengerApp();
    private final SocialApp social = new SocialApp();
    private @Nullable Tab tab;

    public HubApp() {
        super(ID);
        social.embed(this::openDirect, this::openStickers);
    }

    /** Открытое (созданное) приложение-хаб или null. */
    public static @Nullable HubApp get() {
        return PhoneState.app(ID) instanceof HubApp hub ? hub : null;
    }

    public MessengerApp chats() {
        return chats;
    }

    public SocialApp social() {
        return social;
    }

    /** Для автоскриншотов: показать ленту/профиль, если сейчас открыты чаты. */
    public SocialApp socialShown() {
        if (current() == Tab.CHATS && tabs().size() > 1) {
            showTab(Tab.FEED);
        }
        return social;
    }

    /** Для автоскриншотов: показать вкладку «Чаты». */
    public MessengerApp chatsShown() {
        if (current() != Tab.CHATS) {
            showTab(Tab.CHATS);
        }
        return chats;
    }

    // --- Вкладки ---

    private List<Tab> tabs() {
        List<Tab> list = new ArrayList<>();
        boolean feed = ClientNetwork.socialAvailable();
        if (feed) {
            list.add(Tab.FEED);
        }
        if (ClientNetwork.messengerAvailable()) {
            list.add(Tab.CHATS);
        }
        if (feed) {
            list.add(Tab.PROFILE);
        }
        return list;
    }

    private Tab current() {
        List<Tab> list = tabs();
        if (tab == null || !list.contains(tab)) {
            Tab next = list.contains(Tab.CHATS) ? Tab.CHATS : list.isEmpty() ? Tab.CHATS : list.get(0);
            if (tab != next) {
                tab = next;
                if (next != Tab.CHATS) {
                    social.switchTab(next == Tab.FEED ? SocialApp.Tab.FEED : SocialApp.Tab.PROFILE);
                }
            }
        }
        return tab;
    }

    private PhoneApp active() {
        return current() == Tab.CHATS ? chats : social;
    }

    /** Панель вкладок видна: вкладок больше одной и открыт корневой экран вкладки. */
    private boolean barVisible() {
        if (tabs().size() < 2) {
            return false;
        }
        return current() == Tab.CHATS ? chats.atRoot() : social.atRoot();
    }

    public void showTab(Tab next) {
        PhoneApp before = active();
        Tab was = tab;
        tab = next;
        if (next == Tab.FEED) {
            social.switchTab(SocialApp.Tab.FEED);
        } else if (next == Tab.PROFILE) {
            social.switchTab(SocialApp.Tab.PROFILE);
        }
        PhoneApp after = active();
        if (was != next && before != after) {
            before.onHidden();
            after.onShown();
        }
    }

    public void showFeed() {
        showTab(Tab.FEED);
    }

    public void showModeration() {
        showTab(Tab.PROFILE);
        social.switchTab(SocialApp.Tab.MODERATION);
    }

    /** Открыть переписку во вкладке «Чаты». */
    public void openChat(long chatId) {
        showTab(Tab.CHATS);
        chats.openChat(chatId);
    }

    /** «Написать» из профиля: открыть (или создать) личный чат, он откроется, когда сервер ответит. */
    private void openDirect(UUID uuid) {
        ClientNetwork.send(new dev.blockphone.common.protocol.packet.MessengerPackets.OpenDirect(uuid));
        showTab(Tab.CHATS);
    }

    private void openStickers() {
        showTab(Tab.CHATS);
        chats.openStickers();
    }

    // --- PhoneApp ---

    private void prepare() {
        attachChild(chats);
        attachChild(social);
        int reserved = barVisible() ? TAB_H : 0;
        chats.setReservedBottom(reserved);
        // Лента сама оставляет место под панель вкладок у корневых экранов.
        social.setReservedBottom(0);
    }

    @Override
    public String title() {
        prepare();
        return active().title();
    }

    @Override
    public String headerAction() {
        return active().headerAction();
    }

    @Override
    public int headerActionColor() {
        return active().headerActionColor();
    }

    @Override
    public void onHeaderAction() {
        active().onHeaderAction();
    }

    @Override
    public void onShown() {
        prepare();
        active().onShown();
    }

    @Override
    public void onHidden() {
        active().onHidden();
    }

    @Override
    public void tick() {
        prepare();
        // Сервер открыл чат («Написать», уведомление) — показать вкладку «Чаты».
        if (MessengerClient.hasPendingOpen() && current() != Tab.CHATS && tabs().contains(Tab.CHATS)) {
            showTab(Tab.CHATS);
        }
        chats.tick();
        social.tick();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        prepare();
        boolean bar = barVisible();
        int barTop = contentHeight() - TAB_H;
        PhoneApp app = active();
        app.render(g, mouseX, bar && mouseY >= barTop ? -1000 : mouseY, delta);
        if (ClientSession.bannedUntil() > 0) {
            renderBanned(g);
        }
        if (bar) {
            renderBar(g, mouseX, mouseY, barTop);
        }
    }

    /** Полоса сверху: телефон заблокирован на этом сервере (до когда и за что). */
    private void renderBanned(GuiGraphics g) {
        int w = contentWidth();
        long until = ClientSession.bannedUntil();
        String when = until == dev.blockphone.common.protocol.data.ModerationData.FOREVER ? PhoneLang.tr("social.forever")
                : dev.blockphone.fabric.client.social.SocialFormat.dateTime(until);
        String text = PhoneLang.tr("moderation.banned_banner", when)
                + (ClientSession.banReason().isEmpty() ? "" : " · " + ClientSession.banReason());
        PhoneDraw.rawRoundedRect(g, 4, 2, w - 4, 14, 3, 0xE0B8403A);
        PhoneDraw.centeredText(g, PhoneDraw.ellipsize(text, w - 12, 0.6f), w / 2f, 5, 0xFFFFFFFF, 0.6f, false);
    }

    private void renderBar(GuiGraphics g, int mouseX, int mouseY, int top) {
        int w = contentWidth();
        g.fill(0, top, w, top + TAB_H, PhoneTheme.header());
        g.fill(0, top, w, top + 1, PhoneTheme.separator());
        List<Tab> list = tabs();
        int tw = w / list.size();
        for (int i = 0; i < list.size(); i++) {
            Tab t = list.get(i);
            int x = i * tw;
            boolean selected = t == current();
            boolean hovered = PhoneDraw.inside(mouseX, mouseY, x, top, tw, TAB_H);
            if (selected) {
                g.fill(x + 6, top + 1, x + tw - 6, top + 2, PhoneTheme.accent());
            }
            int color = selected ? PhoneTheme.accent() : hovered ? PhoneTheme.text() : PhoneTheme.secondaryText();
            String label = PhoneDraw.ellipsize(PhoneLang.tr("hub.tab." + t.name().toLowerCase()), tw - 4, 0.65f);
            PhoneDraw.centeredText(g, label, x + tw / 2f, top + 7, color, 0.65f, false);
            int badge = switch (t) {
                case FEED -> t == current() ? 0 : SocialClient.newPosts();
                case CHATS -> MessengerClient.unreadTotal();
                case PROFILE -> SocialApp.moderationBadge();
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
        return active().onBack();
    }

    @Override
    public boolean capturesKeyboard() {
        return active().capturesKeyboard();
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        prepare();
        if (barVisible() && y >= contentHeight() - TAB_H) {
            List<Tab> list = tabs();
            int index = (int) (x / (contentWidth() / list.size()));
            if (index >= 0 && index < list.size()) {
                Tab target = list.get(index);
                if (target != current() || (target != Tab.CHATS && !social.atRoot())) {
                    showTab(target);
                } else if (target == Tab.FEED) {
                    // Повторное нажатие на «Ленту» — наверх.
                    social.switchTab(SocialApp.Tab.FEED);
                }
            }
            return true;
        }
        return active().mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return active().mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return active().mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double amount) {
        return active().mouseScrolled(x, y, amount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return active().keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        return active().charTyped(event);
    }
}
