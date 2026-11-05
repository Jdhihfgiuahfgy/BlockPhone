package dev.blockphone.common.server;

import dev.blockphone.common.protocol.data.ImageRef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Фото (байты в базе — так они общие для всех серверов с одной MySQL) и служебная таблица meta. */
public final class ImageRepository {
    /** Загружено, но ещё не прикреплено (удаляется через сутки). */
    public static final int KIND_UNUSED = 0;
    /** В посте: ref_id — id поста. */
    public static final int KIND_POST = 1;
    /** В сообщении: ref_id — id чата. */
    public static final int KIND_MESSAGE = 2;
    /** Баннер личной страницы (виден всем). */
    public static final int KIND_BANNER = 3;
    /** Стикер (виден всем; ref_id — набор). */
    public static final int KIND_STICKER = 4;

    /** Сведения о фото без байтов. */
    public record ImageMeta(long id, UUID owner, int kind, long refId, int width, int height, int size) {
        public ImageRef ref() {
            return new ImageRef(id, width, height);
        }
    }

    private final Database db;
    private final String images;
    private final String meta;
    private final String members;

    public ImageRepository(Database db) {
        this.db = db;
        this.images = db.table("images");
        this.meta = db.table("meta");
        this.members = db.table("members");
    }

    private Connection c() throws SQLException {
        return db.connection();
    }

    /** Сколько байт занимает всё загруженное игроком: картинки и голосовые. */
    public long usedBytes(UUID owner, String voicesTable) throws SQLException {
        return sum("SELECT COALESCE(SUM(size), 0) FROM " + images + " WHERE owner = ?", owner)
                + sum("SELECT COALESCE(SUM(size), 0) FROM " + voicesTable + " WHERE owner = ?", owner);
    }

    /** Сколько загрузок (картинок и голосовых) игрок сделал с момента since. */
    public long uploadsSince(UUID owner, long since, String voicesTable) throws SQLException {
        return sum("SELECT COUNT(*) FROM " + images + " WHERE owner = ? AND created_at >= " + since, owner)
                + sum("SELECT COUNT(*) FROM " + voicesTable + " WHERE owner = ? AND created_at >= " + since, owner);
    }

    private long sum(String sql, UUID owner) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement(sql)) {
            ps.setString(1, owner.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    public long insert(UUID owner, int width, int height, byte[] data, long now) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("INSERT INTO " + images
                + " (owner, kind, ref_id, width, height, size, data, created_at) VALUES (?, 0, 0, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, width);
            ps.setInt(3, height);
            ps.setInt(4, data.length);
            ps.setBytes(5, data);
            ps.setLong(6, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** Копия картинки (пересылка): новый владелец, сразу прикреплена (kind, refId). 0 — исходной нет. */
    public long copy(long id, UUID owner, int kind, long refId, long now) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("INSERT INTO " + images
                + " (owner, kind, ref_id, width, height, size, data, created_at) SELECT ?, ?, ?, width, height, size, data, ? FROM "
                + images + " WHERE id = ?", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, kind);
            ps.setLong(3, refId);
            ps.setLong(4, now);
            ps.setLong(5, id);
            if (ps.executeUpdate() == 0) {
                return 0;
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    public ImageMeta meta(long id) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT id, owner, kind, ref_id, width, height, size FROM " + images
                + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? metaRow(rs) : null;
            }
        }
    }

    private static ImageMeta metaRow(ResultSet rs) throws SQLException {
        return new ImageMeta(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getInt(3), rs.getLong(4), rs.getInt(5),
                rs.getInt(6), rs.getInt(7));
    }

    /** Ссылки (id + размер) для набора фото; отсутствующие пропускаются. */
    public Map<Long, ImageRef> refs(Collection<Long> ids) throws SQLException {
        Map<Long, ImageRef> result = new HashMap<>();
        java.util.Set<Long> wanted = new java.util.LinkedHashSet<>(ids);
        wanted.remove(0L);
        if (wanted.isEmpty()) {
            return result;
        }
        String marks = String.join(",", java.util.Collections.nCopies(wanted.size(), "?"));
        try (PreparedStatement ps = c().prepareStatement("SELECT id, width, height FROM " + images + " WHERE id IN (" + marks + ")")) {
            int i = 1;
            for (long id : wanted) {
                ps.setLong(i++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getLong(1), new ImageRef(rs.getLong(1), rs.getInt(2), rs.getInt(3)));
                }
            }
        }
        return result;
    }

    public byte[] data(long id) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT data FROM " + images + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    /** Прикрепить фото к посту/чату. */
    public void attach(long id, int kind, long refId) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("UPDATE " + images + " SET kind = ?, ref_id = ? WHERE id = ?")) {
            ps.setInt(1, kind);
            ps.setLong(2, refId);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void delete(long id) throws SQLException {
        if (id <= 0) {
            return;
        }
        try (PreparedStatement ps = c().prepareStatement("DELETE FROM " + images + " WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    /** Удаляет загруженные, но так и не прикреплённые фото старше olderThan. Возвращает их число. */
    public int deleteUnused(long olderThan) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("DELETE FROM " + images + " WHERE kind = 0 AND created_at < ?")) {
            ps.setLong(1, olderThan);
            return ps.executeUpdate();
        }
    }

    public boolean isChatMember(long chatId, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT 1 FROM " + members + " WHERE chat_id = ? AND uuid = ?")) {
            ps.setLong(1, chatId);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // --- meta ---

    public String metaValue(String key) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT v FROM " + meta + " WHERE k = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    public void setMetaValue(String key, String value) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("INSERT INTO " + meta + " (k, v) VALUES (?, ?) ON DUPLICATE KEY UPDATE v = VALUES(v)")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }
}
