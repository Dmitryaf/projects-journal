package io.github.dmitryaf.projectsjournal;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class JournalRecords {

    private static final Set<String> IDEA_STATES = Set.of("new", "consider", "testing", "accepted", "rejected");
    private static final Set<String> DECISION_STATES = Set.of("pending", "active", "superseded");
    private static final Set<String> RELATION_TYPES = Set.of("uses", "depends_on", "related_to", "produces");
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final SqliteStore store;
    private final JsonMapper mapper;

    JournalRecords(SqliteStore store, JsonMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    List<ObjectNode> list(String kind) throws SQLException {
        try (var connection = store.open()) {
            return list(connection, kind);
        }
    }

    List<ObjectNode> list(Connection connection, String kind) throws SQLException {
        var result = new ArrayList<ObjectNode>();
        try (var statement = connection.prepareStatement("SELECT id,data,created_at,updated_at FROM records WHERE kind=? ORDER BY created_at DESC")) {
            statement.setString(1, kind);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(stamp(mapper.readTree(rows.getString("data")).asObject(), rows.getString("id"),
                            rows.getString("created_at"), rows.getString("updated_at")));
                }
            }
        }
        return result;
    }

    ObjectNode save(String kind, String id, JsonNode input) throws SQLException {
        return store.write(connection -> save(connection, kind, id, input));
    }

    ObjectNode save(Connection connection, String kind, String id, JsonNode input) throws SQLException {
        var data = parse(kind, input);
        var project = data.path("projectId").asString(null);
        var recordId = id == null ? UUID.randomUUID().toString() : id;
        if (project != null && ProjectRow.find(connection, project) == null) {
            throw new TaskError(400, "Связанный проект не найден.");
        }
        var now = TIMESTAMP.format(Instant.now());
        var created = id == null ? now : createdAt(connection, kind, id);
        try (var statement = connection.prepareStatement("""
                INSERT INTO records(id,kind,project_id,data,created_at,updated_at) VALUES(?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET project_id=excluded.project_id,data=excluded.data,updated_at=excluded.updated_at
                """)) {
            statement.setString(1, recordId);
            statement.setString(2, kind);
            statement.setString(3, project);
            statement.setString(4, mapper.writeValueAsString(data));
            statement.setString(5, created);
            statement.setString(6, now);
            statement.executeUpdate();
        }
        return stamp(data, recordId, created, now);
    }

    void delete(String kind, String id) throws SQLException {
        store.write(connection -> {
            try (var statement = connection.prepareStatement("DELETE FROM records WHERE kind=? AND id=?")) {
                statement.setString(1, kind);
                statement.setString(2, id);
                if (statement.executeUpdate() == 0) {
                    throw new TaskError(404, "Запись не найдена.");
                }
            }
            return null;
        });
    }

    List<ObjectNode> relations(Connection connection) throws SQLException {
        var result = new ArrayList<ObjectNode>();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id,sourceId,targetId,type FROM relations")) {
            while (rows.next()) {
                result.add(relation(rows.getString("id"), rows.getString("sourceId"),
                        rows.getString("targetId"), rows.getString("type")));
            }
        }
        return result;
    }

    ObjectNode relate(JsonNode input) throws SQLException {
        ApiJson.requireObject(input);
        var source = ProjectLifecycleService.uuid(input.get("sourceId"), "sourceId");
        var target = ProjectLifecycleService.uuid(input.get("targetId"), "targetId");
        var type = option(input, "type", RELATION_TYPES, null);
        if (source.equals(target)) {
            throw new TaskError(400, "Выберите другой проект.");
        }
        return store.write(connection -> {
            if (ProjectRow.find(connection, source) == null || ProjectRow.find(connection, target) == null) {
                throw new TaskError(400, "Проект связи не найден.");
            }
            try (var statement = connection.prepareStatement("SELECT id FROM relations WHERE sourceId=? AND targetId=? AND type=?")) {
                statement.setString(1, source);
                statement.setString(2, target);
                statement.setString(3, type);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        return relation(rows.getString("id"), source, target, type);
                    }
                }
            }
            var id = UUID.randomUUID().toString();
            try (var statement = connection.prepareStatement("INSERT INTO relations(id,sourceId,targetId,type) VALUES(?,?,?,?)")) {
                statement.setString(1, id);
                statement.setString(2, source);
                statement.setString(3, target);
                statement.setString(4, type);
                statement.executeUpdate();
            }
            return relation(id, source, target, type);
        });
    }

    void removeRelation(String id) throws SQLException {
        store.write(connection -> {
            try (var statement = connection.prepareStatement("DELETE FROM relations WHERE id=?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private ObjectNode parse(String kind, JsonNode input) {
        ApiJson.requireObject(input);
        var project = input.get("projectId");
        var data = mapper.createObjectNode()
                .put("title", ApiJson.text(input.get("title"), "title", 1, 240, true))
                .put("projectId", project == null || project.isNull() ? null : ProjectLifecycleService.uuid(project, "projectId"));
        return switch (kind) {
            case "ideas" -> data.put("description", optionalText(input, "description"))
                    .put("state", option(input, "state", IDEA_STATES, "new"));
            case "decisions" -> data.put("context", optionalText(input, "context"))
                    .put("decision", ApiJson.text(input.get("decision"), "decision", 1, 10_000, true))
                    .put("reason", optionalText(input, "reason"))
                    .put("date", date(input.get("date")))
                    .put("status", option(input, "status", DECISION_STATES, "active"));
            default -> throw new IllegalArgumentException("Unknown record kind");
        };
    }

    private static String optionalText(JsonNode input, String field) {
        return input.has(field) ? ApiJson.text(input.get(field), field, 0, 10_000, true) : "";
    }

    private static String date(JsonNode input) {
        var value = ApiJson.text(input, "date", 10, 10, false);
        try {
            if (LocalDate.parse(value).toString().equals(value)) {
                return value;
            }
        } catch (DateTimeParseException invalid) {
            // Keep the date-only validation error consistent with other fields.
        }
        throw ApiJson.invalid("date");
    }

    private static String option(JsonNode input, String field, Set<String> values, String fallback) {
        var value = input.has(field) ? ApiJson.text(input.get(field), field, 1, 240, false) : fallback;
        if (value == null || !values.contains(value)) {
            throw ApiJson.invalid(field);
        }
        return value;
    }

    private static String createdAt(Connection connection, String kind, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT created_at FROM records WHERE id=? AND kind=?")) {
            statement.setString(1, id);
            statement.setString(2, kind);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskError(404, "Запись не найдена.");
                }
                return rows.getString(1);
            }
        }
    }

    private static ObjectNode stamp(ObjectNode data, String id, String created, String updated) {
        return data.put("id", id).put("createdAt", created).put("updatedAt", updated);
    }

    private ObjectNode relation(String id, String source, String target, String type) {
        return mapper.createObjectNode().put("id", id).put("sourceId", source).put("targetId", target).put("type", type);
    }
}
