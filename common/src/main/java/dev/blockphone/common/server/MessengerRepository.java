package dev.blockphone.common.server;

import dev.blockphone.common.protocol.data.MessengerData.ChatType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** SQL-запросы мессенджера. Только из потока мессенджера. */
public final class MessengerRepository {
    public record ChatRow(long id, ChatType type, String title, UUID owner, long createdAt) {
    }

    public record MessageRow(long id, long chatId, UUID sender, String text, long sentAt, long editedAt, boolean deleted) {
    }

    private final Database db;
    private final String users;
    private final String chats;
    private final String members;
    private final String messages;

    public MessengerRepository(Database db) {
        this.db = db;
        this.users = db.table("users");
        this.chats = db.table("chats");
        this.members = db.table("members");
        this.messages = db.table("messages");
    }

    private Connection c() throws SQLException {
        return db.connection();
    }

    // --- Игроки ---

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
        Map<UUID, String> result = new java.util.LinkedHashMap<>();
        String escaped = prefix.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        try (PreparedStatement ps = c().prepareStatement("SELECT uuid, name FROM " + users
                + " WHERE name_lower LIKE ? ORDER BY last_seen DESC LIMIT ?")) {
            ps.setString(1, escaped + "%");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                }
            }
        }
        return result;
    }

    // --- Чаты ---

    private static ChatRow chatRow(ResultSet rs) throws SQLException {
        return new ChatRow(rs.getLong("id"), ChatType.byId(rs.getInt("type")), rs.getString("title"),
                UUID.fromString(rs.getString("owner")), rs.getLong("created_at"));
    }

    public ChatRow chat(long id) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT * FROM " + chats + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? chatRow(rs) : null;
            }
        }
    }

    public Long findDirectChat(String key) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT id FROM " + chats + " WHERE direct_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    public long createChat(ChatType type, String title, UUID owner, String directKey, long now) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("INSERT INTO " + chats + " (type, title, owner, created_at, direct_key) VALUES (?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, type.ordinal());
            ps.setString(2, title);
            ps.setString(3, owner.toString());
            ps.setLong(4, now);
            ps.setString(5, directKey);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void renameChat(long chatId, String title) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("UPDATE " + chats + " SET title = ? WHERE id = ?")) {
            ps.setString(1, title);
            ps.setLong(2, chatId);
            ps.executeUpdate();
        }
    }

    public void setOwner(long chatId, UUID owner) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("UPDATE " + chats + " SET owner = ? WHERE id = ?")) {
            ps.setString(1, owner.toString());
            ps.setLong(2, chatId);
            ps.executeUpdate();
        }
    }

    /** Удаляет чат вместе с участниками и сообщениями. */
    public void deleteChat(long chatId) throws SQLException {
        for (String sql : new String[]{
                "DELETE FROM " + messages + " WHERE chat_id = ?",
                "DELETE FROM " + members + " WHERE chat_id = ?",
                "DELETE FROM " + chats + " WHERE id = ?"}) {
            try (PreparedStatement ps = c().prepareStatement(sql)) {
                ps.setLong(1, chatId);
                ps.executeUpdate();
            }
        }
    }

    /** Чаты игрока. */
    public List<ChatRow> chatsOf(UUID uuid) throws SQLException {
        List<ChatRow> result = new ArrayList<>();
        try (PreparedStatement ps = c().prepareStatement("SELECT c.* FROM " + chats + " c JOIN " + members
                + " m ON m.chat_id = c.id WHERE m.uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(chatRow(rs));
                }
            }
        }
        return result;
    }

    // --- Участники ---

    public void addMember(long chatId, UUID uuid, long now) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("INSERT IGNORE INTO " + members + " (chat_id, uuid, joined_at, last_read) VALUES (?, ?, ?, 0)")) {
            ps.setLong(1, chatId);
            ps.setString(2, uuid.toString());
            ps.setLong(3, now);
            ps.executeUpdate();
        }
    }

    public void removeMember(long chatId, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("DELETE FROM " + members + " WHERE chat_id = ? AND uuid = ?")) {
            ps.setLong(1, chatId);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** Участники в порядке вступления. */
    public List<UUID> members(long chatId) throws SQLException {
        List<UUID> result = new ArrayList<>();
        try (PreparedStatement ps = c().prepareStatement("SELECT uuid FROM " + members + " WHERE chat_id = ? ORDER BY joined_at, uuid")) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(UUID.fromString(rs.getString(1)));
                }
            }
        }
        return result;
    }

    public boolean isMember(long chatId, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT 1 FROM " + members + " WHERE chat_id = ? AND uuid = ?")) {
            ps.setLong(1, chatId);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void markRead(long chatId, UUID uuid, long messageId) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("UPDATE " + members + " SET last_read = GREATEST(last_read, ?) WHERE chat_id = ? AND uuid = ?")) {
            ps.setLong(1, messageId);
            ps.setLong(2, chatId);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** Непрочитанные (чужие, не удалённые) сообщения в чате. */
    public int unread(long chatId, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT COUNT(*) FROM " + messages + " msg JOIN " + members
                + " m ON m.chat_id = msg.chat_id AND m.uuid = ? WHERE msg.chat_id = ? AND msg.id > m.last_read"
                + " AND msg.sender <> ? AND msg.deleted = FALSE")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, chatId);
            ps.setString(3, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // --- Сообщения ---

    private static MessageRow messageRow(ResultSet rs) throws SQLException {
        return new MessageRow(rs.getLong("id"), rs.getLong("chat_id"), UUID.fromString(rs.getString("sender")),
                rs.getString("text"), rs.getLong("sent_at"), rs.getLong("edited_at"), rs.getBoolean("deleted"));
    }

    public long insertMessage(long chatId, UUID sender, String text, long now) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("INSERT INTO " + messages + " (chat_id, sender, text, sent_at) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, chatId);
            ps.setString(2, sender.toString());
            ps.setString(3, text);
            ps.setLong(4, now);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public MessageRow message(long id) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT * FROM " + messages + " WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? messageRow(rs) : null;
            }
        }
    }

    public MessageRow lastMessage(long chatId) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("SELECT * FROM " + messages + " WHERE chat_id = ? ORDER BY id DESC LIMIT 1")) {
            ps.setLong(1, chatId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? messageRow(rs) : null;
            }
        }
    }

    /** До limit сообщений старше beforeId (0 — самые новые), от новых к старым. */
    public List<MessageRow> history(long chatId, long beforeId, int limit) throws SQLException {
        List<MessageRow> result = new ArrayList<>();
        try (PreparedStatement ps = c().prepareStatement("SELECT * FROM " + messages + " WHERE chat_id = ? AND id < ? ORDER BY id DESC LIMIT ?")) {
            ps.setLong(1, chatId);
            ps.setLong(2, beforeId <= 0 ? Long.MAX_VALUE : beforeId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(messageRow(rs));
                }
            }
        }
        return result;
    }

    public void editMessage(long id, String text, long now) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("UPDATE " + messages + " SET text = ?, edited_at = ? WHERE id = ?")) {
            ps.setString(1, text);
            ps.setLong(2, now);
            ps.setLong(3, id);
            ps.executeUpdate();
        }
    }

    public void deleteMessage(long id) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("UPDATE " + messages + " SET text = '', deleted = TRUE WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }
}
