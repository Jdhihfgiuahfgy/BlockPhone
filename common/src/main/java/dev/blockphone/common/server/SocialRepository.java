package dev.blockphone.common.server;

import dev.blockphone.common.protocol.data.SocialData.FeedKind;
import dev.blockphone.common.protocol.data.SocialData.ReportTarget;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** SQL-запросы ленты: профили, подписки, паблики, посты, лайки, комментарии, жалобы, заглушения. Только из потока {@link Backend}. */
public final class SocialRepository {
    /** Пост с подсчётами для конкретного зрителя. */
    public record PostRow(long id, UUID author, long publicId, String text, long imageId, long createdAt, boolean deleted,
                          int likes, int comments, boolean liked) {
    }

    public record CommentRow(long id, long postId, UUID author, String text, long createdAt, boolean deleted) {
    }

    public record PublicRow(long id, String title, String description, int color, UUID owner, long createdAt, long avatarImage) {
    }

    public record ReportRow(long id, UUID reporter, ReportTarget type, long targetId, UUID targetUser, String reason, long createdAt) {
    }

    @FunctionalInterface
    private interface Mapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final Database db;
    private final String profiles;
    private final String follows;
    private final String publics;
    private final String admins;
    private final String subscriptions;
    private final String posts;
    private final String likes;
    private final String comments;
    private final String reports;
    private final String mutes;
    private final String modlog;

    public SocialRepository(Database db) {
        this.db = db;
        this.profiles = db.table("profiles");
        this.follows = db.table("follows");
        this.publics = db.table("publics");
        this.admins = db.table("public_admins");
        this.subscriptions = db.table("subscriptions");
        this.posts = db.table("posts");
        this.likes = db.table("likes");
        this.comments = db.table("comments");
        this.reports = db.table("reports");
        this.mutes = db.table("mutes");
        this.modlog = db.table("modlog");
    }

    // --- Помощники ---

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
                case Boolean b -> ps.setBoolean(i + 1, b);
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

