package dev.blockphone.common.server;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Одно JDBC-подключение к H2 (в режиме совместимости с MySQL) или к MySQL/MariaDB.
 * SQL пишется в диалекте MySQL — он одинаково работает в обоих случаях.
 * Используется только из одного потока (фонового потока BlockPhone, см. {@link Backend}), поэтому без пула соединений.
 */
public final class Database implements AutoCloseable {
    private final ServerSettings.Storage settings;
    private Connection connection;

    public Database(ServerSettings.Storage settings) {
        this.settings = settings;
    }

    public String table(String name) {
        return settings.tablePrefix() + name;
    }

    /** Подключение (переподключается, если соединение с MySQL оборвалось). */
    public Connection connection() throws SQLException {
        if (connection == null || !connection.isValid(2)) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                }
            }
            connection = open();
        }
        return connection;
    }

    private Connection open() throws SQLException {
        String driverClass;
        String url;
        Properties props = new Properties();
        if (settings.isMysql()) {
            driverClass = "org.mariadb.jdbc.Driver";
            url = "jdbc:mariadb://" + settings.host() + ":" + settings.port() + "/" + settings.database();
            props.setProperty("user", settings.user());
            props.setProperty("password", settings.password());
        } else {
            driverClass = "org.h2.Driver";
            String path = settings.h2File().toAbsolutePath().toString().replace('\\', '/');
            url = "jdbc:h2:file:" + path + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        }
        // Драйвер создаётся напрямую: у загрузчиков модов/плагинов DriverManager часто не видит драйверы.
        Driver driver;
        try {
            driver = (Driver) Class.forName(driverClass).getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new SQLException("JDBC driver not found: " + driverClass, e);
        }
        Connection result = driver.connect(url, props);
        if (result == null) {
            throw new SQLException("Driver rejected URL " + url);
        }
        result.setAutoCommit(true);
        return result;
    }

    /** Создаёт таблицы, если их нет. */
    public void migrate() throws SQLException {
        try (Statement st = connection().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("users") + " ("
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                    + "name VARCHAR(16) NOT NULL,"
                    + "name_lower VARCHAR(16) NOT NULL,"
                    + "last_seen BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("chats") + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "type INT NOT NULL,"
                    + "title VARCHAR(64) NOT NULL,"
                    + "owner CHAR(36) NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "direct_key VARCHAR(80) NULL UNIQUE)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("members") + " ("
                    + "chat_id BIGINT NOT NULL,"
                    + "uuid CHAR(36) NOT NULL,"
                    + "joined_at BIGINT NOT NULL,"
                    + "last_read BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (chat_id, uuid))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("messages") + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "chat_id BIGINT NOT NULL,"
                    + "sender CHAR(36) NOT NULL,"
                    + "text VARCHAR(4000) NOT NULL,"
                    + "sent_at BIGINT NOT NULL,"
                    + "edited_at BIGINT NOT NULL DEFAULT 0,"
                    + "deleted BOOLEAN NOT NULL DEFAULT FALSE)");
        }
        index("idx_users_name", "users", "name_lower");
        index("idx_members_uuid", "members", "uuid");
        index("idx_messages_chat", "messages", "chat_id, id");
        // Этап 4: фото в сообщениях.
        column("messages", "image_id BIGINT NOT NULL DEFAULT 0");
        // Этап 6: голосовые сообщения.
        column("messages", "voice_id BIGINT NOT NULL DEFAULT 0");
        migrateSocial();
    }

    /** Таблицы ленты, фото и служебные. */
    private void migrateSocial() throws SQLException {
        try (Statement st = connection().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("meta") + " ("
                    + "k VARCHAR(64) NOT NULL PRIMARY KEY,"
                    + "v VARCHAR(255) NOT NULL)");
            // kind: 0 — ещё не использовано, 1 — пост (ref_id = id поста), 2 — сообщение (ref_id = id чата).
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("images") + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "owner CHAR(36) NOT NULL,"
                    + "kind INT NOT NULL DEFAULT 0,"
                    + "ref_id BIGINT NOT NULL DEFAULT 0,"
                    + "width INT NOT NULL,"
                    + "height INT NOT NULL,"
                    + "size INT NOT NULL,"
                    + "data MEDIUMBLOB NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            // chat_id = 0 — ещё не отправлено (удаляется через сутки).
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("voices") + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "owner CHAR(36) NOT NULL,"
                    + "chat_id BIGINT NOT NULL DEFAULT 0,"
                    + "codec INT NOT NULL,"
                    + "duration_ms INT NOT NULL,"
                    + "size INT NOT NULL,"
                    + "data MEDIUMBLOB NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("profiles") + " ("
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                    + "bio VARCHAR(800) NOT NULL,"
                    + "updated_at BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("follows") + " ("
                    + "follower CHAR(36) NOT NULL,"
                    + "target CHAR(36) NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (follower, target))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("publics") + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "title VARCHAR(128) NOT NULL,"
                    + "title_lower VARCHAR(128) NOT NULL,"
                    + "description VARCHAR(1200) NOT NULL,"
                    + "color INT NOT NULL,"
                    + "owner CHAR(36) NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("public_admins") + " ("
                    + "public_id BIGINT NOT NULL,"
                    + "uuid CHAR(36) NOT NULL,"
                    + "added_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (public_id, uuid))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("subscriptions") + " ("
                    + "public_id BIGINT NOT NULL,"
                    + "uuid CHAR(36) NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (public_id, uuid))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("posts") + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "author CHAR(36) NOT NULL,"
                    + "public_id BIGINT NOT NULL DEFAULT 0,"
                    + "text VARCHAR(8000) NOT NULL,"
                    + "image_id BIGINT NOT NULL DEFAULT 0,"
                    + "created_at BIGINT NOT NULL,"
                    + "deleted BOOLEAN NOT NULL DEFAULT FALSE)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("likes") + " ("
                    + "post_id BIGINT NOT NULL,"
                    + "uuid CHAR(36) NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (post_id, uuid))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("comments") + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "post_id BIGINT NOT NULL,"
                    + "author CHAR(36) NOT NULL,"
                    + "text VARCHAR(2000) NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "deleted BOOLEAN NOT NULL DEFAULT FALSE)");
            // target_type — SocialData.ReportTarget; resolution: 0 — открыта, 1 — отклонена, 2 — контент удалён.
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("reports") + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "reporter CHAR(36) NOT NULL,"
                    + "target_type INT NOT NULL,"
                    + "target_id BIGINT NOT NULL,"
                    + "target_user CHAR(36) NOT NULL,"
                    + "reason VARCHAR(64) NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "resolution INT NOT NULL DEFAULT 0,"
                    + "resolved_by CHAR(36) NULL,"
                    + "resolved_at BIGINT NOT NULL DEFAULT 0)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("mutes") + " ("
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                    + "until_at BIGINT NOT NULL,"
                    + "by_uuid CHAR(36) NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("modlog") + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "moderator CHAR(36) NOT NULL,"
                    + "action VARCHAR(32) NOT NULL,"
                    + "target VARCHAR(80) NOT NULL,"
                    + "details VARCHAR(255) NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
        }
        try (Statement st = connection().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("privacy") + " ("
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY,"
                    + "messages INT NOT NULL,"
                    + "voice INT NOT NULL,"
                    + "photos INT NOT NULL,"
                    + "invites INT NOT NULL,"
                    + "calls INT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("privacy_lists") + " ("
                    + "owner CHAR(36) NOT NULL,"
                    + "target CHAR(36) NOT NULL,"
                    + "kind INT NOT NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (owner, target))");
        }
        try (Statement st = connection().createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("sticker_packs") + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "owner CHAR(36) NOT NULL,"
                    + "title VARCHAR(128) NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("stickers") + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + "pack_id BIGINT NOT NULL,"
                    + "image_id BIGINT NOT NULL,"
                    + "position INT NOT NULL,"
                    + "created_at BIGINT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + table("sticker_installs") + " ("
                    + "uuid CHAR(36) NOT NULL,"
                    + "pack_id BIGINT NOT NULL,"
                    + "added_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (uuid, pack_id))");
        }
        column("messages", "sticker_id BIGINT NOT NULL DEFAULT 0");
        index("idx_stickers_pack", "stickers", "pack_id");
        index("idx_sticker_packs_owner", "sticker_packs", "owner");
        // Оформление личной страницы.
        column("profiles", "avatar_type INT NOT NULL DEFAULT 0");
        column("profiles", "avatar_pose INT NOT NULL DEFAULT 0");
        column("profiles", "avatar_bg INT NOT NULL DEFAULT 0");
        column("profiles", "banner_style INT NOT NULL DEFAULT 0");
        column("profiles", "banner_image BIGINT NOT NULL DEFAULT 0");
        index("idx_follows_target", "follows", "target");
        index("idx_publics_title", "publics", "title_lower");
        index("idx_subscriptions_uuid", "subscriptions", "uuid");
        index("idx_posts_public", "posts", "public_id, id");
        index("idx_posts_author", "posts", "author, id");
        index("idx_comments_post", "comments", "post_id, id");
        index("idx_reports_open", "reports", "resolution, id");
    }

    /** ALTER TABLE ... ADD COLUMN без IF NOT EXISTS (его нет в MySQL): ошибку «уже есть» игнорируем. */
    private void column(String tableName, String definition) {
        try (Statement st = connection().createStatement()) {
            st.executeUpdate("ALTER TABLE " + table(tableName) + " ADD COLUMN " + definition);
        } catch (SQLException ignored) {
            // Колонка уже есть.
        }
    }

    /** CREATE INDEX без IF NOT EXISTS (его нет в MySQL): ошибку «уже существует» игнорируем. */
    private void index(String name, String tableName, String columns) {
        try (Statement st = connection().createStatement()) {
            st.executeUpdate("CREATE INDEX " + settings.tablePrefix() + name + " ON " + table(tableName) + " (" + columns + ")");
        } catch (SQLException ignored) {
            // Индекс уже есть.
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
            connection = null;
        }
    }
}
