package dev.blockphone.common.server;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.packet.ImagePackets;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.SessionPacket;
import dev.blockphone.common.protocol.packet.SocialPackets;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Серверное «ядро» с базой данных: один фоновый поток (база и состояние без гонок), одно подключение к базе,
 * игроки онлайн, права и сведения о сессии. На нём работают мессенджер ({@link MessengerService}),
 * лента ({@link SocialService}) и фото ({@link ImageService}).
 */
public final class Backend {
    private static final long UNUSED_IMAGE_TTL_MS = 24 * 3600_000L;

    private final ServerPlatform platform;
    private final ServerSettings settings;
    private final Database database;
    private final UserRepository users;
    private final ImageRepository imageRepo;
    private final ImageService images;
    private final MessengerService messenger;
    private final SocialService social;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BlockPhone Server");
        thread.setDaemon(true);
        return thread;
    });

    /** Игроки с модом, которые сейчас онлайн (в сети серверов — во всей сети): UUID → ник. */
    private final Map<UUID, String> online = new ConcurrentHashMap<>();
    private volatile boolean available;
    private volatile String serverId = "";

    public Backend(ServerPlatform platform, ServerSettings settings) {
        this.platform = platform;
        this.settings = settings;
        this.database = new Database(settings.storage());
        this.users = new UserRepository(database);
        this.imageRepo = new ImageRepository(database);
        this.images = new ImageService(this);
        this.messenger = settings.messengerEnabled() ? new MessengerService(this) : null;
        this.social = settings.social().enabled() ? new SocialService(this) : null;
    }

    public void start() {
        executor.execute(() -> {
            try {
                database.migrate();
                String id = imageRepo.metaValue("server_id");
                if (id == null || id.isBlank()) {
                    id = UUID.randomUUID().toString();
                    imageRepo.setMetaValue("server_id", id);
                }
                serverId = id;
                int removed = imageRepo.deleteUnused(System.currentTimeMillis() - UNUSED_IMAGE_TTL_MS);
                if (removed > 0) {
                    platform.info("Removed " + removed + " unused uploaded photos");
                }
                available = true;
                platform.info("BlockPhone storage ready (" + settings.storage().type() + ")");
            } catch (Exception e) {
                platform.warn("BlockPhone storage failed to start, messenger and feed disabled", e);
            }
        });
    }

    public void stop() {
        executor.execute(database::close);
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Доступ для сервисов ---

    public boolean available() {
        return available;
    }

    public ServerPlatform platform() {
        return platform;
    }

    public ServerSettings settings() {
        return settings;
    }

    Database database() {
        return database;
    }

    public UserRepository users() {
        return users;
    }

    ImageRepository imageRepo() {
        return imageRepo;
    }

    ImageService images() {
        return images;
    }

    public MessengerService messenger() {
        return messenger;
    }

    public SocialService social() {
        return social;
    }

    public String serverId() {
        return serverId;
    }

    Map<UUID, String> online() {
        return online;
    }

    boolean isOnline(UUID uuid) {
        return online.containsKey(uuid);
    }

    /** Игрок для пакета: ник из базы (или текущий) и статус «в сети». */
    UserInfo user(UUID uuid, String name) {
        String resolved = name != null ? name : online.getOrDefault(uuid, "?");
        return new UserInfo(uuid, resolved, online.containsKey(uuid));
    }

    void send(UUID player, Packet packet) {
        platform.send(player, Packets.encode(packet));
    }

    // --- Права ---

    /** Есть ли у игрока право (с учётом узла blockphone.moderator и списка moderators в настройках). */
    boolean has(UUID player, Permission permission) {
        if (permission.moderator() && listedModerator(player)) {
            return true;
        }
        if (platform.hasPermission(player, permission)) {
            return true;
        }
        return permission.moderator() && permission != Permission.MODERATOR
                && platform.hasPermission(player, Permission.MODERATOR);
    }

    private boolean listedModerator(UUID player) {
        String name = online.get(player);
        for (String entry : settings.moderators()) {
            String e = entry.strip();
            if (e.equalsIgnoreCase(player.toString()) || (name != null && e.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }

    /** Битовая маска прав для клиента. */
    int rights(UUID player) {
        int mask = 0;
        for (Permission permission : Permission.values()) {
            if (permission.right() != 0 && has(player, permission)) {
                mask |= permission.right();
            }
        }
        return mask;
    }

    void sendSession(UUID player) throws SQLException {
        int rights = rights(player);
        long mutedUntil = social != null ? social.mutedUntil(player) : 0;
        int reports = social != null && Rights.has(rights, Rights.MOD_REPORTS) ? social.openReports() : 0;
        ServerSettings.Social s = settings.social();
        send(player, new SessionPacket(serverId, social != null, s.photos(), s.maxPhotoBytes(), s.maxPhotoSide(), rights, mutedUntil,
                reports));
    }

    // --- События платформы ---

    public void onJoin(UUID uuid, String name) {
        run(uuid, false, null, () -> {
            users.upsertUser(uuid, name, System.currentTimeMillis());
            online.put(uuid, name);
            sendSession(uuid);
            if (messenger != null) {
                messenger.join(uuid, name);
            }
        });
    }

    public void onQuit(UUID uuid) {
        run(uuid, false, null, () -> {
            String name = online.remove(uuid);
            images.forget(uuid);
            if (social != null) {
                social.forget(uuid);
            }
            if (name != null) {
                users.touchUser(uuid, System.currentTimeMillis());
                if (messenger != null) {
                    messenger.quit(uuid, name);
                }
            }
        });
    }

    /** Пакет от игрока (уже декодированный). */
    public void handle(UUID player, Packet packet) {
        Class<?> group = packet.getClass().getEnclosingClass();
        if (group == MessengerPackets.class) {
            if (messenger != null) {
                run(player, true, "messenger.error.internal", () -> messenger.handle(player, packet));
            }
        } else if (group == SocialPackets.class) {
            if (social != null) {
                run(player, true, "social.error.internal", () -> social.handle(player, packet));
            }
        } else if (group == ImagePackets.class) {
            run(player, true, "photo.error.internal", () -> images.handle(player, packet));
        } else if (packet instanceof SessionPacket.Refresh) {
            run(player, true, null, () -> sendSession(player));
        }
    }

    /**
     * Выполнить в фоновом потоке.
     *
     * @param fromPlayer это пакет игрока: если он не поздоровался (нет мода или старая версия) — игнорировать
     * @param errorKey   что отправить игроку при ошибке (null — ничего)
     */
    private void run(UUID player, boolean fromPlayer, String errorKey, SqlTask task) {
        executor.execute(() -> {
            if (!available) {
                return;
            }
            if (fromPlayer && !online.containsKey(player)) {
                return;
            }
            try {
                task.run();
            } catch (Exception e) {
                platform.warn("BlockPhone error for " + player, e);
                if (errorKey != null) {
                    if (errorKey.startsWith("social.")) {
                        send(player, new SocialPackets.Failure(errorKey, ""));
                    } else if (errorKey.startsWith("photo.")) {
                        send(player, new ImagePackets.UploadResult(-1, null, errorKey));
                    } else {
                        send(player, new MessengerPackets.Failure(errorKey, ""));
                    }
                }
            }
        });
    }

    @FunctionalInterface
    interface SqlTask {
        void run() throws SQLException;
    }

    /** Выполнить в фоновом потоке без проверок (отладка, тесты). */
    public void execute(SqlTask task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                platform.warn("BlockPhone task failed", e);
            }
        });
    }
}
