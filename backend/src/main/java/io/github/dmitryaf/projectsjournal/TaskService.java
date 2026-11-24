package io.github.dmitryaf.projectsjournal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class TaskService {

    private static final Pattern TASK_CODE = Pattern.compile("CC-([1-9][0-9]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LONE_SURROGATE = Pattern.compile("[\\uD800-\\uDFFF]");
    private static final DateTimeFormatter INSTANT = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final String SELECT = """
            SELECT r.*, t.number, t.revision FROM records r
            JOIN task_identity t ON t.record_id = r.id WHERE r.kind = 'tasks'
            """;

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final ObjectWriter writer;
    private final TaskPayload payload;

    TaskService(SqliteStore store, JsonMapper mapper) {
        this.store = store;
        this.mapper = mapper;
        this.writer = mapper.writer().without(JsonWriteFeature.WRITE_HEX_UPPER_CASE);
        this.payload = new TaskPayload(mapper);
    }

    ObjectNode read(String id) throws SQLException {
        try (var connection = store.open()) {
            return read(connection, id);
        }
    }

    List<ObjectNode> list() throws SQLException {
        try (var connection = store.open()) {
            return list(connection);
        }
    }

    List<ObjectNode> list(Connection connection) throws SQLException {
        List<ObjectNode> tasks = new ArrayList<>();
        try (var statement = connection.prepareStatement(SELECT + "ORDER BY r.created_at DESC, t.number DESC");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                tasks.add(stamp(rows));
            }
        }
        return tasks;
    }

    void detachProject(Connection connection, String id) throws SQLException {
        for (ObjectNode task : list(connection)) {
            List<String> projects = projectIds(task);
            if (projects.contains(id)) {
                ObjectNode changed = task.deepCopy();
                ArrayNode remaining = changed.putArray("projectIds");
                projects.stream().filter(project -> !project.equals(id)).forEach(remaining::add);
                save(connection, changed, task);
            }
        }
    }

    void cancelProject(Connection connection, String projectId, String eventId, String at) throws SQLException {
        ObjectNode cancellation = mapper.createObjectNode().put("at", at).put("projectEventId", eventId);
        for (ObjectNode task : list(connection)) {
            List<String> projects = projectIds(task);
            if (task.hasNonNull("completedAt") || task.hasNonNull("cancellation") || !projects.contains(projectId)) {
                continue;
            }
            boolean stillOpen = false;
            for (String otherId : projects) {
                if (otherId.equals(projectId)) {
                    continue;
                }
                ProjectRow other = ProjectRow.find(connection, otherId);
                if (other == null) {
                    continue;
                }
                JsonNode metadata = other.metadata() == null
                        ? mapper.readTree(other.snapshot()).path("yaml").path("metadata")
                        : mapper.readTree(other.metadata());
                String status = metadata.path("status").asString("unknown");
                if (!"completed".equals(status) && !"stopped".equals(status)) {
                    stillOpen = true;
                    break;
                }
            }
            if (!stillOpen) {
                save(connection, task, task, cancellation);
            }
        }
    }

    ObjectNode create(JsonNode input) throws SQLException {
        return store.write(connection -> create(connection, input));
    }

    ObjectNode create(Connection connection, JsonNode input) throws SQLException {
        return save(connection, input, null);
    }

    ObjectNode update(String id, JsonNode input) throws SQLException {
        return store.write(connection -> save(connection, input, read(connection, id)));
    }

    void delete(String id, Long expectedRevision) throws SQLException {
        store.write(connection -> {
            ObjectNode task = read(connection, id);
            if (expectedRevision != null && expectedRevision != task.path("revision").longValue()) {
                throw new TaskError(409, task.path("code").asString()
                        + " уже изменена. Откройте актуальную задачу и подтвердите удаление заново.");
            }
            try (var statement = connection.prepareStatement("DELETE FROM records WHERE kind='tasks' AND id=?")) {
                statement.setString(1, id);
                if (statement.executeUpdate() == 0) {
                    throw new TaskError(404, "Задача не найдена.");
                }
            }
            return null;
        });
    }

    ObjectNode complete(String id, long revision, JsonNode input) throws SQLException {
        return store.write(connection -> {
            ObjectNode current = read(connection, id);
            if (current.hasNonNull("cancellation")) {
                throw new TaskError(409, "Сначала верните отменённую задачу в работу.");
            }
            ObjectNode result = payload.parseResult(input);
            if (!result.path("remaining").asString().isEmpty()) {
                throw new TaskError(400, "Осталась работа: используйте task_update и сохраните частичный результат.");
            }
            ObjectNode update = current.deepCopy().set("result", result).put("revision", revision)
                    .put("completedAt", INSTANT.format(Instant.now()));
            return save(connection, update, current);
        });
    }

    List<ObjectNode> importPlan(String projectId, List<String> steps) throws SQLException {
        if (steps.isEmpty()) {
            return List.of();
        }
        return store.write(connection -> {
            var byTitle = new HashMap<String, ObjectNode>();
            for (ObjectNode task : list(connection)) {
                if (projectIds(task).contains(projectId)) {
                    byTitle.putIfAbsent(TaskPayload.normalizedTitle(task.path("title").asString()), task);
                }
            }
            List<ObjectNode> imported = new ArrayList<>();
            for (String title : steps) {
                String normalized = TaskPayload.normalizedTitle(title);
                ObjectNode existing = byTitle.get(normalized);
                if (existing == null) {
                    String source = LONE_SURROGATE.matcher(projectId + "\n" + normalized).replaceAll("\uFFFD");
                    ObjectNode input = mapper.createObjectNode().put("title", title).put("projectId", projectId)
                            .put("requestKey", "legacy-plan:" + sha256(source));
                    existing = save(connection, input, null);
                    byTitle.put(normalized, existing);
                }
                imported.add(existing);
            }
            return imported;
        });
    }

    ObjectNode patch(String id, long revision, JsonNode change) throws SQLException {
        TaskPayload.checkPatch(change);
        return store.write(connection -> {
            ObjectNode existing = read(connection, id);
            ObjectNode update = existing.deepCopy().setAll(change.asObject()).put("revision", revision);
            if (change.has("projectId") && !change.has("projectIds")) {
                // The shared PUT path preserves legacy memberships or rejects an ambiguous reassignment.
                update.remove("projectIds");
            }
            return save(connection, update, existing);
        });
    }

    private ObjectNode read(Connection connection, String id) throws SQLException {
        Matcher code = TASK_CODE.matcher(id);
        boolean byCode = code.matches();
        try (var statement = connection.prepareStatement(SELECT + (byCode ? "AND t.number = ?" : "AND r.id = ?"))) {
            if (byCode) {
                try {
                    statement.setLong(1, Long.parseLong(code.group(1)));
                } catch (NumberFormatException ignored) {
                    throw notFound();
                }
            } else {
                statement.setString(1, id);
            }
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    return stamp(rows);
                }
            }
        }
        throw notFound();
    }

    private ObjectNode stamp(ResultSet row) throws SQLException {
        long number = row.getLong("number");
        return payload.readData(mapper.readTree(row.getString("data")))
                .put("projectId", row.getString("project_id"))
                .put("id", row.getString("id"))
                .put("number", number)
                .put("code", "CC-" + number)
                .put("revision", row.getLong("revision"))
                .put("createdAt", row.getString("created_at"))
                .put("updatedAt", row.getString("updated_at"));
    }

    private ObjectNode save(Connection connection, JsonNode input, ObjectNode existing) throws SQLException {
        return save(connection, input, existing, null);
    }

    private ObjectNode save(Connection connection, JsonNode input, ObjectNode existing, ObjectNode cancellation)
            throws SQLException {
        TaskPayload.checkWriteInput(input);
        Long revision = TaskPayload.revision(input);
        String requestKey = TaskPayload.requestKey(input);
        boolean allowDuplicate = TaskPayload.allowDuplicate(input);
        ObjectNode data = payload.readData(input);

        List<String> projects = normalizeProjects(data, existing);
        if (existing != null && !input.has("cancellation")) {
            data.set("cancellation", existing.get("cancellation"));
        }
        if (cancellation != null) {
            data.set("cancellation", cancellation);
        }
        checkOutcome(data, existing, cancellation != null);

        String hash = null;
        if (existing == null && requestKey != null) {
            hash = createHash(data, projects);
            ObjectNode repeated = replay(connection, requestKey, hash);
            if (repeated != null) {
                return repeated;
            }
        }
        if (existing != null) {
            checkRevision(existing, revision);
        }
        validateProjects(connection, projects);
        String now = cancellation == null ? INSTANT.format(Instant.now()) : cancellation.path("at").asString();
        // A later revision still sorts after an earlier writer with a faster clock.
        if (existing != null && existing.path("updatedAt").asString().compareTo(now) > 0) {
            now = existing.path("updatedAt").asString();
        }
        // Preserve revision order when HTTP and MCP observe different wall-clock times.
        if (existing != null && existing.get("updatedAt").stringValue().compareTo(now) > 0) {
            now = existing.get("updatedAt").stringValue();
        }
        if (existing == null) {
            if (!allowDuplicate) {
                checkDuplicate(connection, data, projects);
            }
            return insert(connection, data, requestKey, hash, now);
        }
        return update(connection, data, existing, now);
    }

    private static List<String> normalizeProjects(ObjectNode data, ObjectNode existing) {
        List<String> projects = projectIds(data);
        if (existing != null && !data.has("projectIds")) {
            List<String> current = projectIds(existing);
            if (Objects.equals(data.get("projectId"), existing.get("projectId"))) {
                projects = current;
            } else if (current.size() > 1) {
                throw new TaskError(409, "У задачи несколько проектов. Измените projectIds явно.");
            }
        }
        List<String> sorted = projects.stream().distinct().sorted().toList();
        ArrayNode ids = data.putArray("projectIds");
        sorted.forEach(ids::add);
        data.put("projectId", sorted.isEmpty() ? null : sorted.getFirst());
        return sorted;
    }

    static List<String> projectIds(JsonNode data) {
        if (!data.hasNonNull("projectIds")) {
            return data.hasNonNull("projectId") ? List.of(data.get("projectId").asString()) : List.of();
        }
        List<String> ids = new ArrayList<>();
        data.get("projectIds").forEach(id -> ids.add(id.asString()));
        return ids;
    }

    // Callers supply a fixed column expression, never request data.
    static String projectIdsSql(String json) {
        return "COALESCE(json_extract(" + json + ",'$.projectIds'),CASE WHEN json_extract(" + json
                + ",'$.projectId') IS NULL THEN '[]' ELSE json_array(json_extract(" + json + ",'$.projectId')) END)";
    }

    private static void checkOutcome(JsonNode data, JsonNode existing, boolean projectClosed) {
        boolean cancelled = data.hasNonNull("cancellation");
        boolean completed = data.hasNonNull("completedAt");
        if (cancelled && !projectClosed
                && (existing == null || !data.get("cancellation").equals(existing.get("cancellation")))) {
            throw new TaskError(400, "Отмена записывается при закрытии проекта; для возврата очистите cancellation.");
        }
        if (completed && cancelled) {
            throw new TaskError(400,
                    "Отменённую задачу нельзя одновременно считать выполненной. Сначала верните её в работу.");
        }
        if (completed && !data.path("result").path("remaining").asString("").isEmpty()) {
            throw new TaskError(400, "Осталась работа: сохраните результат без завершения задачи.");
        }
    }

    private String createHash(ObjectNode data, List<String> projects) {
        // The existing hash predates multi-project membership and cancellation for ordinary tasks.
        ObjectNode fields = data.deepCopy();
        JsonNode cancellation = fields.remove("cancellation");
        JsonNode ids = fields.remove("projectIds");
        if (!cancellation.isNull()) {
            fields.set("cancellation", cancellation);
        }
        if (projects.size() > 1) {
            fields.set("projectIds", ids);
        }
        return sha256(json(fields));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 недоступен", error);
        }
    }

    private String json(JsonNode value) {
        String encoded = writer.writeValueAsString(value);
        // JSON.stringify escapes isolated UTF-16 surrogates before UTF-8 encoding.
        return LONE_SURROGATE.matcher(encoded).replaceAll(match ->
                Matcher.quoteReplacement("\\u%04x".formatted((int) match.group().charAt(0))));
    }

    private ObjectNode replay(Connection connection, String requestKey, String hash) throws SQLException {
        String recordId;
        try (var statement = connection.prepareStatement(
                "SELECT record_id, request_hash FROM task_identity WHERE request_key = ?")) {
            statement.setString(1, requestKey);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                if (!hash.equals(rows.getString("request_hash"))) {
                    throw new TaskError(409,
                            "Этот ключ создания уже использован с другими полями. Прочитайте исходную задачу.");
                }
                recordId = rows.getString("record_id");
            }
        }
        return read(connection, recordId);
    }

    private static void checkRevision(JsonNode existing, Long revision) {
        if (revision == null) {
            throw new TaskError(409, "Для изменения нужна revision из актуальной задачи. Прочитайте задачу и повторите.");
        }
        if (revision != existing.get("revision").longValue()) {
            throw new TaskError(409, existing.get("code").asString()
                    + " уже изменена. Прочитайте задачу и согласуйте изменения; ваш ввод не записан.");
        }
    }

    private static void validateProjects(Connection connection, List<String> projects) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM projects WHERE id = ?")) {
            for (String id : projects) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new TaskError(400, "Связанный проект не найден.");
                    }
                }
            }
        }
    }

    private static List<String> projectNames(Connection connection, List<String> projects) throws SQLException {
        List<String> names = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT CASE WHEN metadata IS NOT NULL THEN json_extract(metadata, '$.name')
                            ELSE json_extract(snapshot, '$.yaml.metadata.name') END AS name,
                       json_extract(snapshot, '$.directoryName') AS directory_name
                FROM projects WHERE id = ?
                """)) {
            for (String id : projects) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) {
                        throw new TaskError(400, "Связанный проект не найден.");
                    }
                    names.add(Objects.requireNonNullElse(rows.getString("name"),
                            Objects.toString(rows.getString("directory_name"), "")));
                }
            }
        }
        return names;
    }

    private void checkDuplicate(Connection connection, ObjectNode data, List<String> projects) throws SQLException {
        String title = TaskPayload.normalizedTitle(data.get("title").asString());
        try (var statement = connection.prepareStatement(SELECT + "ORDER BY r.created_at DESC, t.number DESC");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                ObjectNode task = stamp(rows);
                if (!task.hasNonNull("completedAt") && !task.hasNonNull("cancellation")
                        && projectIds(task).stream().sorted().toList().equals(projects)
                        && TaskPayload.normalizedTitle(task.get("title").asString()).equals(title)) {
                    throw new TaskError(409, "Похожая задача уже есть: " + task.get("code").asString()
                            + ". Прочитайте её; для отдельной задачи подтвердите allowDuplicate.");
                }
            }
        }
    }

    private ObjectNode insert(Connection connection, ObjectNode data, String requestKey, String hash, String now)
            throws SQLException {
        String id = UUID.randomUUID().toString();
        try (var statement = connection.prepareStatement("""
                INSERT INTO records(id, kind, project_id, data, created_at, updated_at)
                VALUES (?, 'tasks', ?, ?, ?, ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, data.get("projectId").asString(null));
            statement.setString(3, json(data));
            statement.setString(4, now);
            statement.setString(5, now);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO task_identity(record_id, request_key, request_hash) VALUES (?, ?, ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, requestKey);
            statement.setString(3, hash);
            statement.executeUpdate();
        }
        ObjectNode saved = read(connection, id);
        if (saved.hasNonNull("completedAt")) {
            appendHistory(connection, saved, "completed");
        } else if (saved.hasNonNull("result")) {
            appendHistory(connection, saved, "result");
        }
        return saved;
    }

    private ObjectNode update(Connection connection, ObjectNode data, ObjectNode existing, String now)
            throws SQLException {
        String id = existing.get("id").asString();
        try (var statement = connection.prepareStatement("""
                UPDATE records SET project_id = ?, data = ?, updated_at = ? WHERE id = ? AND kind = 'tasks'
                """)) {
            statement.setString(1, data.get("projectId").asString(null));
            statement.setString(2, json(data));
            statement.setString(3, now);
            statement.setString(4, id);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("UPDATE task_identity SET revision = revision + 1 WHERE record_id = ?")) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
        ObjectNode saved = read(connection, id);
        String kind = historyKind(saved, existing);
        if (kind != null) {
            appendHistory(connection, saved, kind);
        }
        return saved;
    }

    private static String historyKind(JsonNode saved, JsonNode existing) {
        if (saved.hasNonNull("cancellation") && !existing.hasNonNull("cancellation")) {
            return "cancelled";
        }
        if ((existing.hasNonNull("cancellation") && !saved.hasNonNull("cancellation"))
                || (existing.hasNonNull("completedAt") && !saved.hasNonNull("completedAt"))) {
            return "reopened";
        }
        if (saved.hasNonNull("completedAt") && !saved.get("completedAt").equals(existing.get("completedAt"))) {
            return "completed";
        }
        return saved.get("result").equals(existing.get("result")) ? null : "result";
    }

    private void appendHistory(Connection connection, ObjectNode task, String kind) throws SQLException {
        List<String> names = projectNames(connection, projectIds(task));
        try (var statement = connection.prepareStatement("""
                INSERT INTO task_history(task_id, revision, kind, recorded_at, project_id, project_name, snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            ObjectNode snapshot = task.deepCopy();
            ArrayNode snapshotNames = snapshot.putArray("projectNames");
            names.forEach(snapshotNames::add);
            String name = String.join(" · ", names);
            statement.setString(1, task.get("id").asString());
            statement.setLong(2, task.get("revision").longValue());
            statement.setString(3, kind);
            statement.setString(4, task.get("updatedAt").asString());
            statement.setString(5, task.get("projectId").asString(null));
            statement.setString(6, name.isEmpty() ? null : name);
            statement.setString(7, json(snapshot));
            statement.executeUpdate();
        }
    }

    private static TaskError notFound() {
        return new TaskError(404, "Задача не найдена. Проверьте номер CC-N или UUID.");
    }
}
