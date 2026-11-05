package dev.blockphone.common.server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Наказания и журнал модерации: таблицы {@code mutes} (общая с лентой), {@code bans}, {@code modlog}.
 * Вызывается только из потока бэкенда.
 */
public final class ModerationRepository {
    /** Наказание: до какого времени, причина, кто наказал, когда. */
    public record Punishment(UUID uuid, long until, String reason, UUID by, long createdAt) {
    }

    /** Строка журнала (moderator — UUID; target — UUID или другой текст). */
    public record LogRow(long id, UUID moderator, String action, String target, String details, long createdAt) {
    }

    private interface Mapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final Database db;
    private final String mutes;
    private final String bans;
    private final String modlog;

    public ModerationRepository(Database db) {
        this.db = db;
        this.mutes = db.table("mutes");
        this.bans = db.table("bans");
        this.modlog = db.table("modlog");
    }

    private Connection c() throws SQLException {
        return db.connection();
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object v = params[i];
            switch (v) {
                case null -> ps.setNull(i + 1, Types.VARCHAR);
                case UUID u -> ps.setString(i + 1, u.toString());
                case Long l -> ps.setLong(i + 1, l);
                case Integer n -> ps.setInt(i + 1, n);
                default -> ps.setString(i + 1, v.toString());
            }
        }
    }

    private <T> List<T> list(String sql, Mapper<T> mapper, Object... params) throws SQLException {
        List<T> result = new ArrayList<>();
        try (PreparedStatement ps = c().prepareStatement(sql)) {
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
        try (PreparedStatement ps = c().prepareStatement(sql)) {
            bind(ps, params);
            ps.executeUpdate();
        }
    }

    private static String cut(String text, int max) {
        return text == null ? "" : text.length() > max ? text.substring(0, max) : text;
    }

    private static Punishment punishment(ResultSet rs) throws SQLException {
        return new Punishment(UUID.fromString(rs.getString("uuid")), rs.getLong("until_at"), rs.getString("reason"),
                UUID.fromString(rs.getString("by_uuid")), rs.getLong("created_at"));
    }

    // --- Мут ---

    /** Действующий мут или null. */
    public Punishment mute(UUID uuid, long now) throws SQLException {
        List<Punishment> rows = list("SELECT uuid, until_at, reason, by_uuid, created_at FROM " + mutes + " WHERE uuid = ? AND until_at > ?",
                ModerationRepository::punishment, uuid, now);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void setMute(UUID uuid, long until, String reason, UUID by, long now) throws SQLException {
        update("DELETE FROM " + mutes + " WHERE uuid = ?", uuid);
        update("INSERT INTO " + mutes + " (uuid, until_at, by_uuid, created_at, reason) VALUES (?, ?, ?, ?, ?)",
                uuid, until, by, now, cut(reason, 255));
    }

    public void unmute(UUID uuid) throws SQLException {
        update("DELETE FROM " + mutes + " WHERE uuid = ?", uuid);
    }

    // --- Бан ---

    /** Действующий бан или null. */
    public Punishment ban(UUID uuid, long now) throws SQLException {
        List<Punishment> rows = list("SELECT uuid, until_at, reason, by_uuid, created_at FROM " + bans + " WHERE uuid = ? AND until_at > ?",
                ModerationRepository::punishment, uuid, now);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void setBan(UUID uuid, long until, String reason, UUID by, long now) throws SQLException {
        update("DELETE FROM " + bans + " WHERE uuid = ?", uuid);
        update("INSERT INTO " + bans + " (uuid, until_at, reason, by_uuid, created_at) VALUES (?, ?, ?, ?, ?)",
                uuid, until, cut(reason, 255), by, now);
    }

    public void unban(UUID uuid) throws SQLException {
        update("DELETE FROM " + bans + " WHERE uuid = ?", uuid);
    }

    /** Все, у кого сейчас мут или бан (UUID без повторов, новые наказания — первыми). */
    public List<UUID> punished(long now, int limit) throws SQLException {
        List<Punishment> all = new ArrayList<>(list("SELECT uuid, until_at, reason, by_uuid, created_at FROM " + bans
                + " WHERE until_at > ?", ModerationRepository::punishment, now));
        all.addAll(list("SELECT uuid, until_at, reason, by_uuid, created_at FROM " + mutes + " WHERE until_at > ?",
                ModerationRepository::punishment, now));
        all.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        List<UUID> result = new ArrayList<>();
        for (Punishment p : all) {
            if (!result.contains(p.uuid()) && result.size() < limit) {
                result.add(p.uuid());
            }
        }
        return result;
    }

    /** Сколько предупреждений у игрока (по журналу). */
    public int warnings(UUID uuid) throws SQLException {
        List<Integer> rows = list("SELECT COUNT(*) FROM " + modlog + " WHERE action = ? AND target = ?", rs -> rs.getInt(1), "warn", uuid.toString());
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    // --- Журнал ---

    public void log(UUID moderator, String action, String target, String details, long now) throws SQLException {
        update("INSERT INTO " + modlog + " (moderator, action, target, details, created_at) VALUES (?, ?, ?, ?, ?)",
                moderator, cut(action, 32), cut(target, 80), cut(details, 255), now);
    }

    /** Страница журнала: новые первыми, id меньше beforeId (0 — с самого нового). */
    public List<LogRow> log(long beforeId, int limit) throws SQLException {
        return list("SELECT id, moderator, action, target, details, created_at FROM " + modlog
                        + (beforeId > 0 ? " WHERE id < ?" : " WHERE id > ?") + " ORDER BY id DESC LIMIT ?",
                rs -> new LogRow(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getLong(6)), beforeId > 0 ? beforeId : 0L, limit);
    }
}
