package dev.blockphone.common.server;

import dev.blockphone.common.protocol.Packet;
import dev.blockphone.common.protocol.data.ImageRef;
import dev.blockphone.common.protocol.data.MessengerData.UserInfo;
import dev.blockphone.common.protocol.data.StickerData;
import dev.blockphone.common.protocol.data.StickerData.Limits;
import dev.blockphone.common.protocol.data.StickerData.PackInfo;
import dev.blockphone.common.protocol.data.StickerData.StickerRef;
import dev.blockphone.common.protocol.packet.MessengerPackets;
import dev.blockphone.common.protocol.packet.StickerPackets;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Стикеры: свои наборы игроков и добавленные чужие. Таблицы {@code sticker_packs}, {@code stickers}, {@code sticker_installs};
 * картинки — {@code images} вида {@link ImageRepository#KIND_STICKER} (видны всем). Удалённый набор пропадает у всех,
 * но уже отправленные стикеры остаются в переписке (картинку держит сообщение).
 */
public final class StickerService {
    private final Backend backend;
    private final String packs;
    private final String stickers;
    private final String installs;
    private final RateLimiter editLimit = new RateLimiter(30, 60_000);

    StickerService(Backend backend) {
        this.backend = backend;
        Database db = backend.database();
        this.packs = db.table("sticker_packs");
        this.stickers = db.table("stickers");
        this.installs = db.table("sticker_installs");
    }

    private UploadSettings.Stickers settings() {
        return backend.settings().uploads().stickers;
    }

    /** Стикеры включены у сервера, у игрока есть право. */
    boolean enabled(UUID player) {
        return settings().enabled && backend.messenger() != null && backend.has(player, Permission.STICKERS);
    }

    Limits limits(UUID player) {
        UploadSettings.Stickers s = settings();
        return new Limits(enabled(player), s.maxKb * 1024, s.maxSide, s.maxPacks, s.maxPerPack);
    }

    void handle(UUID player, Packet packet) throws SQLException {
        switch (packet) {
            case StickerPackets.RequestMine p -> sendMine(player);
            case StickerPackets.RequestPack p -> {
                PackInfo pack = pack(player, p.packId());
                if (pack != null) {
                    backend.send(player, new StickerPackets.Pack(pack));
                } else {
                    fail(player, "pack_not_found");
                }
            }
            case StickerPackets.EditPack p -> edit(player, p);
            case StickerPackets.AddSticker p -> add(player, p);
            case StickerPackets.RemoveSticker p -> {
                Long pack = one("SELECT pack_id FROM " + stickers + " s JOIN " + packs + " p ON p.id = s.pack_id WHERE s.id = ? AND p.owner = ?",
                        rs -> rs.getLong(1), p.stickerId(), player.toString());
                if (pack != null) {
                    update("DELETE FROM " + stickers + " WHERE id = ?", p.stickerId());
                    sendMine(player);
                }
            }
            default -> {
            }
        }
    }

    private void edit(UUID player, StickerPackets.EditPack p) throws SQLException {
        if (!enabled(player)) {
            fail(player, "disabled");
            return;
        }
        if (!editLimit.allow(player)) {
            fail(player, "rate_limit");
            return;
        }
        String title = p.title().strip();
        long now = System.currentTimeMillis();
        switch (p.action()) {
            case CREATE -> {
                if (title.isEmpty() || title.length() > StickerData.MAX_TITLE) {
                    fail(player, "bad_title");
                    return;
                }
                int count = one("SELECT COUNT(*) FROM " + packs + " WHERE owner = ?", rs -> rs.getInt(1), player.toString());
                if (count >= settings().maxPacks) {
                    backend.send(player, new MessengerPackets.Failure("sticker.error.pack_limit", String.valueOf(settings().maxPacks)));
                    return;
                }
                var verdict = backend.filter(player, dev.blockphone.common.server.filter.ContentFilter.Context.GROUP_NAME, title);
                if (verdict.blocked()) {
                    backend.send(player, new MessengerPackets.Failure("messenger.error." + verdict.reason(), ""));
                    return;
                }
                long id;
                try (PreparedStatement ps = backend.database().connection().prepareStatement("INSERT INTO " + packs
                        + " (owner, title, created_at) VALUES (?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, player.toString());
                    ps.setString(2, verdict.text());
                    ps.setLong(3, now);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        keys.next();
                        id = keys.getLong(1);
                    }
                }
                update("INSERT INTO " + installs + " (uuid, pack_id, added_at) VALUES (?, ?, ?)", player.toString(), id, now);
            }
            case RENAME -> {
                if (!own(player, p.packId()) || title.isEmpty() || title.length() > StickerData.MAX_TITLE) {
                    fail(player, "bad_title");
                    return;
                }
                var verdict = backend.filter(player, dev.blockphone.common.server.filter.ContentFilter.Context.GROUP_NAME, title);
                if (verdict.blocked()) {
                    backend.send(player, new MessengerPackets.Failure("messenger.error." + verdict.reason(), ""));
                    return;
                }
                update("UPDATE " + packs + " SET title = ? WHERE id = ?", verdict.text(), p.packId());
            }
            case DELETE -> {
                if (!own(player, p.packId())) {
                    return;
                }
                update("DELETE FROM " + stickers + " WHERE pack_id = ?", p.packId());
                update("DELETE FROM " + installs + " WHERE pack_id = ?", p.packId());
                update("DELETE FROM " + packs + " WHERE id = ?", p.packId());
            }
            case INSTALL -> {
                Integer exists = one("SELECT COUNT(*) FROM " + packs + " WHERE id = ?", rs -> rs.getInt(1), p.packId());
                int installed = one("SELECT COUNT(*) FROM " + installs + " WHERE uuid = ?", rs -> rs.getInt(1), player.toString());
                if (exists == null || exists == 0) {
                    fail(player, "pack_not_found");
                    return;
                }
                if (installed >= StickerData.HARD_MAX_PACKS) {
                    backend.send(player, new MessengerPackets.Failure("sticker.error.install_limit", String.valueOf(StickerData.HARD_MAX_PACKS)));
                    return;
                }
                update("DELETE FROM " + installs + " WHERE uuid = ? AND pack_id = ?", player.toString(), p.packId());
                update("INSERT INTO " + installs + " (uuid, pack_id, added_at) VALUES (?, ?, ?)", player.toString(), p.packId(), now);
            }
            case UNINSTALL -> update("DELETE FROM " + installs + " WHERE uuid = ? AND pack_id = ? AND pack_id NOT IN "
                    + "(SELECT id FROM " + packs + " WHERE owner = ?)", player.toString(), p.packId(), player.toString());
        }
        sendMine(player);
        if (p.action() == StickerPackets.PackAction.INSTALL || p.action() == StickerPackets.PackAction.UNINSTALL) {
            PackInfo pack = pack(player, p.packId());
            if (pack != null) {
                backend.send(player, new StickerPackets.Pack(pack));
            }
        }
    }

    private void add(UUID player, StickerPackets.AddSticker p) throws SQLException {
        if (!enabled(player)) {
            fail(player, "disabled");
            return;
        }
        if (!own(player, p.packId())) {
            fail(player, "pack_not_found");
            return;
        }
        int count = one("SELECT COUNT(*) FROM " + stickers + " WHERE pack_id = ?", rs -> rs.getInt(1), p.packId());
        if (count >= settings().maxPerPack) {
            backend.send(player, new MessengerPackets.Failure("sticker.error.full", String.valueOf(settings().maxPerPack)));
            return;
        }
        ImageRef image = backend.images().claim(player, p.imageId(), ImageRepository.KIND_STICKER, p.packId());
        if (image == null) {
            backend.send(player, new MessengerPackets.Failure("photo.error.not_found", ""));
            return;
        }
        update("INSERT INTO " + stickers + " (pack_id, image_id, position, created_at) VALUES (?, ?, ?, ?)", p.packId(), image.id(),
                count, System.currentTimeMillis());
        sendMine(player);
    }

    /** Отправить игроку его наборы (свои + добавленные) и ограничения. */
    void sendMine(UUID player) throws SQLException {
        List<Long> ids = list("SELECT i.pack_id FROM " + installs + " i JOIN " + packs + " p ON p.id = i.pack_id WHERE i.uuid = ? "
                + "ORDER BY i.added_at", rs -> rs.getLong(1), player.toString());
        List<PackInfo> result = new ArrayList<>();
        for (long id : ids) {
            PackInfo pack = pack(player, id);
            if (pack != null) {
                result.add(pack);
            }
        }
        backend.send(player, new StickerPackets.Mine(limits(player), result));
    }

    private PackInfo pack(UUID viewer, long packId) throws SQLException {
        record Row(String owner, String title) {
        }
        Row row = one("SELECT owner, title FROM " + packs + " WHERE id = ?", rs -> new Row(rs.getString(1), rs.getString(2)), packId);
        if (row == null) {
            return null;
        }
        List<long[]> rows = list("SELECT id, image_id FROM " + stickers + " WHERE pack_id = ? ORDER BY position, id",
                rs -> new long[]{rs.getLong(1), rs.getLong(2)}, packId);
        Map<Long, ImageRef> images = backend.imageRepo().refs(rows.stream().map(r -> r[1]).toList());
        List<StickerRef> refs = new ArrayList<>();
        for (long[] r : rows) {
            ImageRef image = images.get(r[1]);
            if (image != null) {
                refs.add(new StickerRef(r[0], packId, image));
            }
        }
        UUID owner = UUID.fromString(row.owner());
        Map<UUID, String> names = backend.users().userNames(List.of(owner));
        UserInfo ownerInfo = backend.user(owner, names.get(owner));
        Integer installed = one("SELECT COUNT(*) FROM " + installs + " WHERE uuid = ? AND pack_id = ?", rs -> rs.getInt(1),
                viewer.toString(), packId);
        return new PackInfo(packId, row.title(), ownerInfo, owner.equals(viewer), installed != null && installed > 0, refs);
    }

    /** Стикер для сообщения: id → (набор, картинка). null — такого стикера нет. */
    StickerRef sticker(long stickerId) throws SQLException {
        long[] row = one("SELECT pack_id, image_id FROM " + stickers + " WHERE id = ?", rs -> new long[]{rs.getLong(1), rs.getLong(2)},
                stickerId);
        if (row == null) {
            return null;
        }
        ImageRef image = backend.imageRepo().refs(List.of(row[1])).get(row[1]);
        return image != null ? new StickerRef(stickerId, row[0], image) : null;
    }

    /** id стикера → набор (для истории; удалённого стикера нет в ответе). */
    Map<Long, Long> packsOf(Collection<Long> stickerIds) throws SQLException {
        Map<Long, Long> result = new HashMap<>();
        Set<Long> ids = new LinkedHashSet<>(stickerIds);
        ids.remove(0L);
        for (long id : ids) {
            Long pack = one("SELECT pack_id FROM " + stickers + " WHERE id = ?", rs -> rs.getLong(1), id);
            if (pack != null) {
                result.put(id, pack);
            }
        }
        return result;
    }

    private boolean own(UUID player, long packId) throws SQLException {
        Integer n = one("SELECT COUNT(*) FROM " + packs + " WHERE id = ? AND owner = ?", rs -> rs.getInt(1), packId, player.toString());
        return n != null && n > 0;
    }

    private void fail(UUID player, String key) {
        backend.send(player, new MessengerPackets.Failure("sticker.error." + key, ""));
    }

    // --- SQL ---

    private interface Mapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            if (params[i] instanceof Long l) {
                ps.setLong(i + 1, l);
            } else if (params[i] instanceof Integer n) {
                ps.setInt(i + 1, n);
            } else {
                ps.setString(i + 1, String.valueOf(params[i]));
            }
        }
    }

    private <T> T one(String sql, Mapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = backend.database().connection().prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapper.map(rs) : null;
            }
        }
    }

    private <T> List<T> list(String sql, Mapper<T> mapper, Object... params) throws SQLException {
        List<T> result = new ArrayList<>();
        try (PreparedStatement ps = backend.database().connection().prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapper.map(rs));
                }
            }
        }
        return result;
    }

    private void update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = backend.database().connection().prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        }
    }
}
