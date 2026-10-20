package dev.blockphone.common.server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Игроки, заходившие с модом (ник, последний вход). Только из потока {@link Backend}. */
public final class UserRepository {
    private final Database db;
    private final String users;

    public UserRepository(Database db) {
        this.db = db;
        this.users = db.table("users");
    }

    private Connection c() throws SQLException {
        return db.connection();
    }

    public void upsertUser(UUID uuid, String name, long now) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("INSERT INTO " + users + " (uuid, name, name_lower, last_seen) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name), name_lower = VALUES(name_lower), last_seen = VALUES(last_seen)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, name.toLowerCase(Locale.ROOT));
            ps.setLong(4, now);
            ps.executeUpdate();
        }
    }

    public void touchUser(UUID uuid, long now) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("UPDATE " + users + " SET last_seen = ? WHERE uuid = ?")) {
            ps.setLong(1, now);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    public boolean userExists(UUID uuid) throws SQLException {
        return !userNames(List.of(uuid)).isEmpty();
    }

    /** Последний вход (0 — игрок неизвестен). */
    public long lastSeen(UUID uuid) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT last_seen FROM " + users + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** Ники по UUID (неизвестные пропускаются). */
    public Map<UUID, String> userNames(Collection<UUID> uuids) throws SQLException {
        Map<UUID, String> result = new HashMap<>();
        if (uuids.isEmpty()) {
            return result;
        }
        String marks = String.join(",", java.util.Collections.nCopies(uuids.size(), "?"));
        try (PreparedStatement ps = c().prepareStatement("SELECT uuid, name FROM " + users + " WHERE uuid IN (" + marks + ")")) {
            int i = 1;
            for (UUID uuid : uuids) {
                ps.setString(i++, uuid.toString());
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                }
            }
        }
        return result;
    }

    /** Игроки, чей ник начинается с prefix (без учёта регистра), недавно заходившие — первыми. */
    public Map<UUID, String> searchUsers(String prefix, int limit) throws SQLException {
        Map<UUID, String> result = new LinkedHashMap<>();
        try (PreparedStatement ps = c().prepareStatement("SELECT uuid, name FROM " + users
                + " WHERE name_lower LIKE ? ORDER BY last_seen DESC LIMIT ?")) {
            ps.setString(1, escapeLike(prefix.toLowerCase(Locale.ROOT)) + "%");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                }
            }
        }
        return result;
    }

    /** Экранирует \, % и _ для LIKE. */
    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
