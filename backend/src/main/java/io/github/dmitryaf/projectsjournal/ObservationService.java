package io.github.dmitryaf.projectsjournal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

@Component
final class ObservationService {

    private static final Set<String> CREATE_FIELDS = Set.of("body", "occurredOn", "projectId", "requestKey");
    private static final Set<String> PATCH_FIELDS = Set.of("revision", "body", "occurredOn", "projectId");
    private static final Set<String> DELETE_FIELDS = Set.of("revision");
    private static final Pattern LONE_SURROGATE = Pattern.compile("[\\uD800-\\uDFFF]");

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final ObjectWriter json;

    ObservationService(SqliteStore store, JsonMapper mapper) {
        this.store = store;
        this.mapper = mapper;
        this.json = mapper.writer().without(JsonWriteFeature.WRITE_HEX_UPPER_CASE);
    }

    ObjectNode read(String id) throws SQLException {
        validateId(id);
        try (var connection = store.open()) {
            return read(connection, id);
        }
    }

    ObjectNode create(JsonNode input) throws SQLException {
        checkFields(input, CREATE_FIELDS);
        var body = body(input);
        var occurredOn = occurredOn(input);
        var projectId = projectId(input);
        var requestKey = input.has("requestKey") ? ProjectLifecycleService.uuid(input.get("requestKey"), "requestKey") : null;
        // Keep the field order and representation of persisted creation keys.
        var hash = hash(mapper.createObjectNode().put("body", body).put("occurredOn", occurredOn).put("projectId", projectId));
        return store.write(connection -> {
            if (requestKey != null) {
                var repeatedId = replay(connection, requestKey, hash);
                if (repeatedId != null) {
                    return read(connection, repeatedId);
                }
            }
            var projectName = projectName(connection, projectId);
            var id = UUID.randomUUID().toString();
            var now = JournalPeriods.utc(Instant.now());
            try (var insert = connection.prepareStatement("""
                    INSERT INTO observations(id,project_id,scope_project_id,project_name,occurred_on,body,
                      revision,created_at,updated_at,request_key,request_hash) VALUES(?,?,?,?,?,?,1,?,?,?,?)
                    """)) {
                insert.setString(1, id);
                insert.setString(2, projectId);
                insert.setString(3, projectId);
                insert.setString(4, projectName);
                insert.setString(5, occurredOn);
                insert.setString(6, sqliteBody(body));
                insert.setString(7, now);
                insert.setString(8, now);
                insert.setString(9, requestKey);
                insert.setString(10, hash);
                insert.executeUpdate();
            }
            return read(connection, id);
        });
    }

    ObjectNode patch(String id, JsonNode input) throws SQLException {
        checkFields(input, PATCH_FIELDS);
        var revision = revision(input);
        var body = input.has("body") ? body(input) : null;
        var day = input.has("occurredOn") ? occurredOn(input) : null;
        var requestedProject = projectId(input);
        validateId(id);
        return store.write(connection -> {
            var current = read(connection, id);
            checkRevision(current, revision);
            var previousProject = current.path("projectId").stringValue(null);
            var projectId = input.has("projectId") ? requestedProject : previousProject;
            // An explicit null clears a retained project even if its live link was already removed.
            var rescope = input.has("projectId") && (projectId == null || !Objects.equals(projectId, previousProject));
            var scopeProjectId = rescope ? projectId : current.path("scopeProjectId").stringValue(null);
            var projectName = rescope ? projectName(connection, projectId) : current.path("projectName").stringValue(null);
            try (var update = connection.prepareStatement("""
                    UPDATE observations SET body=?,occurred_on=?,project_id=?,scope_project_id=?,project_name=?,
                      revision=revision+1,updated_at=? WHERE id=?
                    """)) {
                update.setString(1, body == null ? current.path("body").asString() : sqliteBody(body));
                update.setString(2, day == null ? current.path("occurredOn").asString() : day);
                update.setString(3, projectId);
                update.setString(4, scopeProjectId);
                update.setString(5, projectName);
                update.setString(6, JournalPeriods.utc(Instant.now()));
                update.setString(7, id);
                update.executeUpdate();
            }
            return read(connection, id);
        });
    }

