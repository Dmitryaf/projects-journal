package io.github.dmitryaf.controlcenter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

import org.sqlite.SQLiteConfig;
import org.springframework.stereotype.Component;

@Component
final class MvpStore {

    private final Path database;

    MvpStore() throws IOException, SQLException {
        var directory = Path.of(System.getenv().getOrDefault("CONTROL_CENTER_DATA_DIR", ".data"));
        database = directory.toAbsolutePath().normalize().resolve("control-center.sqlite");
        Files.createDirectories(database.getParent());
        try (var connection = open(); var statement = connection.createStatement()) {
            try (var version = statement.executeQuery("PRAGMA user_version")) {
                if (version.next() && version.getInt(1) > 1) {
                    throw new IllegalStateException("База создана более новой версией Control Center.");
                }
            }
            statement.execute("PRAGMA journal_mode=WAL");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS settings (id INTEGER PRIMARY KEY CHECK(id=1), data TEXT NOT NULL)
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS projects (
                      id TEXT PRIMARY KEY, path TEXT NOT NULL UNIQUE, snapshot TEXT NOT NULL,
                      metadata TEXT, notes TEXT NOT NULL DEFAULT '', available INTEGER NOT NULL DEFAULT 1
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS records (
                      id TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('tasks','ideas','decisions')),
                      project_id TEXT REFERENCES projects(id), data TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                    )
                    """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS records_kind ON records(kind)");
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS relations (
                      id TEXT PRIMARY KEY, sourceId TEXT NOT NULL REFERENCES projects(id),
                      targetId TEXT NOT NULL REFERENCES projects(id), type TEXT NOT NULL,
                      UNIQUE(sourceId,targetId,type), CHECK(sourceId!=targetId)
                    )
                    """);
            statement.execute("PRAGMA user_version=1");
        }
    }

    Connection open() throws SQLException {
        var configuration = new SQLiteConfig();
        configuration.enforceForeignKeys(true);
        configuration.setBusyTimeout(5_000);
        return configuration.createConnection("jdbc:sqlite:" + database.toUri().toASCIIString());
    }

    <T> T write(Write<T> action) throws SQLException {
        try (var connection = open(); var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                T result = action.run(connection);
                statement.execute("COMMIT");
                return result;
            } catch (SQLException | RuntimeException error) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
                throw error;
            }
        }
    }

    interface Write<T> {
        T run(Connection connection) throws SQLException;
    }
}
