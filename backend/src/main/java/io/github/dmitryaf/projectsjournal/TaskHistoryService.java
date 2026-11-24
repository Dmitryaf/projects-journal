package io.github.dmitryaf.projectsjournal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class TaskHistoryService {

    private static final Set<String> FIELDS = Set.of("projectId", "identifier", "from", "to", "offset", "limit");
    private static final Pattern TASK_CODE = Pattern.compile("CC-([1-9][0-9]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAY = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final String PROJECT_IDS = TaskService.projectIdsSql("h.snapshot");

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final TaskPayload payload;

    TaskHistoryService(SqliteStore store, JsonMapper mapper) {
        this.store = store;
        this.mapper = mapper;
        payload = new TaskPayload(mapper);
    }

    ObjectNode list(JsonNode input) throws SQLException {
        Query query = parse(input);
        try (var connection = store.open()) {
            // Count, page, and existence lookups share a SQLite read snapshot.
            connection.setAutoCommit(false);
            ObjectNode result = mapper.createObjectNode().put("total", count(connection, query));
            items(connection, query, result.putArray("items"));
            connection.commit();
            return result;
        }
    }

    private static Query parse(JsonNode input) {
        ApiJson.requireObject(input);
        if (!FIELDS.containsAll(input.propertyNames())) {
            throw ApiJson.invalid("");
        }
        var clauses = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        if (input.has("projectId")) {
            String projectId = TaskPayload.uuid(input.get("projectId"), "projectId", true);
            // Membership comes from the retained snapshot, even after removal clears the FK.
            if (projectId == null) {
                clauses.add("json_array_length(" + PROJECT_IDS + ")=0");
            } else {
                clauses.add("EXISTS(SELECT 1 FROM json_each(" + PROJECT_IDS + ") WHERE value=?)");
                parameters.add(projectId);
            }
        }
        if (input.has("identifier")) {
            String identifier = ApiJson.text(input.get("identifier"), "identifier", 1, 240, true);
            var code = TASK_CODE.matcher(identifier);
            if (code.matches()) {
                try {
                    parameters.add(Long.parseLong(code.group(1)));
                    clauses.add("json_extract(h.snapshot,'$.number')=?");
                } catch (NumberFormatException ignored) {
                    clauses.add("0");
                }
            } else {
                clauses.add("h.task_id=?");
                parameters.add(identifier);
            }
        }
        String from = day(input, "from");
        String to = day(input, "to");
        if (from != null && to != null && from.compareTo(to) > 0) {
            throw ApiJson.invalid("to");
        }
        if (from != null) {
            clauses.add("h.recorded_at>=?");
            parameters.add(from + "T00:00:00.000Z");
        }
        if (to != null) {
            clauses.add("substr(h.recorded_at,1,10)<=?");
            parameters.add(to);
        }
        long offset = input.has("offset") ? ApiJson.revision(input.get("offset"), "offset") : 0;
        long limit = input.has("limit") ? ApiJson.integer(input.get("limit"), "limit", 1, 100) : 50;
        return new Query(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), parameters, offset, limit);
    }

    private static String day(JsonNode input, String field) {
        if (!input.has(field)) {
            return null;
        }
        String value = ApiJson.text(input.get(field), field, 10, 10, false);
        if (DAY.matcher(value).matches()) {
            try {
                LocalDate.parse(value);
                return value;
            } catch (DateTimeParseException ignored) {
                // The shape also matches impossible calendar dates.
            }
        }
        throw ApiJson.invalid(field);
    }

    private static long count(Connection connection, Query query) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT count(*) FROM task_history h" + query.where())) {
            bind(statement, query.parameters());
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        }
    }

    private void items(Connection connection, Query query, ArrayNode output) throws SQLException {
        String select = """
                SELECT h.*, EXISTS(SELECT 1 FROM records r WHERE r.id=h.task_id AND r.kind='tasks') AS task_exists
                FROM task_history h
                """;
        try (var statement = connection.prepareStatement(select + query.where()
                + " ORDER BY h.recorded_at DESC,h.id DESC LIMIT ? OFFSET ?");
             var project = connection.prepareStatement("SELECT 1 FROM projects WHERE id=?")) {
            bind(statement, query.parameters());
            statement.setLong(query.parameters().size() + 1, query.limit());
            statement.setLong(query.parameters().size() + 2, query.offset());
            var projectExistence = new HashMap<String, Boolean>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    output.add(item(rows, project, projectExistence));
                }
            }
        }
    }

    private ObjectNode item(ResultSet row, PreparedStatement project, Map<String, Boolean> projectExistence)
            throws SQLException {
        ObjectNode snapshot = mapper.readTree(row.getString("snapshot")).asObject();
        String projectName = row.getString("project_name");
        ObjectNode result = mapper.createObjectNode().put("id", row.getLong("id"))
                .put("kind", row.getString("kind")).put("recordedAt", row.getString("recorded_at"))
                .put("projectId", row.getString("project_id")).put("projectName", projectName)
                .put("taskExists", row.getBoolean("task_exists"));
        ArrayNode projects = result.putArray("projects");
        List<String> projectIds = TaskService.projectIds(snapshot);
        for (int index = 0; index < projectIds.size(); index++) {
            String id = projectIds.get(index);
            String name = snapshot.path("projectNames").path(index).asString(projectName == null ? "Проект" : projectName);
            if (!projectExistence.containsKey(id)) {
                project.setString(1, id);
                try (var exists = project.executeQuery()) {
                    projectExistence.put(id, exists.next());
                }
            }
            projects.addObject().put("id", id).put("name", name).put("exists", projectExistence.get(id));
        }
        return result.set("task", snapshot.setAll(payload.readData(snapshot)));
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    private record Query(String where, List<Object> parameters, long offset, long limit) {
    }
}
