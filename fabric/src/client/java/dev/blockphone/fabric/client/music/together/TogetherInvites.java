package dev.blockphone.fabric.client.music.together;

import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.NotifyPrefs;
import dev.blockphone.fabric.client.phone.PhoneNotifications;
import dev.blockphone.fabric.client.phone.PhoneState;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Приглашения в комнату совместного прослушивания — обычным личным сообщением игры ({@code /msg ник …}), без плагина
 * на сервере: в тексте «BlockPhone» и код «BP-XXXXXX». Мод получателя узнаёт такое сообщение (и от ванильного /msg, и
 * от плагинов вроде Essentials — сообщением сервера) и показывает «Войти»; игрок без мода просто видит код.
 */
public final class TogetherInvites {
    static final Pattern INVITE = Pattern.compile("BlockPhone.*?\\bBP-([A-HJ-NP-Z2-9]{6})\\b");
    private static final long KEEP_MS = 10 * 60 * 1000;
    private static final long RESEND_MS = 20_000;

    /** Приглашение: код, кто позвал (может быть пустым — не удалось понять), когда. */
    public record Invite(String code, String from, long at) {
    }

    private static final List<Invite> INVITES = new ArrayList<>();
    private static final Map<String, Long> SENT = new HashMap<>();

    public static void init() {
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) ->
                onMessage(message, sender != null ? sender.name() : null));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                onMessage(message, null);
            }
        });
    }

    public static List<Invite> invites() {
        return List.copyOf(INVITES);
    }

    /** Позвать игрока в свою комнату (личное сообщение от имени игрока; не чаще раза в 20 с одному). */
    public static boolean invite(String player) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null || ListenTogether.role() != ListenTogether.Role.HOST) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = SENT.get(player);
        if (last != null && now - last < RESEND_MS) {
            return false;
        }
        SENT.put(player, now);
        String text = PhoneLang.tr("music.together.invite_text", "BP-" + ListenTogether.code());
        connection.sendCommand("msg " + player + " " + text);
        return true;
    }

    /** Звали ли этого игрока только что (кнопка «Позвать» → «Позван»). */
    public static boolean invitedRecently(String player) {
        Long last = SENT.get(player);
        return last != null && System.currentTimeMillis() - last < RESEND_MS;
    }

    /** Ники игроков на текущем сервере (кроме себя) — кого можно позвать. */
    public static List<String> onlinePlayers() {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return List.of();
        }
        String self = mc.getUser().getName();
        List<String> names = new ArrayList<>();
        connection.getOnlinePlayers().forEach(info -> {
            String name = info.getProfile().name();
            if (!name.equalsIgnoreCase(self) && name.matches("[A-Za-z0-9_]{1,16}")) {
                names.add(name);
            }
        });
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    static void forget(String code) {
        INVITES.removeIf(i -> i.code().equals(code));
        PhoneNotifications.remove("together:" + code);
    }

    static void tick() {
        long now = System.currentTimeMillis();
        INVITES.removeIf(i -> now - i.at() > KEEP_MS);
    }

    private static void onMessage(Component message, @Nullable String sender) {
        String text = message.getString();
        Matcher m = INVITE.matcher(text);
        if (!m.find()) {
            return;
        }
        String code = m.group(1);
        Minecraft mc = Minecraft.getInstance();
        String self = mc.getUser().getName();
        // Своё же приглашение (эхо «Вы шепчете …») или комната, где уже сидим, — не приглашение.
        if (code.equals(ListenTogether.code()) || (sender != null && sender.equalsIgnoreCase(self))) {
            return;
        }
        String from = sender != null ? sender : guessSender(text.substring(0, m.start()), self);
        if (from.equalsIgnoreCase(self)) {
            return;
        }
        INVITES.removeIf(i -> i.code().equals(code));
        INVITES.add(0, new Invite(code, from, System.currentTimeMillis()));
        String title = PhoneLang.tr("music.together.invited_title");
        String body = from.isEmpty() ? PhoneLang.tr("music.together.invited_anon", code)
                : PhoneLang.tr("music.together.invited", from);
        NotifyPrefs.sound("music");
        dev.blockphone.fabric.client.compat.ClientCompat.systemToast(mc, Component.literal(title), Component.literal(body));
        PhoneNotifications.add("together:" + code, "music", title, body, null, phone -> {
            phone.openApp("music");
            if (PhoneState.existing("music") instanceof dev.blockphone.fabric.client.apps.music.MusicApp music) {
                music.switchTab(dev.blockphone.fabric.client.apps.music.MusicApp.Tab.TOGETHER);
            }
        });
    }

    /** Кто прислал (сообщение от плагина сервера — без отправителя): ник игрока онлайн, который есть в начале текста. */
    private static String guessSender(String before, String self) {
        String lower = before.toLowerCase(Locale.ROOT);
        String found = "";
        int at = -1;
        for (String name : onlinePlayers()) {
            int i = lower.lastIndexOf(name.toLowerCase(Locale.ROOT));
            if (i > at && !name.equalsIgnoreCase(self)) {
                at = i;
                found = name;
            }
        }
        return found;
    }

    private TogetherInvites() {
    }
}
