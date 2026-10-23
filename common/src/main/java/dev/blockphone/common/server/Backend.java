package dev.blockphone.common.server;

import dev.blockphone.common.server.filter.ContentFilter;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.Packets;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.SocialData.Rights;
import dev.blockphone.common.protocol.packet.ImagePackets;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.SessionPacket;
import dev.blockphone.common.protocol.packet.SocialPackets;
import dev.blockphone.common.protocol.packet.VoicePackets;
import dev.blockphone.common.voice.VoiceBridge;
import dev.blockphone.common.voice.VoiceBridges;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import java.util.concurrent.TimeUnit;

/**
 * Серверное «ядро» с базой данных: один фоновый поток (база и состояние без гонок), одно подключение к базе,
 * игроки онлайн, права и сведения о сессии. На нём работают мессенджер ({@link MessengerService}),
 * лента ({@link SocialService}), фото ({@link ImageService}), голосовые ({@link VoiceService}) и звонки ({@link CallService}).
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
    private final VoiceService voices;
    private final CallService calls;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "BlockPhone Server");
        thread.setDaemon(true);
        return thread;
    });

    /** Игроки с модом, которые сейчас онлайн (в сети серверов — во всей сети): UUID → ник. */
    private final Map<UUID, String> online = new ConcurrentHashMap<>();
    private final ContentFilter filter;
    private final PrivacyService privacy;
    private final StickerService stickers;
    private final ModerationService moderation;
    private volatile boolean available;
    private volatile String serverId = "";

    public Backend(ServerPlatform platform, ServerSettings settings) {
        this(platform, settings, VoiceBridges::current);
    }

    /** bridges — откуда брать голосовой мост (тесты подставляют заглушку). */
    public Backend(ServerPlatform platform, ServerSettings settings, Supplier<VoiceBridge> bridges) {
        this.platform = platform;
        this.settings = settings;
        this.database = new Database(settings.storage());
        this.users = new UserRepository(database);
        this.imageRepo = new ImageRepository(database);
        this.images = new ImageService(this);
        this.messenger = settings.messengerEnabled() ? new MessengerService(this) : null;
        this.social = settings.social().enabled() ? new SocialService(this) : null;
        this.voices = new VoiceService(this);
        this.calls = new CallService(this);
        this.filter = new ContentFilter(settings.filter());
        this.privacy = new PrivacyService(this);
        this.stickers = new StickerService(this);
        this.moderation = new ModerationService(this);
        for (String bad : filter.badEntries()) {
            platform.warn("BlockPhone filter: bad entry ignored: " + bad, null);
        }
        // Голоса из Simple Voice Chat (если он есть) — для громкой связи в звонках.
        VoiceBridges.setMicrophoneListener((player, opus) -> run(player, false, null, () -> calls.nearbyVoice(player, opus)));
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
                voices.cleanup();
                available = true;
                platform.info("BlockPhone storage ready (" + settings.storage().type() + ")");
            } catch (Exception e) {
                platform.warn("BlockPhone storage failed to start, messenger and feed disabled", e);
            }
        });
        executor.scheduleWithFixedDelay(() -> run(null, false, null, calls::tick), 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        // Позиции игроков для громкой связи — только пока она у кого-то включена.
        executor.scheduleWithFixedDelay(() -> {
            if (available && calls.needsPositions()) {
                platform.runOnMain(() -> {
                    Map<UUID, ServerPlatform.Position> snapshot = Map.copyOf(platform.positions());
                    executor.execute(() -> calls.positions(snapshot));
                });
            }
        }, 1, 500, java.util.concurrent.TimeUnit.MILLISECONDS);
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

    VoiceService voices() {
        return voices;
    }

    CallService calls() {
        return calls;
    }

    public MessengerService messenger() {
        return messenger;
    }

    public StickerService stickers() {
        return stickers;
    }

    /**
     * Квота загрузок игрока (настройки uploads.quota): null — можно загрузить ещё {@code size} байт, иначе ключ ошибки
     * с префиксом (photo./voice.) и аргументом через «|».
     */
    String quotaError(UUID player, int size, String prefix) throws SQLException {
        UploadSettings.Quota q = settings.uploads().quota;
        String voices = database.table("voices");
        if (q.uploadsPerDay > 0 && imageRepo.uploadsSince(player, System.currentTimeMillis() - 86_400_000L, voices) >= q.uploadsPerDay) {
            return prefix + "error.daily_limit|" + q.uploadsPerDay;
        }
        if (q.perPlayerMb > 0 && imageRepo.usedBytes(player, voices) + size > q.perPlayerMb * 1024L * 1024L) {
            return prefix + "error.quota|" + q.perPlayerMb;
        }
        return null;
    }

    public ModerationService moderation() {
        return moderation;
    }

    public PrivacyService privacy() {
        return privacy;
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

    /**
     * Автомодерация текста игрока (см. {@link ContentFilter}). Нарушение пишется в лог; много нарушений подряд — автомьют
     * (в ленте — обычное заглушение, в мессенджере — {@link #filterMutedUntil}).
     */
    public ContentFilter.Verdict filter(UUID player, ContentFilter.Context context, String text) {
        if (text.isEmpty() || !filter.applies(context)
                || (filter.settings().bypassForModerators && has(player, Permission.FILTER_BYPASS))) {
            return new ContentFilter.Verdict(text, false, null, java.util.List.of());
        }
        ContentFilter.Verdict verdict = filter.check(text);
        if (verdict.violation()) {
            long now = System.currentTimeMillis();
            String name = online.getOrDefault(player, player.toString());
            if (filter.settings().log) {
                platform.info("[filter] " + name + " " + context.name().toLowerCase(java.util.Locale.ROOT)
                        + (verdict.blocked() ? " blocked: " : " censored: ") + verdict.found());
            }
            if (filter.recordViolation(player, now)) {
                int minutes = filter.settings().autoMute.muteMinutes;
                platform.info("[filter] " + name + " auto-muted for " + minutes + " min");
                if (social != null) {
                    social.autoMute(player, now + minutes * 60_000L);
                }
                send(player, new MessengerPackets.Failure("messenger.error.filter_muted", String.valueOf(minutes)));
            }
        }
        return verdict;
    }

    /** До какого времени игрок не может писать из-за автомьюта фильтра (0 — может). */
    public long filterMutedUntil(UUID player) {
        return filter.mutedUntil(player, System.currentTimeMillis());
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
        long mutedUntil = moderation.mutedUntil(player);
        ModerationRepository.Punishment ban = moderation.banned(player);
        int reports = social != null && Rights.has(rights, Rights.MOD_REPORTS) ? social.openReports() : 0;
        ServerSettings.Social s = settings.social();
        boolean callsOk = calls.available() && Rights.has(rights, Rights.CALL);
        send(player, new SessionPacket(serverId, social != null, s.photos(), s.maxPhotoBytes(), s.maxPhotoSide(), rights, mutedUntil,
                reports, voices.maxSeconds(player), callsOk && ban == null, ban != null ? ban.until() : 0, ban != null ? ban.reason() : ""));
    }

    // --- События платформы ---

    public void onJoin(UUID uuid, String name) {
        run(uuid, false, null, () -> {
            users.upsertUser(uuid, name, System.currentTimeMillis());
            online.put(uuid, name);
            sendSession(uuid);
            if (messenger != null) {
                messenger.join(uuid, name);
                calls.join(uuid);
            }
        });
    }

    public void onQuit(UUID uuid) {
        run(uuid, false, null, () -> {
            String name = online.remove(uuid);
            images.forget(uuid);
            moderation.forget(uuid);
            voices.forget(uuid);
            if (messenger != null) {
                calls.quit(uuid);
            }
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
        if (group == dev.blockphone.common.protocol.packet.ModerationPackets.class) {
            run(player, true, "social.error.internal", () -> moderation.handle(player, packet));
            return;
        }
        if (packet instanceof SessionPacket.Refresh) {
            run(player, true, null, () -> sendSession(player));
            return;
        }
        String errorKey;
        SqlTask task;
        if (group == MessengerPackets.class && messenger != null) {
            errorKey = "messenger.error.internal";
            task = () -> messenger.handle(player, packet);
        } else if (group == dev.blockphone.common.protocol.packet.StickerPackets.class && messenger != null) {
            errorKey = "messenger.error.internal";
            task = () -> stickers.handle(player, packet);
        } else if (group == dev.blockphone.common.protocol.packet.PrivacyPackets.class) {
            errorKey = "messenger.error.internal";
            task = () -> privacy.handle(player, packet);
        } else if (group == SocialPackets.class && social != null) {
            errorKey = "social.error.internal";
            task = () -> social.handle(player, packet);
        } else if (group == ImagePackets.class) {
            errorKey = "photo.error.internal";
            task = () -> images.handle(player, packet);
        } else if (group == VoicePackets.class && (packet instanceof VoicePackets.UploadBegin || packet instanceof VoicePackets.UploadChunk
                || packet instanceof VoicePackets.RequestVoice)) {
            errorKey = "voice.error.internal";
            task = () -> voices.handle(player, packet);
        } else if (group == VoicePackets.class && messenger != null) {
            errorKey = "messenger.error.internal";
            task = () -> calls.handle(player, packet);
        } else {
            return;
        }
        // Бан в телефоне: кроме сведений о сессии и выхода из звонка, сервер ничего от игрока не принимает.
        run(player, true, errorKey, () -> {
            ModerationRepository.Punishment ban = packet instanceof VoicePackets.LeaveCall ? null : moderation.banned(player);
            if (ban != null) {
                sendBanned(player, ban, packet);
            } else {
                task.run();
            }
        });
    }

    /** Сообщить заблокированному игроку (на действия, которые он видит: отправка, пост, загрузка). */
    private void sendBanned(UUID player, ModerationRepository.Punishment ban, Packet packet) {
        String until = ban.until() == dev.blockphone.common.protocol.data.ModerationData.FOREVER ? "" : String.valueOf(ban.until());
        if (packet instanceof MessengerPackets.SendMessage || packet instanceof MessengerPackets.OpenDirect
                || packet instanceof MessengerPackets.CreateGroup) {
            send(player, new MessengerPackets.Failure("messenger.error.banned", until));
        } else if (packet instanceof SocialPackets.CreatePost || packet instanceof SocialPackets.AddComment) {
            send(player, new SocialPackets.Failure("social.error.banned", until));
        } else if (packet instanceof ImagePackets.UploadBegin begin) {
            send(player, new ImagePackets.UploadResult(begin.uploadId(), null, "photo.error.banned"));
        } else if (packet instanceof VoicePackets.UploadBegin begin) {
            send(player, new VoicePackets.UploadResult(begin.uploadId(), null, "voice.error.banned"));
        } else if (packet instanceof VoicePackets.StartCall) {
            send(player, new MessengerPackets.Failure("call.error.banned", until));
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
                    } else if (errorKey.startsWith("voice.")) {
                        send(player, new VoicePackets.UploadResult(-1, null, errorKey));
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
