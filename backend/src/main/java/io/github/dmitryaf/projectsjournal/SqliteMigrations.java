package io.github.dmitryaf.projectsjournal;

import java.sql.Connection;
import java.sql.SQLException;

final class SqliteMigrations {

    static final int CURRENT_VERSION = 16;

    private SqliteMigrations() {
    }

    static int supportedVersion(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("PRAGMA user_version")) {
            if (!rows.next()) {
                throw new SQLException("Не удалось прочитать версию базы приложения.");
            }
            int version = rows.getInt(1);
            if (version >= 0 && version <= CURRENT_VERSION) {
                return version;
            }
            throw new IllegalStateException("Версия базы не поддерживается этой сборкой приложения.");
        }
    }

    static String backupPrefix(int version) {
        return switch (version) {
            case 0, 1, 2, 3 -> "before-tasks-v4";
            case 4 -> "before-history-v5";
            case 5 -> "before-observations-v6";
            case 6 -> "before-reviews-v7";
            case 7 -> "before-analysis-v8";
            case 8 -> "before-schedules-v9";
            case 9 -> "before-proposals-v10";
            case 10 -> "before-codex-v11";
            case 11 -> "before-simple-tasks-v12";
            case 12 -> "before-project-lifecycle-v13";
            case 13 -> "before-task-cancellation-v14";
            case 14 -> "before-codex-schedules-v15";
            case 15 -> "before-task-projects-v16";
            default -> throw new IllegalArgumentException("Нет резервной копии для версии " + version);
        };
    }

    static void upgrade(Connection connection, int version) throws SQLException {
        initializeWorkspace(connection);
        initializeTaskHistory(connection, version);
        initializeReviews(connection, version);
        initializeAnalysis(connection);

        if (version < 12) {
            // Leave immutable snapshots and accepted proposals as recorded.
            execute(connection, """
                    UPDATE records SET data=json_remove(data,'$.state')
                    WHERE kind='tasks' AND json_type(data,'$.state') IS NOT NULL
                    """);
            if (hasColumn(connection, "projects", "decision_sources")) {
                execute(connection, "ALTER TABLE projects DROP COLUMN decision_sources");
            }
        }
        execute(connection, """
                CREATE TABLE IF NOT EXISTS project_lifecycle_events (
                  id TEXT PRIMARY KEY,
                  project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
                  scope_project_id TEXT NOT NULL,project_name TEXT NOT NULL,
                  revision INTEGER NOT NULL CHECK(revision>0),
                  action TEXT NOT NULL CHECK(action IN ('closed','reopened')),
                  outcome TEXT CHECK(outcome IN ('completed','stopped')),
                  reason TEXT NOT NULL,recorded_at TEXT NOT NULL,
                  request_key TEXT UNIQUE,request_hash TEXT,
                  UNIQUE(scope_project_id,revision)
                );
                CREATE INDEX IF NOT EXISTS project_lifecycle_time
                  ON project_lifecycle_events(recorded_at,scope_project_id);
                """);
        if (version < 14) {
            allowCancelledHistory(connection);
        }
        addColumn(connection, "analysis_schedules", "provider",
                "TEXT NOT NULL DEFAULT 'openai' CHECK(provider IN ('openai','codex'))");
        execute(connection, "PRAGMA user_version=" + CURRENT_VERSION);
    }

    private static void initializeWorkspace(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS settings (id INTEGER PRIMARY KEY CHECK(id=1), data TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS projects (
                  id TEXT PRIMARY KEY, path TEXT NOT NULL UNIQUE, snapshot TEXT NOT NULL,
                  metadata TEXT, notes TEXT NOT NULL DEFAULT '', available INTEGER NOT NULL DEFAULT 1,
                  yaml_base_hash TEXT
                );
                CREATE TABLE IF NOT EXISTS records (
                  id TEXT PRIMARY KEY, kind TEXT NOT NULL CHECK(kind IN ('tasks','ideas','decisions')),
                  project_id TEXT REFERENCES projects(id), data TEXT NOT NULL,
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS records_kind ON records(kind);
                CREATE TABLE IF NOT EXISTS relations (
                  id TEXT PRIMARY KEY, sourceId TEXT NOT NULL REFERENCES projects(id),
                  targetId TEXT NOT NULL REFERENCES projects(id), type TEXT NOT NULL,
                  UNIQUE(sourceId,targetId,type), CHECK(sourceId != targetId)
                );
                CREATE TABLE IF NOT EXISTS checks (
                  id TEXT PRIMARY KEY, project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
                  idea_id TEXT REFERENCES records(id) ON DELETE SET NULL, data TEXT NOT NULL,
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS check_entries (
                  id TEXT PRIMARY KEY, check_id TEXT NOT NULL REFERENCES checks(id) ON DELETE CASCADE,
                  data TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS checks_project ON checks(project_id);
                CREATE INDEX IF NOT EXISTS entries_check ON check_entries(check_id);
                """);
        addColumn(connection, "projects", "yaml_base_hash", "TEXT");
        addColumn(connection, "projects", "visibility",
                "TEXT NOT NULL DEFAULT 'unknown' CHECK(visibility IN ('unknown','private','public'))");
        addColumn(connection, "projects", "private_context_path", "TEXT");
        addColumn(connection, "projects", "private_context_had_records", "INTEGER NOT NULL DEFAULT 0");
        addColumn(connection, "projects", "publication_allowlist", "TEXT NOT NULL DEFAULT '[]'");
    }

    private static void initializeTaskHistory(Connection connection, int version) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS task_identity (
                  number INTEGER PRIMARY KEY AUTOINCREMENT,
                  record_id TEXT NOT NULL UNIQUE REFERENCES records(id) ON DELETE CASCADE,
                  revision INTEGER NOT NULL DEFAULT 1,
                  request_key TEXT UNIQUE, request_hash TEXT
                );
                INSERT INTO task_identity(record_id)
                  SELECT id FROM records WHERE kind='tasks' AND id NOT IN (SELECT record_id FROM task_identity)
                  ORDER BY created_at, id;
                CREATE TABLE IF NOT EXISTS task_history (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  task_id TEXT NOT NULL, revision INTEGER NOT NULL,
                  kind TEXT NOT NULL CHECK(kind IN ('baseline','result','completed','reopened')),
                  recorded_at TEXT NOT NULL,
                  project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
                  project_name TEXT, snapshot TEXT NOT NULL,
                  UNIQUE(task_id,revision)
                );
                CREATE INDEX IF NOT EXISTS task_history_project_time ON task_history(project_id,recorded_at);
                """);
        if (version < 5) {
            execute(connection, """
                    INSERT INTO task_history(task_id,revision,kind,recorded_at,project_id,project_name,snapshot)
                    SELECT r.id,t.revision,'baseline',r.updated_at,r.project_id,
                      COALESCE(json_extract(p.metadata,'$.name'),json_extract(p.snapshot,'$.yaml.metadata.name'),
                        json_extract(p.snapshot,'$.directoryName')),
                      json_set(r.data,'$.id',r.id,'$.projectId',r.project_id,'$.number',t.number,'$.code','CC-'||t.number,
                        '$.revision',t.revision,'$.createdAt',r.created_at,'$.updatedAt',r.updated_at)
                    FROM records r JOIN task_identity t ON t.record_id=r.id LEFT JOIN projects p ON p.id=r.project_id
                    WHERE r.kind='tasks' AND (json_extract(r.data,'$.result') IS NOT NULL
                      OR json_extract(r.data,'$.completedAt') IS NOT NULL)
                    ORDER BY r.updated_at,r.id;
                    """);
        }
    }

    private static void initializeReviews(Connection connection, int version) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS observations (
                  id TEXT PRIMARY KEY,
                  project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
                  scope_project_id TEXT, project_name TEXT,
                  occurred_on TEXT NOT NULL, body TEXT NOT NULL,
                  revision INTEGER NOT NULL CHECK(revision>0),
                  created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                  request_key TEXT UNIQUE, request_hash TEXT
                );
                CREATE INDEX IF NOT EXISTS observations_scope_date ON observations(scope_project_id,occurred_on);
                CREATE TABLE IF NOT EXISTS reviews (
                  id TEXT PRIMARY KEY,
                  project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
                  scope_key TEXT NOT NULL, project_name TEXT,
                  period_kind TEXT NOT NULL CHECK(period_kind IN ('week','month')),
                  period_start TEXT NOT NULL, period_end TEXT NOT NULL, timezone TEXT NOT NULL,
                  start_at TEXT NOT NULL, next_start_at TEXT NOT NULL, user_text TEXT NOT NULL,
                  revision INTEGER NOT NULL CHECK(revision>0), created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                  UNIQUE(scope_key,period_kind,period_start)
                );
                CREATE INDEX IF NOT EXISTS reviews_period ON reviews(period_start,period_end);
                """);
        if (version < 7) {
            execute(connection, """
                    UPDATE settings SET data=json_set(data,'$.timezone','Asia/Yekaterinburg')
                    WHERE json_type(data,'$.timezone') IS NULL
                    """);
        }
    }

    private static void initializeAnalysis(Connection connection) throws SQLException {
        execute(connection, """
                CREATE TABLE IF NOT EXISTS analysis_runs (
                  id TEXT PRIMARY KEY,review_id TEXT REFERENCES reviews(id) ON DELETE SET NULL,
                  review_key TEXT NOT NULL,scope_key TEXT NOT NULL,generation INTEGER NOT NULL,
                  status TEXT NOT NULL CHECK(status IN ('pending','running','succeeded','failed','unknown','cancelled')),
                  trigger TEXT NOT NULL DEFAULT 'manual' CHECK(trigger IN ('manual','scheduled')),approved_at TEXT,
                  model TEXT NOT NULL,policy_version TEXT NOT NULL,
                  selection_json TEXT NOT NULL,payload_json TEXT NOT NULL,guards_json TEXT NOT NULL,packet_hash TEXT NOT NULL,
                  request_key TEXT NOT NULL UNIQUE,request_hash TEXT NOT NULL,
                  created_at TEXT NOT NULL,expires_at TEXT NOT NULL,started_at TEXT,finished_at TEXT,
                  lease_until TEXT,owner_id TEXT,cancel_requested INTEGER NOT NULL DEFAULT 0,
                  report_json TEXT,raw_output TEXT,error TEXT,usage_json TEXT,response_id TEXT,
                  UNIQUE(review_key,generation)
                );
                CREATE INDEX IF NOT EXISTS analysis_review_time ON analysis_runs(review_key,created_at,id);
                CREATE TABLE IF NOT EXISTS analysis_schedules (
                  id TEXT PRIMARY KEY,scope_key TEXT NOT NULL,project_name TEXT,
                  period_kind TEXT NOT NULL,timezone TEXT NOT NULL,model TEXT NOT NULL,
                  policy_version TEXT NOT NULL,selection_json TEXT NOT NULL,access_json TEXT NOT NULL,
                  revision INTEGER NOT NULL,enabled INTEGER NOT NULL DEFAULT 0,
                  enabled_at TEXT NOT NULL,next_period_start TEXT NOT NULL,
                  lease_owner TEXT,lease_until TEXT,last_run_id TEXT,note TEXT,updated_at TEXT NOT NULL,
                  UNIQUE(scope_key,period_kind)
                );
                """);
        if (!hasColumn(connection, "analysis_runs", "schedule_id")) {
            execute(connection, """
                    ALTER TABLE analysis_runs ADD COLUMN schedule_id TEXT;
                    ALTER TABLE analysis_runs ADD COLUMN schedule_revision INTEGER;
                    ALTER TABLE analysis_runs ADD COLUMN automatic_key TEXT;
                    """);
        }
        execute(connection, """
                CREATE UNIQUE INDEX IF NOT EXISTS analysis_automatic_key
                  ON analysis_runs(automatic_key) WHERE automatic_key IS NOT NULL;
                CREATE TABLE IF NOT EXISTS analysis_schedule_periods (automatic_key TEXT PRIMARY KEY,run_key TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS analysis_acceptances (
                  id TEXT PRIMARY KEY,analysis_run_id TEXT REFERENCES analysis_runs(id) ON DELETE SET NULL,
                  run_key TEXT NOT NULL,proposal_index INTEGER NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('task','idea')),
                  result_id TEXT NOT NULL,approved_json TEXT NOT NULL,original_json TEXT NOT NULL,created_at TEXT NOT NULL,
                  UNIQUE(run_key,proposal_index)
                );
                CREATE TABLE IF NOT EXISTS codex_grants (
                  project_id TEXT PRIMARY KEY REFERENCES projects(id) ON DELETE CASCADE,
                  revision INTEGER NOT NULL,enabled INTEGER NOT NULL DEFAULT 0,access_stamp TEXT NOT NULL,
                  roots_json TEXT NOT NULL,updated_at TEXT NOT NULL,policy_version TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS codex_digests (
                  id TEXT PRIMARY KEY,project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
                  scope_project_id TEXT NOT NULL,project_name TEXT NOT NULL,
                  review_key TEXT NOT NULL,period_json TEXT NOT NULL,thread_id TEXT NOT NULL,
                  thread_name TEXT NOT NULL,thread_updated_at INTEGER NOT NULL,
                  summary TEXT NOT NULL,excerpts_json TEXT NOT NULL,source_hash TEXT NOT NULL,access_stamp TEXT NOT NULL,
                  grant_revision INTEGER NOT NULL,captured_at TEXT NOT NULL,checked_at TEXT NOT NULL,
                  stale INTEGER NOT NULL DEFAULT 0,revision INTEGER NOT NULL,
                  preview_id TEXT NOT NULL UNIQUE,request_hash TEXT NOT NULL,policy_version TEXT NOT NULL,
                  UNIQUE(review_key,thread_id)
                );
                """);
    }

    private static void allowCancelledHistory(Connection connection) throws SQLException {
        long sequence;
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT seq FROM sqlite_sequence WHERE name='task_history'")) {
            sequence = rows.next() ? rows.getLong(1) : 0;
        }
        execute(connection, """
                ALTER TABLE task_history RENAME TO task_history_before_cancel;
                CREATE TABLE task_history (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT NOT NULL,revision INTEGER NOT NULL,
                  kind TEXT NOT NULL CHECK(kind IN ('baseline','result','completed','cancelled','reopened')),
                  recorded_at TEXT NOT NULL,project_id TEXT REFERENCES projects(id) ON DELETE SET NULL,
                  project_name TEXT,snapshot TEXT NOT NULL,UNIQUE(task_id,revision)
                );
                INSERT INTO task_history SELECT * FROM task_history_before_cancel;
                DROP TABLE task_history_before_cancel;
                CREATE INDEX task_history_project_time ON task_history(project_id,recorded_at);
                """);
        try (var insert = connection.prepareStatement("""
                INSERT INTO sqlite_sequence(name,seq) SELECT 'task_history',?
                WHERE NOT EXISTS(SELECT 1 FROM sqlite_sequence WHERE name='task_history')
                """);
                var update = connection.prepareStatement("UPDATE sqlite_sequence SET seq=MAX(seq,?) WHERE name='task_history'")) {
            insert.setLong(1, sequence);
            insert.executeUpdate();
            update.setLong(1, sequence);
            update.executeUpdate();
        }
    }

    private static void addColumn(Connection connection, String table, String column, String definition)
            throws SQLException {
        if (!hasColumn(connection, table, column)) {
            execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM pragma_table_info(?) WHERE name=?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    // These fixed statements contain no semicolons inside quoted values or triggers.
    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            for (String command : sql.split(";")) {
                if (!command.isBlank()) {
                    statement.execute(command.strip());
                }
            }
        }
    }
}
