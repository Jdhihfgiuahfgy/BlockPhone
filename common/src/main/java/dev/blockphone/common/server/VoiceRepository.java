package dev.blockphone.common.server;

import dev.blockphone.common.protocol.data.VoiceData.VoiceRef;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Голосовые сообщения (байты в базе, как фото). Только из потока {@link Backend}. */
final class VoiceRepository {
    /** Сведения о голосовом без байтов. chatId = 0 — ещё не отправлено. */
    record VoiceMeta(long id, UUID owner, long chatId, int codec, int durationMs) {
        VoiceRef ref() {
            return new VoiceRef(id, durationMs);
        }
    }

    private final Database db;
    private final String voices;

    VoiceRepository(Database db) {
        this.db = db;
        this.voices = db.table("voices");
    }

    private Connection c() throws SQLException {
        return db.connection();
    }

    long insert(UUID owner, int codec, int durationMs, byte[] data, long now) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("INSERT INTO " + voices
                + " (owner, chat_id, codec, duration_ms, size, data, created_at) VALUES (?, 0, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, owner.toString());
            ps.setInt(2, codec);
            ps.setInt(3, durationMs);
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

    VoiceMeta meta(long id) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT id, owner, chat_id, codec, duration_ms FROM " + voices + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next()
                        ? new VoiceMeta(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getLong(3), rs.getInt(4), rs.getInt(5))
                        : null;
            }
        }
    }

    byte[] data(long id) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT data FROM " + voices + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    Map<Long, VoiceRef> refs(Collection<Long> ids) throws SQLException {
        Map<Long, VoiceRef> result = new HashMap<>();
        Set<Long> wanted = new LinkedHashSet<>(ids);
        wanted.remove(0L);
        if (wanted.isEmpty()) {
            return result;
        }
        String marks = String.join(",", java.util.Collections.nCopies(wanted.size(), "?"));
        try (PreparedStatement ps = c().prepareStatement("SELECT id, duration_ms FROM " + voices + " WHERE id IN (" + marks + ")")) {
            int i = 1;
            for (long id : wanted) {
                ps.setLong(i++, id);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getLong(1), new VoiceRef(rs.getLong(1), rs.getInt(2)));
                }
            }
        }
        return result;
    }

    void attach(long id, long chatId) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("UPDATE " + voices + " SET chat_id = ? WHERE id = ?")) {
            ps.setLong(1, chatId);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    void delete(long id) throws SQLException {
        if (id <= 0) {
            return;
        }
        try (PreparedStatement ps = c().prepareStatement("DELETE FROM " + voices + " WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    int deleteUnused(long olderThan) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("DELETE FROM " + voices + " WHERE chat_id = 0 AND created_at < ?")) {
            ps.setLong(1, olderThan);
            return ps.executeUpdate();
        }
    }
}
