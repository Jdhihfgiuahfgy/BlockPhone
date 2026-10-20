package dev.blockphone.fabric.client.apps.custom;

import com.mojang.blaze3d.platform.NativeImage;
import dev.blockphone.common.BlockPhone;
import dev.blockphone.common.apps.AppDefinition;
import dev.blockphone.common.apps.AppLoader;
import dev.blockphone.common.apps.AppsSettings;
import dev.blockphone.common.protocol.packet.AppPackets;
import dev.blockphone.fabric.BlockPhoneFabric;
import dev.blockphone.fabric.client.i18n.PhoneLang;
import dev.blockphone.fabric.client.phone.PhoneState;
import dev.blockphone.fabric.client.storage.ClientFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Клиентские приложения из папки игрока {@code blockphone/apps/} (папка с app.json + icon.png или файл &lt;имя&gt;.json).
 * Это данные, а не код (см. {@link CustomApp}). Сервер присылает политику ({@link AppPackets.Policy}): запрещённые на этом
 * сервере приложения не открываются; при выходе с сервера политика сбрасывается. Id на телефоне — {@code app.<id>}.
 */
public final class CustomApps {
    public static final String PREFIX = "app.";

    private static List<AppDefinition> apps = List.of();
    private static List<String> errors = List.of();
    private static String fingerprint = "";
    private static long lastScan;
    private static @Nullable AppPackets.Policy policy;
    private static final Map<String, Identifier> ICONS = new HashMap<>();
    private static final Map<String, byte[]> ICON_SOURCES = new HashMap<>();

    public static Path folder() {
        return ClientFiles.root().resolve("apps");
    }

    /** Установленные приложения (папка перечитывается, если изменилась; не чаще раза в 2 с). */
    public static List<AppDefinition> all() {
        scan(false);
        return apps;
    }

    public static @Nullable AppDefinition get(String appId) {
        for (AppDefinition app : all()) {
            if (app.id().equals(appId)) {
                return app;
            }
        }
        return null;
    }

    public static String name(AppDefinition app) {
        return app.name(PhoneLang.current());
    }

    /** Ошибки загрузки (показываются в «Настройки → Приложения»). */
    public static List<String> errors() {
        return errors;
    }

    // --- Политика сервера ---

    public static void onPolicy(AppPackets.Policy next) {
        policy = next;
        // Серверная часть могла появиться, пока приложение открыто, — перезапросить данные страницы.
        for (AppDefinition app : apps) {
            if (PhoneState.existing(PREFIX + app.id()) instanceof CustomApp open) {
                open.onPolicyChanged();
            }
        }
    }

    /** Разрешено ли приложение на текущем сервере (в одиночной игре и на серверах без BlockPhone — да). */
    public static boolean allowedHere(AppDefinition app) {
        AppPackets.Policy p = policy;
        return p == null || AppsSettings.allowed(p.mode(), p.blacklist(), p.whitelist(), app.id(), app.sha256());
    }

    /** Есть ли у приложения серверная часть на текущем сервере. */
    public static boolean serverPart(String appId) {
        AppPackets.Policy p = policy;
        return p != null && p.serverApps().contains(appId);
    }

    public static void onVariables(AppPackets.Variables v) {
        if (PhoneState.existing(PREFIX + v.appId()) instanceof CustomApp app) {
            app.onServerVariables(v.page(), v.values());
        }
    }

    public static void onCallResult(AppPackets.CallResult r) {
        if (PhoneState.existing(PREFIX + r.appId()) instanceof CustomApp app) {
            app.onServerResult(r);
        }
    }

    /** Вышли с сервера. */
    public static void reset() {
        policy = null;
    }

    // --- Папка ---

    public static void scan(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastScan < 2000) {
            return;
        }
        lastScan = now;
        String next = fingerprint(folder());
        if (next.equals(fingerprint)) {
            return;
        }
        fingerprint = next;
        AppLoader.Result result = AppLoader.loadDirectory(folder());
        for (String error : result.errors()) {
            BlockPhoneFabric.LOGGER.warn("BlockPhone app not loaded: {}", error);
        }
        apps = result.apps().stream().sorted(Comparator.comparingInt(AppDefinition::order)
                .thenComparing(CustomApps::name, String.CASE_INSENSITIVE_ORDER)).toList();
        errors = result.errors();
    }

    private static String fingerprint(Path dir) {
        if (!Files.isDirectory(dir)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> files = Files.walk(dir, 2)) {
            for (Path f : files.sorted().toList()) {
                sb.append(f).append(Files.getLastModifiedTime(f).toMillis()).append(';');
            }
        } catch (IOException e) {
            return fingerprint;
        }
        return sb.toString();
    }

    /** Создать папку с инструкцией и примером (при первом открытии «Настройки → Приложения»). */
    public static void ensureFolder() {
        Path dir = folder();
        ClientFiles.IO.execute(() -> {
            try {
                Files.createDirectories(dir);
                copyResource("CUSTOM_APPS.md", dir.resolve("CUSTOM_APPS.md"), true);
                if (!Files.exists(dir.resolve("example-tapper.json.example"))) {
                    copyResource("example-tapper.json", dir.resolve("example-tapper.json.example"), false);
                }
            } catch (IOException e) {
                BlockPhoneFabric.LOGGER.warn("Apps folder not created", e);
            }
        });
    }

    private static void copyResource(String name, Path target, boolean replace) throws IOException {
        try (var in = CustomApps.class.getResourceAsStream("/blockphone/apps/" + name)) {
            if (in != null && (replace || !Files.exists(target))) {
                Files.write(target, in.readAllBytes());
            }
        }
    }

    // --- Иконки ---

    /** Текстура иконки (создаётся в потоке рендера при первой отрисовке); null — нет или битая. */
    public static @Nullable Identifier icon(AppDefinition app) {
        if (app.icon() == null) {
            return null;
        }
        String key = app.id();
        if (ICON_SOURCES.get(key) == app.icon() && ICONS.containsKey(key)) {
            return ICONS.get(key);
        }
        Minecraft mc = Minecraft.getInstance();
        Identifier old = ICONS.remove(key);
        if (old != null) {
            mc.getTextureManager().release(old);
        }
        ICON_SOURCES.put(key, app.icon());
        Identifier id = Identifier.fromNamespaceAndPath(BlockPhone.MOD_ID, "custom_app/" + key);
        try {
            NativeImage image = NativeImage.read(app.icon());
            mc.getTextureManager().register(id, new DynamicTexture(() -> "BlockPhone app " + key, image));
            ICONS.put(key, id);
            return id;
        } catch (IOException | RuntimeException e) {
            BlockPhoneFabric.LOGGER.warn("Bad icon of app {}: {}", key, e.toString());
            return null;
        }
    }

    private CustomApps() {
    }
}
