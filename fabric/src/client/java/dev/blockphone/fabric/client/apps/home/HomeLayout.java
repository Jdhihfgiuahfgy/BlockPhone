package dev.blockphone.fabric.client.apps.home;

import dev.blockphone.fabric.client.config.PhoneSettings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Расположение рабочего стола: порядок иконок и папки ({@link PhoneSettings#homeOrder}, {@link PhoneSettings#homeFolders}).
 * Логика без Minecraft — только id приложений. Приложения, которых нет в порядке (новые), встают в конец; скрытые сейчас
 * (например, мессенджер в одиночной игре) сохраняют своё место в списке и возвращаются, когда снова видны.
 */
final class HomeLayout {
    static final String FOLDER = "folder:";

    /** Элемент рабочего стола: приложение (apps = [key]) или папка (key = "folder:<id>", apps — видимые приложения в ней). */
    record Item(String key, List<String> apps) {
        boolean folder() {
            return key.startsWith(FOLDER);
        }

        String folderId() {
            return key.substring(FOLDER.length());
        }
    }

    /** Элементы рабочего стола по порядку для видимых сейчас приложений. */
    static List<Item> arrange(List<String> visible, List<String> order, Map<String, PhoneSettings.HomeFolder> folders) {
        Set<String> shown = new HashSet<>(visible);
        Set<String> inFolders = new HashSet<>();
        for (PhoneSettings.HomeFolder folder : folders.values()) {
            inFolders.addAll(folder.apps);
        }
        Set<String> placed = new HashSet<>();
        List<Item> items = new ArrayList<>();
        List<String> keys = new ArrayList<>(order);
        for (String id : folders.keySet()) {
            if (!keys.contains(FOLDER + id)) {
                keys.add(FOLDER + id);
            }
        }
        for (String key : keys) {
            if (key.startsWith(FOLDER)) {
                PhoneSettings.HomeFolder folder = folders.get(key.substring(FOLDER.length()));
                if (folder == null || placed.contains(key)) {
                    continue;
                }
                placed.add(key);
                List<String> apps = new ArrayList<>();
                for (String app : folder.apps) {
                    if (shown.contains(app) && placed.add(app)) {
                        apps.add(app);
                    }
                }
                if (!apps.isEmpty()) {
                    items.add(new Item(key, List.copyOf(apps)));
                }
            } else if (shown.contains(key) && !inFolders.contains(key) && placed.add(key)) {
                items.add(new Item(key, List.of(key)));
            }
        }
        for (String id : visible) {
            if (!inFolders.contains(id) && placed.add(id)) {
                items.add(new Item(id, List.of(id)));
            }
        }
        return items;
    }

    /**
     * Сохранить новый порядок: показанные элементы (keys), за ними — бывшие в порядке, но сейчас не показанные.
     * Пустые папки удаляются.
     */
    static void store(List<String> keys, PhoneSettings settings) {
        settings.homeFolders.entrySet().removeIf(e -> e.getValue() == null || e.getValue().apps.isEmpty());
        List<String> order = new ArrayList<>();
        for (String key : keys) {
            if (!order.contains(key)) {
                order.add(key);
            }
        }
        for (String old : settings.homeOrder) {
            if (!order.contains(old)) {
                order.add(old);
            }
        }
        order.removeIf(k -> k.startsWith(FOLDER) && !settings.homeFolders.containsKey(k.substring(FOLDER.length())));
        Set<String> inFolders = new HashSet<>();
        for (PhoneSettings.HomeFolder folder : settings.homeFolders.values()) {
            inFolders.addAll(folder.apps);
        }
        order.removeIf(inFolders::contains);
        settings.homeOrder = order;
        settings.save();
    }

    /** Новый id папки (не занятый). */
    static String newFolderId(PhoneSettings settings) {
        int n = settings.homeFolders.size() + 1;
        while (settings.homeFolders.containsKey("f" + n)) {
            n++;
        }
        return "f" + n;
    }

    private HomeLayout() {
    }
}
