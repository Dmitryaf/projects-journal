package io.github.dmitryaf.controlcenter;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class ProjectRegistry {

    private static final DateTimeFormatter INSTANT = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final long DAY_MILLIS = 86_400_000;

    private final MvpStore store;
    private final ProjectFiles files;
    private final JsonMapper mapper;
    private volatile ObjectNode scanInfo;

    ProjectRegistry(MvpStore store, ProjectFiles files, JsonMapper mapper) {
        this.store = store;
        this.files = files;
        this.mapper = mapper;
        this.scanInfo = mapper.createObjectNode().putNull("scannedAt").put("running", false);
        scanInfo.putArray("errors");
    }

    @PostConstruct
    void initialize() throws SQLException {
        scan();
    }

    ObjectNode scanInfo() {
        return scanInfo.deepCopy();
    }

    synchronized ObjectNode scan() throws SQLException {
        scanInfo = scanInfo.deepCopy().put("running", true);
        try {
            ObjectNode configuration;
            try (var connection = store.open()) {
                configuration = settings(connection);
            }
            var discovery = files.discover(configuration);
            List<ObjectNode> snapshots = new ArrayList<>();
            ArrayNode errors = mapper.createArrayNode();
            discovery.errors().forEach(errors::add);
            for (String path : discovery.paths()) {
                try {
                    ProjectFiles.assertProjectAccess(Path.of(path), roots(configuration));
                    snapshots.add(files.inspect(path));
                } catch (IOException | ApiFailure failure) {
                    errors.add("Не удалось обновить проект: " + path);
                }
            }
            store.write(connection -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("UPDATE projects SET available=0");
                }
                for (ObjectNode snapshot : snapshots) {
                    saveSnapshot(connection, UUID.randomUUID().toString(), snapshot);
                }
                return null;
            });
            scanInfo = mapper.createObjectNode().put("scannedAt", timestamp()).set("errors", errors).put("running", false);
        } finally {
            scanInfo = scanInfo.deepCopy().put("running", false);
        }
        return scanInfo();
    }

    ObjectNode settings(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT data FROM settings WHERE id=1")) {
            if (rows.next()) {
                return mapper.readTree(rows.getString(1)).asObject();
            }
        }
        ObjectNode defaults = mapper.createObjectNode().put("inactivityDays", 14).put("reviewDays", 30);
        defaults.putArray("roots");
        ArrayNode exclusions = defaults.putArray("exclusions");
        List.of("node_modules", "dist", "build", "archive", ".git", ".venv", "vendor").forEach(exclusions::add);
        return defaults;
    }

    synchronized ObjectNode saveSettings(JsonNode input) throws SQLException {
        ObjectNode configuration = mapper.createObjectNode();
        ArrayNode roots = configuration.putArray("roots");
        var paths = new LinkedHashSet<String>();
        for (String raw : strings(input.get("roots"), "roots", 30, 2_000)) {
            try {
                Path path = Path.of(raw);
                if (!path.isAbsolute()) {
                    throw new ApiFailure(400, "Укажите абсолютные пути каталогов.");
                }
                paths.add(path.normalize().toString());
            } catch (InvalidPathException invalid) {
                throw new ApiFailure(400, "Укажите абсолютные пути каталогов.");
            }
        }
        paths.forEach(roots::add);
        ArrayNode excluded = configuration.putArray("exclusions");
        strings(input.get("exclusions"), "exclusions", 100, 100).forEach(excluded::add);
        configuration.put("inactivityDays", days(input, "inactivityDays")).put("reviewDays", days(input, "reviewDays"));
        store.write(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO settings VALUES(1,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data
                    """)) {
                statement.setString(1, mapper.writeValueAsString(configuration));
                statement.executeUpdate();
            }
            return null;
        });
        scan();
        return configuration;
    }

    private static int days(JsonNode input, String field) {
        JsonNode node = input.path(field);
        if (!node.isNumber() || !node.canConvertToExactIntegral() || !node.canConvertToInt()
                || node.intValue() < 1 || node.intValue() > 3650) {
            throw Input.invalid(field);
        }
        return node.intValue();
    }

    private static List<String> strings(JsonNode nodes, String field, int limit, int length) {
        if (nodes == null || !nodes.isArray() || nodes.size() > limit) {
            throw Input.invalid(field);
        }
        List<String> values = new ArrayList<>();
        for (int index = 0; index < nodes.size(); index++) {
            values.add(Input.text(nodes.get(index), field + "." + index, null, 1, length, true));
        }
        return values;
    }

    private static List<String> roots(JsonNode configuration) {
        List<String> roots = new ArrayList<>();
        configuration.path("roots").forEach(root -> roots.add(root.stringValue()));
        return roots;
    }

    List<ObjectNode> list(Connection connection, ObjectNode configuration) throws SQLException {
        List<ObjectNode> projects = new ArrayList<>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM projects ORDER BY path")) {
            while (rows.next()) {
                projects.add(view(connection, row(rows), configuration));
            }
        }
        return projects;
    }

    synchronized ObjectNode refresh(String id) throws SQLException {
        try (var connection = store.open()) {
            ProjectRow project = require(connection, id);
            var configuration = settings(connection);
            ObjectNode snapshot;
            try {
                ProjectFiles.assertProjectAccess(Path.of(project.path()), roots(configuration));
                snapshot = files.inspect(project.path());
            } catch (IOException | ApiFailure unavailable) {
                try (var update = connection.prepareStatement("UPDATE projects SET available=0 WHERE id=?")) {
                    update.setString(1, id);
                    update.executeUpdate();
                }
                return view(connection, require(connection, id), configuration);
            }
            saveSnapshot(connection, id, snapshot);
            return view(connection, require(connection, id), configuration);
        }
    }

    ObjectNode edit(String id, JsonNode input) throws SQLException {
        ObjectNode metadata = files.metadata(input.get("metadata"), "Проект");
        String notes = Input.text(input.get("notes"), "notes", null, 0, 20_000, false);
        return store.write(connection -> {
            require(connection, id);
            writeMetadata(connection, id, metadata, notes);
            return view(connection, require(connection, id), settings(connection));
        });
    }

    ObjectNode useYaml(String id) throws SQLException {
        store.write(connection -> {
            ProjectRow project = require(connection, id);
            writeMetadata(connection, id, null, project.notes());
            return null;
        });
        return refresh(id);
    }

    ObjectNode exportYaml(String id, JsonNode input) throws SQLException, IOException {
        JsonNode expectedHash = input.get("expectedHash");
        if (expectedHash == null) {
            throw Input.invalid("expectedHash");
        }
        String hash = expectedHash.isNull() ? null : Input.text(expectedHash, "expectedHash", null, 0, 262144, false);
        try (var connection = store.open()) {
            ProjectRow project = require(connection, id);
            ProjectFiles.assertProjectAccess(Path.of(project.path()), roots(settings(connection)));
            files.exportMetadata(project.path(), metadata(project, mapper.readTree(project.snapshot())), hash);
        }
        return refresh(id);
    }

    static ProjectRow find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM projects WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? row(rows) : null;
            }
        }
    }

    private static ProjectRow require(Connection connection, String id) throws SQLException {
        ProjectRow row = find(connection, id);
        if (row == null) {
            throw new ApiFailure(404, "Проект не найден.");
        }
        return row;
    }

    private static ProjectRow row(ResultSet rows) throws SQLException {
        return new ProjectRow(rows.getString("id"), rows.getString("path"), rows.getString("snapshot"),
                rows.getString("metadata"), rows.getString("notes"), rows.getBoolean("available"));
    }

    private void saveSnapshot(Connection connection, String id, ObjectNode snapshot) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO projects(id,path,snapshot) VALUES(?,?,?) ON CONFLICT(path)
                DO UPDATE SET snapshot=excluded.snapshot,available=1
                """)) {
            statement.setString(1, id);
            statement.setString(2, snapshot.path("path").stringValue());
            statement.setString(3, mapper.writeValueAsString(snapshot));
            statement.executeUpdate();
        }
    }

    private void writeMetadata(Connection connection, String id, ObjectNode metadata, String notes) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE projects SET metadata=?,notes=? WHERE id=?")) {
            statement.setString(1, metadata == null ? null : mapper.writeValueAsString(metadata));
            statement.setString(2, notes);
            statement.setString(3, id);
            statement.executeUpdate();
        }
    }

    private ObjectNode metadata(ProjectRow row, JsonNode snapshot) {
        if (row.metadata() != null) {
            return files.metadata(mapper.readTree(row.metadata()), "Проект");
        }
        JsonNode yaml = snapshot.path("yaml").path("metadata");
        return yaml.isObject() ? yaml.asObject() : files.metadata(mapper.createObjectNode(),
                snapshot.path("directoryName").asString("Проект"));
    }

    private ObjectNode view(Connection connection, ProjectRow row, ObjectNode configuration) throws SQLException {
        JsonNode snapshot = mapper.readTree(row.snapshot());
        JsonNode metadata = metadata(row, snapshot);
        boolean hasYaml = snapshot.path("yaml").hasNonNull("metadata");
        ObjectNode result = mapper.createObjectNode().put("id", row.id()).put("path", row.path())
                .set("snapshot", snapshot).set("metadata", metadata)
                .put("metadataSource", row.metadata() != null ? "local" : hasYaml ? "yaml" : "defaults")
                .put("notes", row.notes()).put("available", row.available());
        ArrayNode signals = result.putArray("signals");
        if (!row.available()) {
            signal(signals, "unavailable", "Не найден при последнем сканировании. Показан сохранённый снимок.");
        }
        attention(signals, snapshot, metadata, configuration);
        try (var statement = connection.prepareStatement("""
                SELECT count(*) FROM records WHERE kind='decisions' AND project_id=? AND json_extract(data,'$.status')='pending'
                """)) {
            statement.setString(1, row.id());
            try (var pending = statement.executeQuery()) {
                if (pending.next() && pending.getInt(1) > 0) {
                    signal(signals, "decision", "Ожидают решения: " + pending.getInt(1));
                }
            }
        }
        return result;
    }

    private static void attention(ArrayNode signals, JsonNode snapshot, JsonNode metadata, JsonNode settings) {
        var git = snapshot.path("git");
        var yaml = snapshot.path("yaml");
        if (git.hasNonNull("error")) signal(signals, "git-error", git.path("error").stringValue());
        if (yaml.hasNonNull("error")) signal(signals, "yaml-error", yaml.path("error").stringValue());
        snapshot.path("errors").forEach(error -> signal(signals, "read-error", error.stringValue()));
        if (git.path("dirty").booleanValue(false)) signal(signals, "dirty", "Есть незакоммиченные изменения");
        List<String> blocked = strings(metadata.path("blocked_by"), "blocked_by", 100, 240);
        if (!blocked.isEmpty()) signal(signals, "blocked", "Блокировки: " + String.join("; ", blocked));
        if (!"active".equals(metadata.path("status").asString())) {
            return;
        }
        Instant now = Instant.now();
        String last = git.path("commits").path(0).path("date").stringValue(null);
        if (last != null) {
            long days = Math.floorDiv(now.toEpochMilli() - Instant.parse(last).toEpochMilli(), DAY_MILLIS);
            if (days >= settings.path("inactivityDays").intValue()) signal(signals, "inactive", "Нет новых commits " + days + " дн.");
        }
        if (metadata.path("current_focus").asString().isEmpty()) signal(signals, "focus", "Не определён текущий фокус");
        if (metadata.path("next").isEmpty()) signal(signals, "next", "Не определён следующий шаг");
        String reviewed = metadata.path("last_reviewed").stringValue(null);
        if (reviewed == null) {
            signal(signals, "review", "Метаданные ещё не пересматривались");
        } else if (now.toEpochMilli() - LocalDate.parse(reviewed).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                >= settings.path("reviewDays").intValue() * DAY_MILLIS) {
            signal(signals, "review", "Пора пересмотреть метаданные");
        }
    }

    private static void signal(ArrayNode signals, String code, String message) {
        signals.addObject().put("code", code).put("message", message);
    }

    static String timestamp() {
        return INSTANT.format(Instant.now());
    }

    record ProjectRow(String id, String path, String snapshot, String metadata, String notes, boolean available) {
    }
}