    private <T> T one(String sql, Mapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapper.map(rs) : null;
            }
        }
    }

    private int count(String sql, Object... params) throws SQLException {
        Integer value = one(sql, rs -> rs.getInt(1), params);
        return value != null ? value : 0;
    }

    private int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    private long insert(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return UUID.fromString(rs.getString(column));
    }

    private static String marks(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    // --- Профили ---

    public String bio(UUID uuid) throws SQLException {
        String bio = one("SELECT bio FROM " + profiles + " WHERE uuid = ?", rs -> rs.getString(1), uuid);
        return bio != null ? bio : "";
    }

    public void setBio(UUID uuid, String bio, long now) throws SQLException {
        update("INSERT INTO " + profiles + " (uuid, bio, updated_at) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE bio = VALUES(bio), updated_at = VALUES(updated_at)", uuid, bio, now);
    }

    /** Оформление страницы (фото баннера и аватара — id картинок). */
    public record LookRow(int avatarType, int pose, int background, int bannerStyle, long bannerImage, long avatarImage) {
    }

    public LookRow look(UUID uuid) throws SQLException {
        LookRow row = one("SELECT avatar_type, avatar_pose, avatar_bg, banner_style, banner_image, avatar_image FROM " + profiles
                        + " WHERE uuid = ?",
                rs -> new LookRow(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getLong(5), rs.getLong(6)), uuid);
        return row != null ? row : new LookRow(0, 0, 0, 0, 0, 0);
    }

    public void setPublicAvatar(long id, long imageId) throws SQLException {
        update("UPDATE " + publics + " SET avatar_image = ? WHERE id = ?", imageId, id);
    }

    public void setLook(UUID uuid, LookRow look, long now) throws SQLException {
        update("INSERT INTO " + profiles + " (uuid, bio, updated_at, avatar_type, avatar_pose, avatar_bg, banner_style, banner_image, "
                        + "avatar_image) VALUES (?, '', ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE avatar_type = VALUES(avatar_type), "
                        + "avatar_pose = VALUES(avatar_pose), avatar_bg = VALUES(avatar_bg), banner_style = VALUES(banner_style), "
                        + "banner_image = VALUES(banner_image), avatar_image = VALUES(avatar_image), updated_at = VALUES(updated_at)",
                uuid, now, look.avatarType(), look.pose(), look.background(), look.bannerStyle(), look.bannerImage(), look.avatarImage());
    }

    // --- Подписки на игроков ---

    /** @return true — подписка новая. */
    public boolean follow(UUID follower, UUID target, long now) throws SQLException {
        return update("INSERT IGNORE INTO " + follows + " (follower, target, created_at) VALUES (?, ?, ?)", follower, target, now) > 0;
    }

    public void unfollow(UUID follower, UUID target) throws SQLException {
        update("DELETE FROM " + follows + " WHERE follower = ? AND target = ?", follower, target);
    }

    public boolean isFollowing(UUID follower, UUID target) throws SQLException {
        return count("SELECT COUNT(*) FROM " + follows + " WHERE follower = ? AND target = ?", follower, target) > 0;
    }

    public int followerCount(UUID uuid) throws SQLException {
        return count("SELECT COUNT(*) FROM " + follows + " WHERE target = ?", uuid);
    }

    public int followingCount(UUID uuid) throws SQLException {
        return count("SELECT COUNT(*) FROM " + follows + " WHERE follower = ?", uuid);
    }

    /** Подписчики (новые первыми). */
    public List<UUID> followers(UUID uuid, int limit) throws SQLException {
        return list("SELECT follower FROM " + follows + " WHERE target = ? ORDER BY created_at DESC LIMIT ?",
                rs -> uuid(rs, "follower"), uuid, limit);
    }

    /** На кого подписан (новые первыми). */
    public List<UUID> following(UUID uuid, int limit) throws SQLException {
        return list("SELECT target FROM " + follows + " WHERE follower = ? ORDER BY created_at DESC LIMIT ?",
                rs -> uuid(rs, "target"), uuid, limit);
    }

    // --- Паблики ---

    private static PublicRow publicRow(ResultSet rs) throws SQLException {
        return new PublicRow(rs.getLong("id"), rs.getString("title"), rs.getString("description"), rs.getInt("color"),
                uuid(rs, "owner"), rs.getLong("created_at"), rs.getLong("avatar_image"));
    }

    public long createPublic(String title, String description, int color, UUID owner, long now) throws SQLException {
        return insert("INSERT INTO " + publics + " (title, title_lower, description, color, owner, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                title, title.toLowerCase(Locale.ROOT), description, color, owner, now);
    }

    public PublicRow publicRow(long id) throws SQLException {
        return one("SELECT * FROM " + publics + " WHERE id = ?", SocialRepository::publicRow, id);
    }

    /** Паблик с таким названием (без учёта регистра), кроме exceptId. */
    public boolean titleTaken(String title, long exceptId) throws SQLException {
        return count("SELECT COUNT(*) FROM " + publics + " WHERE title_lower = ? AND id <> ?", title.toLowerCase(Locale.ROOT), exceptId) > 0;
    }

    public int ownedPublicCount(UUID owner) throws SQLException {
        return count("SELECT COUNT(*) FROM " + publics + " WHERE owner = ?", owner);
    }

    public void updatePublic(long id, String title, String description) throws SQLException {
        update("UPDATE " + publics + " SET title = ?, title_lower = ?, description = ? WHERE id = ?",
                title, title.toLowerCase(Locale.ROOT), description, id);
    }

    /** Удаляет паблик с администраторами и подписками (посты помечает удалёнными). Возвращает id фото его постов. */
    public List<Long> deletePublic(long id) throws SQLException {
        List<Long> images = list("SELECT image_id FROM " + posts + " WHERE public_id = ? AND image_id > 0", rs -> rs.getLong(1), id);
        update("UPDATE " + posts + " SET deleted = TRUE, image_id = 0 WHERE public_id = ?", id);
        update("DELETE FROM " + admins + " WHERE public_id = ?", id);
        update("DELETE FROM " + subscriptions + " WHERE public_id = ?", id);
        update("DELETE FROM " + publics + " WHERE id = ?", id);
        return images;
    }

    /** Администраторы паблика (без владельца), по порядку назначения. */
    public List<UUID> admins(long publicId) throws SQLException {
        return list("SELECT uuid FROM " + admins + " WHERE public_id = ? ORDER BY added_at", rs -> uuid(rs, "uuid"), publicId);
    }

    public boolean isAdmin(long publicId, UUID uuid) throws SQLException {
        return count("SELECT COUNT(*) FROM " + admins + " WHERE public_id = ? AND uuid = ?", publicId, uuid) > 0;
    }

    public void addAdmin(long publicId, UUID uuid, long now) throws SQLException {
        update("INSERT IGNORE INTO " + admins + " (public_id, uuid, added_at) VALUES (?, ?, ?)", publicId, uuid, now);
    }

    public void removeAdmin(long publicId, UUID uuid) throws SQLException {
        update("DELETE FROM " + admins + " WHERE public_id = ? AND uuid = ?", publicId, uuid);
    }

    public void subscribe(long publicId, UUID uuid, long now) throws SQLException {
        update("INSERT IGNORE INTO " + subscriptions + " (public_id, uuid, created_at) VALUES (?, ?, ?)", publicId, uuid, now);
    }

    public void unsubscribe(long publicId, UUID uuid) throws SQLException {
        update("DELETE FROM " + subscriptions + " WHERE public_id = ? AND uuid = ?", publicId, uuid);
    }

    public boolean isSubscribed(long publicId, UUID uuid) throws SQLException {
        return count("SELECT COUNT(*) FROM " + subscriptions + " WHERE public_id = ? AND uuid = ?", publicId, uuid) > 0;
    }

    public int subscriberCount(long publicId) throws SQLException {
        return count("SELECT COUNT(*) FROM " + subscriptions + " WHERE public_id = ?", publicId);
    }

    public List<UUID> subscribers(long publicId) throws SQLException {
        return list("SELECT uuid FROM " + subscriptions + " WHERE public_id = ?", rs -> uuid(rs, "uuid"), publicId);
    }

    public int publicPostCount(long publicId) throws SQLException {
        return count("SELECT COUNT(*) FROM " + posts + " WHERE public_id = ? AND deleted = FALSE", publicId);
    }

    public List<PublicRow> subscribedPublics(UUID uuid) throws SQLException {
        return list("SELECT p.* FROM " + publics + " p JOIN " + subscriptions + " s ON s.public_id = p.id WHERE s.uuid = ? "
                + "ORDER BY s.created_at DESC", SocialRepository::publicRow, uuid);
    }

    /** Паблики, где игрок владелец или администратор. */
    public List<PublicRow> managedPublics(UUID uuid) throws SQLException {
        return list("SELECT * FROM " + publics + " WHERE owner = ? OR id IN (SELECT public_id FROM " + admins + " WHERE uuid = ?) "
                + "ORDER BY created_at", SocialRepository::publicRow, uuid, uuid);
    }

    /** Самые популярные (по подписчикам). */
    public List<PublicRow> popularPublics(int limit) throws SQLException {
        return list("SELECT p.* FROM " + publics + " p ORDER BY (SELECT COUNT(*) FROM " + subscriptions
                + " s WHERE s.public_id = p.id) DESC, p.id DESC LIMIT ?", SocialRepository::publicRow, limit);
    }

    /** Паблики, в названии которых есть query (без учёта регистра). */
    public List<PublicRow> searchPublics(String query, int limit) throws SQLException {
        return list("SELECT * FROM " + publics + " WHERE title_lower LIKE ? ORDER BY id DESC LIMIT ?", SocialRepository::publicRow,
                "%" + UserRepository.escapeLike(query.toLowerCase(Locale.ROOT)) + "%", limit);
    }

    public List<PublicRow> publicRows(Collection<Long> ids) throws SQLException {
        Set<Long> wanted = new LinkedHashSet<>(ids);
        wanted.remove(0L);
        if (wanted.isEmpty()) {
            return List.of();
        }
        return list("SELECT * FROM " + publics + " WHERE id IN (" + marks(wanted.size()) + ")", SocialRepository::publicRow,
                wanted.toArray());
    }

    // --- Посты ---

    private String postSelect() {
        return "SELECT p.id, p.author, p.public_id, p.text, p.image_id, p.created_at, p.deleted,"
                + " (SELECT COUNT(*) FROM " + likes + " l WHERE l.post_id = p.id) AS like_count,"
                + " (SELECT COUNT(*) FROM " + comments + " c WHERE c.post_id = p.id AND c.deleted = FALSE) AS comment_count,"
                + " (SELECT COUNT(*) FROM " + likes + " l2 WHERE l2.post_id = p.id AND l2.uuid = ?) AS liked"
                + " FROM " + posts + " p";
    }

    private static PostRow postRow(ResultSet rs) throws SQLException {
        return new PostRow(rs.getLong("id"), uuid(rs, "author"), rs.getLong("public_id"), rs.getString("text"), rs.getLong("image_id"),
                rs.getLong("created_at"), rs.getBoolean("deleted"), rs.getInt("like_count"), rs.getInt("comment_count"),
                rs.getInt("liked") > 0);
    }

    public long insertPost(UUID author, long publicId, String text, long imageId, long now) throws SQLException {
        return insert("INSERT INTO " + posts + " (author, public_id, text, image_id, created_at) VALUES (?, ?, ?, ?, ?)",
                author, publicId, text, imageId, now);
    }

    /** Пост (в том числе удалённый) с подсчётами для viewer. */
    public PostRow post(long id, UUID viewer) throws SQLException {
        return one(postSelect() + " WHERE p.id = ?", SocialRepository::postRow, viewer, id);
    }

    public void deletePost(long id) throws SQLException {
        update("UPDATE " + posts + " SET deleted = TRUE, image_id = 0 WHERE id = ?", id);
    }

    /**
     * Страница ленты (от новых к старым): limit постов с id меньше beforeId (0 — самые новые).
     */
    public List<PostRow> feed(FeedKind kind, UUID viewer, UUID user, long publicId, long beforeId, int limit) throws SQLException {
        List<Object> params = new ArrayList<>();
        params.add(viewer);
        params.add(beforeId <= 0 ? Long.MAX_VALUE : beforeId);
        String condition = switch (kind) {
            case FOLLOWING -> {
                params.add(viewer);
                params.add(viewer);
                params.add(viewer);
                yield "((p.public_id = 0 AND (p.author = ? OR p.author IN (SELECT f.target FROM " + follows + " f WHERE f.follower = ?)))"
                        + " OR p.public_id IN (SELECT s.public_id FROM " + subscriptions + " s WHERE s.uuid = ?))";
            }
            case ALL -> "1 = 1";
            case USER -> {
                params.add(user);
                yield "p.public_id = 0 AND p.author = ?";
            }
            case PUBLIC -> {
                params.add(publicId);
                yield "p.public_id = ?";
            }
        };
        params.add(limit);
        return list(postSelect() + " WHERE p.deleted = FALSE AND p.id < ? AND " + condition + " ORDER BY p.id DESC LIMIT ?",
                SocialRepository::postRow, params.toArray());
    }

    /** Личные посты игрока. */
    public int userPostCount(UUID uuid) throws SQLException {
        return count("SELECT COUNT(*) FROM " + posts + " WHERE author = ? AND public_id = 0 AND deleted = FALSE", uuid);
    }

    // --- Лайки ---

    /** @return true — лайк новый. */
    public boolean like(long postId, UUID uuid, long now) throws SQLException {
        return update("INSERT IGNORE INTO " + likes + " (post_id, uuid, created_at) VALUES (?, ?, ?)", postId, uuid, now) > 0;
    }

    public void unlike(long postId, UUID uuid) throws SQLException {
        update("DELETE FROM " + likes + " WHERE post_id = ? AND uuid = ?", postId, uuid);
    }

    // --- Комментарии ---

    private static CommentRow commentRow(ResultSet rs) throws SQLException {
        return new CommentRow(rs.getLong("id"), rs.getLong("post_id"), uuid(rs, "author"), rs.getString("text"), rs.getLong("created_at"),
                rs.getBoolean("deleted"));
    }

    public long insertComment(long postId, UUID author, String text, long now) throws SQLException {
        return insert("INSERT INTO " + comments + " (post_id, author, text, created_at) VALUES (?, ?, ?, ?)", postId, author, text, now);
    }

    public CommentRow comment(long id) throws SQLException {
        return one("SELECT * FROM " + comments + " WHERE id = ?", SocialRepository::commentRow, id);
    }

    public void deleteComment(long id) throws SQLException {
        update("UPDATE " + comments + " SET deleted = TRUE WHERE id = ?", id);
    }

    /** До limit неудалённых комментариев старше beforeId (0 — самые новые), от новых к старым. */
    public List<CommentRow> comments(long postId, long beforeId, int limit) throws SQLException {
        return list("SELECT * FROM " + comments + " WHERE post_id = ? AND deleted = FALSE AND id < ? ORDER BY id DESC LIMIT ?",
                SocialRepository::commentRow, postId, beforeId <= 0 ? Long.MAX_VALUE : beforeId, limit);
    }

    // --- Жалобы ---

    private static ReportRow reportRow(ResultSet rs) throws SQLException {
        ReportTarget[] types = ReportTarget.values();
        int type = Math.max(0, Math.min(types.length - 1, rs.getInt("target_type")));
        return new ReportRow(rs.getLong("id"), uuid(rs, "reporter"), types[type], rs.getLong("target_id"), uuid(rs, "target_user"),
                rs.getString("reason"), rs.getLong("created_at"));
    }

    public boolean hasOpenReport(UUID reporter, ReportTarget type, long targetId, UUID targetUser) throws SQLException {
        return count("SELECT COUNT(*) FROM " + reports + " WHERE reporter = ? AND target_type = ? AND target_id = ? AND target_user = ?"
                + " AND resolution = 0", reporter, type.ordinal(), targetId, targetUser) > 0;
    }

    public long insertReport(UUID reporter, ReportTarget type, long targetId, UUID targetUser, String reason, long now) throws SQLException {
        return insert("INSERT INTO " + reports + " (reporter, target_type, target_id, target_user, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                reporter, type.ordinal(), targetId, targetUser, reason, now);
    }

    public ReportRow report(long id) throws SQLException {
        return one("SELECT * FROM " + reports + " WHERE id = ? AND resolution = 0", SocialRepository::reportRow, id);
    }

    public List<ReportRow> openReports(int limit) throws SQLException {
        return list("SELECT * FROM " + reports + " WHERE resolution = 0 ORDER BY id LIMIT ?", SocialRepository::reportRow, limit);
    }

    public int openReportCount() throws SQLException {
        return count("SELECT COUNT(*) FROM " + reports + " WHERE resolution = 0");
    }

    /** Закрыть все открытые жалобы на объект. resolution: 1 — отклонена, 2 — контент удалён. */
    public void resolveReports(ReportTarget type, long targetId, UUID targetUser, int resolution, UUID by, long now) throws SQLException {
        update("UPDATE " + reports + " SET resolution = ?, resolved_by = ?, resolved_at = ? WHERE target_type = ? AND target_id = ?"
                + " AND target_user = ? AND resolution = 0", resolution, by, now, type.ordinal(), targetId, targetUser);
    }

    // --- Заглушения и журнал модерации ---

    /** До какого времени игрок заглушён (0 — не заглушён или срок истёк). */
    public long mutedUntil(UUID uuid, long now) throws SQLException {
        Long until = one("SELECT until_at FROM " + mutes + " WHERE uuid = ?", rs -> rs.getLong(1), uuid);
        return until != null && until > now ? until : 0;
    }

    public void mute(UUID uuid, long until, UUID by, long now) throws SQLException {
        update("INSERT INTO " + mutes + " (uuid, until_at, by_uuid, created_at) VALUES (?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE until_at = VALUES(until_at), by_uuid = VALUES(by_uuid), created_at = VALUES(created_at)",
                uuid, until, by, now);
    }

    public void unmute(UUID uuid) throws SQLException {
        update("DELETE FROM " + mutes + " WHERE uuid = ?", uuid);
    }

    public void log(UUID moderator, String action, String target, String details, long now) throws SQLException {
        update("INSERT INTO " + modlog + " (moderator, action, target, details, created_at) VALUES (?, ?, ?, ?, ?)",
                moderator, action, target, details.length() > 255 ? details.substring(0, 255) : details, now);
    }
}
