package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Map;
import java.util.UUID;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

public final class SqliteStore {

    private static final String DATABASE = "control-center.sqlite";

    private final Path path;

    private SqliteStore(Path path) {
        this.path = path;
    }

    public static SqliteStore fromEnvironment() {
        var directory = dataDirectory(System.getenv(), System.getProperty("os.name"), Path.of(System.getProperty("user.home")));
        var path = directory.toAbsolutePath().normalize().resolve(DATABASE);
        var store = new SqliteStore(path);
        try {
            store.initialize();
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Не удалось открыть базу приложения.", exception);
        }
        return store;
    }

    static Path dataDirectory(Map<String, String> environment, String platform, Path home) {
        String override = environment.get("CONTROL_CENTER_DATA_DIR");
        if (override != null && !override.isEmpty()) {
            return Path.of(override);
        }
        if (platform.startsWith("Windows")) {
            Path profile = environmentPath(environment, "USERPROFILE", home);
            // Packaged Windows apps can virtualize LocalAppData; the stable location is shared with the desktop.
            Path stable = profile.resolve(".control-center");
            Path local = environmentPath(environment, "LOCALAPPDATA", profile.resolve("AppData").resolve("Local"));
            Path legacy = local.resolve("ControlCenter").resolve(DATABASE);
            if (!Files.exists(stable.resolve(DATABASE)) && Files.exists(legacy)) {
                throw new IllegalStateException("Найдена прежняя база приложения в AppData. "
                        + "Перенесите её в %USERPROFILE%\\.control-center по инструкции Storage, backup and restore в SETUP.md. "
                        + "Пустая база не создана. Для явного выбора прежнего каталога используйте CONTROL_CENTER_DATA_DIR.");
            }
            return stable;
        }
        Path userHome = environmentPath(environment, "HOME", home);
        if (platform.startsWith("Mac") || platform.equals("Darwin")) {
            return userHome.resolve("Library").resolve("Application Support").resolve("ControlCenter");
        }
        return environmentPath(environment, "XDG_DATA_HOME", userHome.resolve(".local").resolve("share")).resolve("control-center");
    }

    private static Path environmentPath(Map<String, String> environment, String key, Path fallback) {
        String value = environment.get(key);
        return value == null || value.isEmpty() ? fallback : Path.of(value);
    }

    private void initialize() throws IOException, SQLException {
        boolean existing = Files.exists(path) && Files.size(path) > 0;
        Files.createDirectories(path.getParent());
        try (var connection = connect(path, true)) {
            int version = SqliteMigrations.supportedVersion(connection);
            try (var statement = connection.createStatement();
                    var journal = statement.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!journal.next() || !"wal".equalsIgnoreCase(journal.getString(1))) {
                    throw new SQLException("Не удалось включить WAL для базы приложения.");
                }
            }
            if (existing && version < SqliteMigrations.CURRENT_VERSION) {
                backup(SqliteMigrations.backupPrefix(version) + "-" + UUID.randomUUID() + ".sqlite");
            }
            write(connection, transaction -> {
                // HTTP and MCP may both start while the upgrade backup is being made.
                SqliteMigrations.upgrade(transaction, SqliteMigrations.supportedVersion(transaction));
                return null;
            });
        }
    }

    public Path path() {
        return path;
    }

    public Connection open() throws SQLException {
        return connect(path, false);
    }

    private static Connection connect(Path path, boolean create) throws SQLException {
        var config = new SQLiteConfig();
        if (!create) {
            config.resetOpenMode(SQLiteOpenMode.CREATE);
        }
        config.enforceForeignKeys(true);
        config.setBusyTimeout(5_000);
        return config.createConnection("jdbc:sqlite:" + path.toUri().toASCIIString());
    }

    @FunctionalInterface
    public interface Transaction<T> {
        T run(Connection connection) throws SQLException;
    }

    public <T> T write(Transaction<T> action) throws SQLException {
        try (var connection = open()) {
            return write(connection, action);
        }
    }

    private static <T> T write(Connection connection, Transaction<T> action) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                var result = action.run(connection);
                statement.execute("COMMIT");
                return result;
            } catch (SQLException | RuntimeException exception) {
                try {
                    statement.execute("ROLLBACK");
                } catch (SQLException rollback) {
                    exception.addSuppressed(rollback);
                }
                throw exception;
            }
        }
    }

    public Path backup() throws IOException, SQLException {
        var timestamp = new DateTimeFormatterBuilder().appendInstant(3).toFormatter()
                .format(Instant.now()).replace(':', '-').replace('.', '-');
        return backup("control-center-" + timestamp + "-" + UUID.randomUUID().toString().substring(0, 8) + ".sqlite");
    }

    void revokeRestoredApprovals() throws SQLException {
        write(SqliteStore::revokeApprovals);
    }

    private Path backup(String name) throws IOException, SQLException {
        var directory = Files.createDirectories(path.getParent().resolve("backups"));
        var destination = directory.resolve(name);
        var staging = Files.createTempFile(directory, ".snapshot-", ".tmp");
        try {
            // VACUUM snapshots committed WAL pages without copying the live sidecar files.
            try (var source = open(); var vacuum = source.prepareStatement("VACUUM INTO ?")) {
                vacuum.setString(1, staging.toString());
                vacuum.execute();
            }
            try (var copy = connect(staging, false)) {
                write(copy, SqliteStore::revokeApprovals);
                try (var statement = copy.createStatement()) {
                    statement.execute("PRAGMA journal_mode=DELETE");
                    try (var check = statement.executeQuery("PRAGMA quick_check")) {
                        if (!check.next() || !"ok".equals(check.getString(1))) {
                            throw new SQLException("Проверка резервной копии не пройдена.");
                        }
                    }
                }
            }
            return Files.move(staging, destination);
        } catch (IOException | SQLException | RuntimeException exception) {
            try {
                Files.deleteIfExists(staging);
            } catch (IOException cleanup) {
                exception.addSuppressed(cleanup);
            }
            throw exception;
        }
    }

    private static Void revokeApprovals(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            if (hasTable(connection, "codex_grants")) {
                statement.executeUpdate("UPDATE codex_grants SET enabled=0,revision=revision+1");
            }
            if (hasTable(connection, "analysis_schedules")) {
                statement.executeUpdate("""
                        UPDATE analysis_schedules SET enabled=0,revision=revision+1,
                        lease_owner=NULL,lease_until=NULL,note='После восстановления требуется новое согласие.'
                        """);
            }
            if (hasTable(connection, "analysis_runs")) {
                statement.executeUpdate("""
                        UPDATE analysis_runs SET status=CASE WHEN status='running' THEN 'unknown' ELSE 'cancelled' END,
                        error='Восстановленная копия не возобновляет отправку. Подготовьте новый пакет; прежний запрос мог быть оплачен.',
                        owner_id=NULL,lease_until=NULL WHERE status IN ('pending','running')
                        """);
            }
        }
        return null;
    }

    private static boolean hasTable(Connection connection, String name) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM sqlite_schema WHERE type='table' AND name=?")) {
            statement.setString(1, name);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }
}
