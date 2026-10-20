package dev.blockphone.fabric.client.phone;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Центр уведомлений: последние уведомления приложений (сообщения, лента, Telegram, звонки) для экрана блокировки.
 * Только поток рендера; живут до выхода из игры.
 */
public final class PhoneNotifications {
    private static final int MAX = 30;

    /**
     * Уведомление.
     *
     * @param key    для замены и снятия (например, «messenger:12» — по чату): новое с тем же ключом заменяет старое
     * @param avatar игрок для аватара или null (тогда значок приложения)
     * @param open   что сделать при нажатии (телефон уже открыт)
     */
    public record Notice(String key, String appId, String title, String text, @Nullable UUID avatar, long time,
                         Consumer<PhoneScreen> open) {
    }

    private static final List<Notice> LIST = new ArrayList<>();

    public static void add(String key, String appId, String title, String text, @Nullable UUID avatar, Consumer<PhoneScreen> open) {
        LIST.removeIf(n -> n.key().equals(key));
        LIST.add(0, new Notice(key, appId, title, text, avatar, System.currentTimeMillis(), open));
        while (LIST.size() > MAX) {
            LIST.remove(LIST.size() - 1);
        }
    }

    /** Новые сверху. */
    public static List<Notice> all() {
        return LIST;
    }

    /** Снять уведомления, ключ которых начинается с prefix (например, открыли чат или приложение). */
    public static void remove(String prefix) {
        LIST.removeIf(n -> n.key().startsWith(prefix));
    }

    public static void clear() {
        LIST.clear();
    }

    private PhoneNotifications() {
    }
}
