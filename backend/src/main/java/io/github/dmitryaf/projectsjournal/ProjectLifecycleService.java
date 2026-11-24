package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class ProjectLifecycleService {

    private static final Set<String> CLOSED = Set.of("completed", "stopped");
    private static final Pattern UUID_TEXT = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
                    + "|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final ObjectWriter json;
    private final ProjectService projects;
    private final ProjectFiles files;
    private final JournalSettings settings;
    private final TaskService tasks;

    ProjectLifecycleService(SqliteStore store, JsonMapper mapper, ProjectService projects,
                            ProjectFiles files, JournalSettings settings, TaskService tasks) {
        this.store = store;
        this.mapper = mapper;
        this.json = mapper.writer().without(JsonWriteFeature.WRITE_HEX_UPPER_CASE);
        this.projects = projects;
        this.files = files;
        this.settings = settings;
        this.tasks = tasks;
    }

    ObjectNode edit(String id, JsonNode input) throws SQLException {
        ApiJson.requireObject(input);
        var metadata = input.get("metadata");
        if (metadata == null || !metadata.isObject()) {
            throw ApiJson.invalid("metadata");
        }
        var edit = files.metadata(metadata, null);
        var notes = ApiJson.text(input.get("notes"), "notes", 0, 20_000, false);
        var expectedHash = hashField(input, false);
        var expectedRevision = expectedRevision(input);
        var expectedVersion = expectedVersion(input);
        projects.refresh(id);
        return store.write(connection -> {
            var row = ProjectRow.require(connection, id);
            var yaml = mapper.readTree(row.snapshot()).path("yaml");
            if (expectedHash != null && !Objects.equals(expectedHash.asString(null), yaml.path("hash").asString(null))) {
                throw new TaskError(409, "PROJECT.yaml изменился. Обновите страницу и сравните версии.");
            }
            var current = projects.metadata(row);
            for (var key : Set.of("current_focus", "next")) {
                if (!metadata.has(key)) {
                    edit.set(key, current.get(key));
                }
            }
            var revision = checkEdit(connection, row, current, expectedRevision, expectedVersion);
            save(connection, row, edit, notes, null);
            transitionIfNeeded(connection, row.id(), current, edit, revision);
            return projects.view(connection, ProjectRow.require(connection, id));
        });
    }

    ObjectNode reconcile(String id, JsonNode input, boolean useYaml) throws SQLException {
        var expectedHash = hashField(input, true);
        var expectedRevision = useYaml ? expectedRevision(input) : null;
        var expectedVersion = useYaml ? expectedVersion(input) : null;
        var observed = projects.refresh(id);
        return store.write(connection -> {
            var row = ProjectRow.require(connection, id);
            var snapshot = mapper.readTree(row.snapshot());
            var yaml = snapshot.path("yaml");
            if (!row.available() || yaml.hasNonNull("error")
                    || !Objects.equals(expectedHash.asString(null), yaml.path("hash").asString(null))) {
                throw new TaskError(409, useYaml
                        ? "PROJECT.yaml изменился или недоступен. Обновите страницу и сравните версии. Локальная версия сохранена."
                        : "Файл изменился или недоступен. Обновите страницу.");
            }
            var current = projects.metadata(row);
            var revision = checkEdit(connection, row, current, expectedRevision,
                    Objects.requireNonNullElse(expectedVersion, observed.get("metadataVersion").stringValue()));
            if (useYaml) {
                var next = yaml.hasNonNull("metadata") ? yaml.get("metadata").asObject()
                        : files.metadata(mapper.createObjectNode(), snapshot.path("directoryName").asString());
                save(connection, row, null, row.notes(), null);
                transitionIfNeeded(connection, row.id(), current, next, revision);
            } else {
                save(connection, row, current, row.notes(), expectedHash);
            }
            return projects.view(connection, ProjectRow.require(connection, id));
        });
    }

    ObjectNode export(String id, JsonNode input) throws IOException, SQLException {
        var hash = hashField(input, true).asString(null);
        var current = projects.refresh(id);
        var path = current.get("path").stringValue();
        ProjectFiles.assertProjectAccess(Path.of(path), JournalSettings.strings(settings.read().get("roots")));
        if (current.get("yamlConflict").booleanValue()) {
            throw new TaskError(409, "Сначала выберите, какую версию сводки оставить.");
        }
        var exported = current.get("metadata");
        files.exportMetadata(path, exported, hash);
        var updated = projects.refresh(id);
        return store.write(connection -> {
            var row = ProjectRow.require(connection, id);
            if (row.metadata() != null && row.path().equals(path) && projects.metadata(row).equals(exported)) {
                try (var statement = connection.prepareStatement("UPDATE projects SET yaml_base_hash=? WHERE id=?")) {
                    statement.setString(1, updated.path("snapshot").path("yaml").path("hash").asString(null));
                    statement.setString(2, id);
                    statement.executeUpdate();
                }
            }
            return projects.view(connection, ProjectRow.require(connection, id));
        });
    }

    ObjectNode transition(String id, JsonNode input, boolean closing) throws SQLException {
        ApiJson.requireObject(input);
        var fields = closing ? Set.of("revision", "requestKey", "outcome", "reason")
                : Set.of("revision", "requestKey");
        if (!fields.containsAll(input.propertyNames())) {
            throw ApiJson.invalid("");
        }
        var revision = ApiJson.revision(input.get("revision"), "revision");
        var key = uuid(input.get("requestKey"), "requestKey");
        var status = closing ? ApiJson.text(input.get("outcome"), "outcome", 1, 9, false) : "active";
        if (closing && !CLOSED.contains(status)) {
            throw ApiJson.invalid("outcome");
        }
        var reason = closing && input.has("reason") ? ApiJson.text(input.get("reason"), "reason", 0, 10_000, true) : "";
        var action = closing ? "closed" : "reopened";
        var hash = hash(mapper.createObjectNode().put("id", id).put("action", action).put("revision", revision)
                .put("status", status).put("reason", reason));
        return store.write(connection -> {
            var row = ProjectRow.require(connection, id);
            try (var statement = connection.prepareStatement("SELECT request_hash FROM project_lifecycle_events WHERE request_key=?")) {
                statement.setString(1, key);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        if (!hash.equals(rows.getString(1))) {
                            throw new TaskError(409, "Ключ уже использован для другого действия.");
                        }
                        return projects.view(connection, row);
                    }
                }
            }
            checkRevision(connection, id, revision);
            var current = projects.metadata(row);
            if (closing == CLOSED.contains(current.get("status").stringValue())) {
                throw new TaskError(409, closing ? "Проект уже закрыт." : "Проект уже открыт.");
            }
            current.put("status", status);
            save(connection, row, current, row.notes(), null);
            append(connection, id, current, closing, revision, reason, key, hash);
            return projects.view(connection, ProjectRow.require(connection, id));
        });
    }

    ObjectNode event(String id) throws SQLException {
        if (!UUID_TEXT.matcher(id).matches()) {
            throw ApiJson.invalid("");
        }
        try (var connection = store.open();
             var statement = connection.prepareStatement("SELECT * FROM project_lifecycle_events WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskError(404, "Событие проекта не найдено.");
                }
                return mapper.createObjectNode().put("id", rows.getString("id"))
                        .put("revision", rows.getLong("revision")).put("action", rows.getString("action"))
                        .put("outcome", rows.getString("outcome")).put("reason", rows.getString("reason"))
                        .put("recordedAt", rows.getString("recorded_at")).put("projectId", rows.getString("project_id"))
                        .put("scopeProjectId", rows.getString("scope_project_id")).put("projectName", rows.getString("project_name"));
            }
        }
    }

    static String uuid(JsonNode input, String field) {
        var value = ApiJson.text(input, field, 36, 36, false);
        if (!UUID_TEXT.matcher(value).matches()) {
            throw ApiJson.invalid(field);
        }
        return value;
    }

    private static JsonNode hashField(JsonNode input, boolean required) {
        ApiJson.requireObject(input);
        var value = input.get("expectedHash");
        if (value == null && required || value != null && !value.isNull() && !value.isString()) {
            throw ApiJson.invalid("expectedHash");
        }
        return value;
    }

    private static Long expectedRevision(JsonNode input) {
        return input.has("expectedLifecycleRevision") ? ApiJson.revision(input.get("expectedLifecycleRevision"), "expectedLifecycleRevision") : null;
    }

    private static String expectedVersion(JsonNode input) {
        return input.has("expectedMetadataVersion") ? ApiJson.text(input.get("expectedMetadataVersion"), "expectedMetadataVersion", 0, Integer.MAX_VALUE, false) : null;
    }

    private long checkEdit(Connection connection, ProjectRow row, ObjectNode metadata,
                           Long expectedRevision, String expectedVersion) throws SQLException {
        var revision = checkRevision(connection, row.id(), expectedRevision);
        if (expectedVersion != null && !expectedVersion.equals(projects.metadataVersion(row, metadata))) {
            throw new TaskError(409, "Сводка или заметки уже изменены. Сверьте сохранённую версию с черновиком.");
        }
        return revision;
    }

    private static long checkRevision(Connection connection, String id, Long expected) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT COALESCE(MAX(revision),0) FROM project_lifecycle_events WHERE scope_project_id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                var revision = rows.getLong(1);
                if (expected != null && expected != revision) {
                    throw new TaskError(409, "Состояние проекта изменилось. Обновите проект и подтвердите действие заново.");
                }
                return revision;
            }
        }
    }

    private void save(Connection connection, ProjectRow row, ObjectNode metadata, String notes, JsonNode baseHash)
            throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE projects SET metadata=?,notes=?,yaml_base_hash=? WHERE id=?")) {
            var hash = baseHash != null ? baseHash.asString(null) : row.metadata() == null
                    ? mapper.readTree(row.snapshot()).path("yaml").path("hash").asString(null) : row.yamlBaseHash();
            statement.setString(1, metadata == null ? null : json.writeValueAsString(metadata));
            statement.setString(2, notes);
            statement.setString(3, hash);
            statement.setString(4, row.id());
            statement.executeUpdate();
        }
    }

    private void transitionIfNeeded(Connection connection, String id, ObjectNode current, ObjectNode next, long revision)
            throws SQLException {
        var previousStatus = current.get("status").stringValue();
        var nextStatus = next.get("status").stringValue();
        if (!previousStatus.equals(nextStatus) && (CLOSED.contains(previousStatus) || CLOSED.contains(nextStatus))) {
            append(connection, id, next, CLOSED.contains(nextStatus), revision, "", null, null);
        }
    }

    private void append(Connection connection, String id, ObjectNode metadata, boolean closing, long revision,
                        String reason, String key, String hash) throws SQLException {
        var eventId = UUID.randomUUID().toString();
        var recordedAt = TIMESTAMP.format(Instant.now());
        try (var statement = connection.prepareStatement("""
                INSERT INTO project_lifecycle_events
                (id,project_id,scope_project_id,project_name,revision,action,outcome,reason,recorded_at,request_key,request_hash)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, eventId);
            statement.setString(2, id);
            statement.setString(3, id);
            statement.setString(4, metadata.get("name").stringValue());
            statement.setLong(5, revision + 1);
            statement.setString(6, closing ? "closed" : "reopened");
            statement.setString(7, closing ? metadata.get("status").stringValue() : null);
            statement.setString(8, reason);
            statement.setString(9, recordedAt);
            statement.setString(10, key);
            statement.setString(11, hash);
            statement.executeUpdate();
        }
        if (closing) {
            tasks.cancelProject(connection, id, eventId, recordedAt);
        }
    }

    private String hash(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 недоступен", unavailable);
        }
    }
}
