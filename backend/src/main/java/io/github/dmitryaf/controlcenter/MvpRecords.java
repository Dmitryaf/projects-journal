package io.github.dmitryaf.controlcenter;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class MvpRecords {

    private static final Set<String> TASK_STATE = Set.of("now", "next", "later");
    private static final Set<String> IDEA_STATE = Set.of("new", "consider", "testing", "accepted", "rejected");
    private static final Set<String> PRIORITY = Set.of("low", "normal", "high");
    private static final Set<String> DECISION_STATUS = Set.of("pending", "active", "superseded");
    private static final Set<String> RELATION = Set.of("uses", "depends_on", "related_to", "produces");

    private final MvpStore store;
    private final JsonMapper mapper;

    MvpRecords(MvpStore store, JsonMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    List<ObjectNode> list(Connection connection, String kind) throws SQLException {
        List<ObjectNode> records = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT * FROM records WHERE kind=? ORDER BY created_at DESC")) {
            statement.setString(1, kind);
            try (var row = statement.executeQuery()) {
                while (row.next()) {
                    records.add(mapper.readTree(row.getString("data")).asObject()
                            .put("id", row.getString("id")).put("createdAt", row.getString("created_at"))
                            .put("updatedAt", row.getString("updated_at")));
                }
            }
        }
        return records;
    }

    ObjectNode save(String kind, String id, JsonNode input) throws SQLException {
        ObjectNode data = parse(kind, input);
        String projectId = data.path("projectId").stringValue(null);
        String recordId = id == null ? UUID.randomUUID().toString() : id;
        return store.write(connection -> {
            requireProject(connection, projectId, "Связанный проект не найден.");
            String now = ProjectRegistry.timestamp();
            String created = now;
            if (id != null) {
                try (var existing = connection.prepareStatement("SELECT created_at FROM records WHERE id=? AND kind=?")) {
                    existing.setString(1, id);
                    existing.setString(2, kind);
                    try (var row = existing.executeQuery()) {
                        if (!row.next()) {
                            throw new ApiFailure(404, "Запись не найдена.");
                        }
                        created = row.getString(1);
                    }
                }
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO records (id,kind,project_id,data,created_at,updated_at) VALUES(?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET project_id=excluded.project_id,data=excluded.data,updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, recordId);
                statement.setString(2, kind);
                statement.setString(3, projectId);
                statement.setString(4, mapper.writeValueAsString(data));
                statement.setString(5, created);
                statement.setString(6, now);
                statement.executeUpdate();
            }
            return data.put("id", recordId).put("createdAt", created).put("updatedAt", now);
        });
    }

    void delete(String kind, String id) throws SQLException {
        try (var connection = store.open(); var statement = connection.prepareStatement("DELETE FROM records WHERE kind=? AND id=?")) {
            statement.setString(1, kind);
            statement.setString(2, id);
            if (statement.executeUpdate() != 1) {
                throw new ApiFailure(404, "Запись не найдена.");
            }
        }
    }

    List<ObjectNode> relations(Connection connection) throws SQLException {
        List<ObjectNode> relations = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM relations")) {
            while (rows.next()) {
                relations.add(relation(rows));
            }
        }
        return relations;
    }

    ObjectNode relate(JsonNode input) throws SQLException {
        String sourceId = Input.uuid(input.get("sourceId"), "sourceId", false);
        String targetId = Input.uuid(input.get("targetId"), "targetId", false);
        String type = Input.choice(input, "type", null, RELATION);
        if (sourceId.equals(targetId)) {
            throw new ApiFailure(400, "Выберите другой проект.");
        }
        return store.write(connection -> {
            requireProject(connection, sourceId, "Проект связи не найден.");
            requireProject(connection, targetId, "Проект связи не найден.");
            try (var statement = connection.prepareStatement("SELECT * FROM relations WHERE sourceId=? AND targetId=? AND type=?")) {
                statement.setString(1, sourceId);
                statement.setString(2, targetId);
                statement.setString(3, type);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        return relation(rows);
                    }
                }
            }
            String id = UUID.randomUUID().toString();
            try (var statement = connection.prepareStatement("INSERT INTO relations VALUES(?,?,?,?)")) {
                statement.setString(1, id);
                statement.setString(2, sourceId);
                statement.setString(3, targetId);
                statement.setString(4, type);
                statement.executeUpdate();
            }
            return mapper.createObjectNode().put("id", id).put("sourceId", sourceId).put("targetId", targetId).put("type", type);
        });
    }

    void removeRelation(String id) throws SQLException {
        try (var connection = store.open(); var statement = connection.prepareStatement("DELETE FROM relations WHERE id=?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    private ObjectNode relation(ResultSet rows) throws SQLException {
        return mapper.createObjectNode().put("id", rows.getString("id")).put("sourceId", rows.getString("sourceId"))
                .put("targetId", rows.getString("targetId")).put("type", rows.getString("type"));
    }

    private static void requireProject(Connection connection, String id, String error) throws SQLException {
        if (id != null && ProjectRegistry.find(connection, id) == null) {
            throw new ApiFailure(400, error);
        }
    }

    private ObjectNode parse(String kind, JsonNode input) {
        ObjectNode data = mapper.createObjectNode()
                .put("title", Input.text(input.get("title"), "title", null, 1, 240, true))
                .put("projectId", Input.uuid(input.get("projectId"), "projectId", true));
        return switch (kind) {
            case "tasks" -> data.put("description", text(input, "description"))
                    .put("state", Input.choice(input, "state", "next", TASK_STATE))
                    .put("priority", Input.choice(input, "priority", "normal", PRIORITY))
                    .put("completedAt", Input.date(input, "completedAt", true));
            case "ideas" -> data.put("description", text(input, "description"))
                    .put("state", Input.choice(input, "state", "new", IDEA_STATE));
            case "decisions" -> data.put("context", text(input, "context"))
                    .put("decision", Input.text(input.get("decision"), "decision", null, 1, 10_000, true))
                    .put("reason", text(input, "reason")).put("date", Input.date(input, "date", false))
                    .put("status", Input.choice(input, "status", "active", DECISION_STATUS));
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static String text(JsonNode input, String field) {
        return Input.text(input.get(field), field, "", 0, 10_000, true);
    }
}
