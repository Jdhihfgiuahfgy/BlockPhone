package dev.blockphone.common.server;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Одно JDBC-подключение к H2 (в режиме совместимости с MySQL) или к MySQL/MariaDB.
 * SQL пишется в диалекте MySQL — он одинаково работает в обоих случаях.
 * Используется только из одного потока (потока мессенджера), поэтому без пула соединений.
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
