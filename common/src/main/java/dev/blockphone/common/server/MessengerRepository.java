package dev.blockphone.common.server;

import dev.blockphone.common.protocol.data.MessengerData.ChatType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** SQL-запросы мессенджера. Только из потока мессенджера. */
public final class MessengerRepository {
    public record ChatRow(long id, ChatType type, String title, UUID owner, long createdAt) {
    }

    /** imageId = 0 — без фото. */
    public record MessageRow(long id, long chatId, UUID sender, String text, long imageId, long sentAt, long editedAt,
                             boolean deleted) {
    }

    private final Database db;
    private final UserRepository userRepo;
    private final String chats;
    private final String members;
    private final String messages;

    public MessengerRepository(Database db, UserRepository userRepo) {
        this.db = db;
        this.userRepo = userRepo;
        this.chats = db.table("chats");
        this.members = db.table("members");
        this.messages = db.table("messages");
    }

    private Connection c() throws SQLException {
        return db.connection();
    }

    // --- Игроки (для совместимости: отладка и тесты обращаются через репозиторий мессенджера) ---

    public void upsertUser(UUID uuid, String name, long now) throws SQLException {
        userRepo.upsertUser(uuid, name, now);
    }

    public boolean userExists(UUID uuid) throws SQLException {
        return userRepo.userExists(uuid);
    }

    public Map<UUID, String> userNames(Collection<UUID> uuids) throws SQLException {
        return userRepo.userNames(uuids);
    }

    public Map<UUID, String> searchUsers(String prefix, int limit) throws SQLException {
        return userRepo.searchUsers(prefix, limit);
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

    /** Удаляет чат вместе с участниками, сообщениями и их фото. */
    public void deleteChat(long chatId) throws SQLException {
        for (String sql : new String[]{
                "DELETE FROM " + db.table("images") + " WHERE kind = " + ImageRepository.KIND_MESSAGE + " AND ref_id = ?",
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
                rs.getString("text"), rs.getLong("image_id"), rs.getLong("sent_at"), rs.getLong("edited_at"), rs.getBoolean("deleted"));
    }

    public long insertMessage(long chatId, UUID sender, String text, long now) throws SQLException {
        return insertMessage(chatId, sender, text, 0, now);
    }

    public long insertMessage(long chatId, UUID sender, String text, long imageId, long now) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement("INSERT INTO " + messages
                + " (chat_id, sender, text, image_id, sent_at) VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, chatId);
            ps.setString(2, sender.toString());
            ps.setString(3, text);
            ps.setLong(4, imageId);
            ps.setLong(5, now);
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
        try (PreparedStatement ps = c().prepareStatement("UPDATE " + messages + " SET text = '', image_id = 0, deleted = TRUE WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }
}