    void delete(String id, JsonNode input) throws SQLException {
        checkFields(input, DELETE_FIELDS);
        var revision = revision(input);
        validateId(id);
        store.write(connection -> {
            checkRevision(read(connection, id), revision);
            try (var delete = connection.prepareStatement("DELETE FROM observations WHERE id=?")) {
                delete.setString(1, id);
                delete.executeUpdate();
            }
            return null;
        });
    }

    private ObjectNode read(Connection connection, String id) throws SQLException {
        try (var select = connection.prepareStatement("""
                SELECT id,body,occurred_on,project_id,scope_project_id,project_name,revision,created_at,updated_at
                FROM observations WHERE id=?
                """)) {
            select.setString(1, id);
            try (var row = select.executeQuery()) {
                if (!row.next()) {
                    throw new TaskError(404, "Наблюдение не найдено.");
                }
                return mapper.createObjectNode().put("id", row.getString("id")).put("body", row.getString("body"))
                        .put("occurredOn", row.getString("occurred_on")).put("projectId", row.getString("project_id"))
                        .put("scopeProjectId", row.getString("scope_project_id")).put("projectName", row.getString("project_name"))
                        .put("revision", row.getLong("revision")).put("createdAt", row.getString("created_at"))
                        .put("updatedAt", row.getString("updated_at"));
            }
        }
    }

    private static String replay(Connection connection, String requestKey, String hash) throws SQLException {
        try (var select = connection.prepareStatement("SELECT id,request_hash FROM observations WHERE request_key=?")) {
            select.setString(1, requestKey);
            try (var row = select.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                if (!hash.equals(row.getString("request_hash"))) {
                    throw new TaskError(409, "Этот ключ создания уже использован с другими полями. "
                            + "Найдите сохранённое наблюдение в истории; текущий ввод не записан.");
                }
                return row.getString("id");
            }
        }
    }

    private static String projectName(Connection connection, String id) throws SQLException {
        if (id == null) {
            return null;
        }
        try (var select = connection.prepareStatement("""
                SELECT COALESCE(CASE WHEN metadata IS NOT NULL THEN json_extract(metadata,'$.name')
                  ELSE json_extract(snapshot,'$.yaml.metadata.name') END,json_extract(snapshot,'$.directoryName'))
                FROM projects WHERE id=?
                """)) {
            select.setString(1, id);
            try (var row = select.executeQuery()) {
                if (!row.next()) {
                    throw new TaskError(400, "Проект не найден. Выберите существующий проект или сохраните общую запись.");
                }
                return row.getString(1);
            }
        }
    }

    private String hash(ObjectNode value) {
        var encoded = LONE_SURROGATE.matcher(json.writeValueAsString(value)).replaceAll(match ->
                Matcher.quoteReplacement("\\u%04x".formatted((int) match.group().charAt(0))));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 недоступен", error);
        }
    }

    private static void validateId(String id) {
        ProjectLifecycleService.uuid(StringNode.valueOf(id), "id");
    }

    private static void checkFields(JsonNode input, Set<String> fields) {
        ApiJson.requireObject(input);
        for (var field : input.propertyNames()) {
            if (!fields.contains(field)) {
                throw ApiJson.invalid(field);
            }
        }
    }

    private static String body(JsonNode input) {
        return ApiJson.text(input.get("body"), "body", 1, 20_000, true);
    }

    private static String sqliteBody(String body) {
        // Normalize unpaired UTF-16 code units to U+FFFD before persisting SQLite text.
        return LONE_SURROGATE.matcher(body).replaceAll("\uFFFD");
    }

    private static String occurredOn(JsonNode input) {
        var value = ApiJson.text(input.get("occurredOn"), "occurredOn", 10, 10, false);
        JournalPeriods.day(value, "occurredOn");
        return value;
    }

    private static String projectId(JsonNode input) {
        return input.hasNonNull("projectId") ? ProjectLifecycleService.uuid(input.get("projectId"), "projectId") : null;
    }

    private static long revision(JsonNode input) {
        var revision = ApiJson.revision(input.get("revision"), "revision");
        if (revision < 1) {
            throw ApiJson.invalid("revision");
        }
        return revision;
    }

    private static void checkRevision(ObjectNode current, long revision) {
        if (current.path("revision").asLong() != revision) {
            throw new TaskError(409, "Наблюдение уже изменено. Прочитайте сохранённую запись и сравните её с черновиком; "
                    + "текущий ввод не записан.");
        }
    }
}
