package io.github.dmitryaf.projectsjournal;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.sqlite.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class HistoryService {

    private static final Set<String> QUERY_FIELDS = Set.of("projectId", "kind", "from", "to", "timezone", "cursor", "limit", "groupTasks");
    private static final Set<String> KINDS = Set.of("project", "task", "check", "decision", "file_decision", "observation", "review", "ai_analysis");
    private static final Set<String> PAGE_FIELDS = Set.of("cursor", "limit");
    private static final Set<String> CURSOR_FIELDS = Set.of("day", "time", "key");
    private static final List<String> ENTRY_FIELDS = List.of("key", "kind", "event", "sourceId", "day", "recordedAt",
            "dateQuality", "projectId", "scopeProjectId", "projectName", "title", "excerpt", "href");
    private static final Pattern UTC_TIMESTAMP = Pattern.compile(
            "[0-9]{4}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\\.[0-9]+)?Z");

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final ProjectContextService contexts;
    private final AnalysisPrivacy privacy;

    HistoryService(SqliteStore store, JsonMapper mapper, ProjectContextService contexts, AnalysisPrivacy privacy) {
        this.store = store;
        this.mapper = mapper;
        this.contexts = contexts;
        this.privacy = privacy;
    }

    ObjectNode list(JsonNode input) throws SQLException {
        return list(parse(input), null, false);
    }

    ObjectNode latestProjects(JsonNode input) throws SQLException {
        validateFields(input, Set.of("timezone"));
        return list(parse(input), null, true);
    }

    ObjectNode forReview(ObjectNode review, ObjectNode page) throws SQLException {
        try (var connection = store.open()) {
            return forReview(connection, review, page, null, false);
        }
    }

    ObjectNode forReview(Connection connection, ObjectNode review, ObjectNode page, Set<String> kinds, boolean generalOnly)
            throws SQLException {
        validateFields(page, PAGE_FIELDS);
        var query = page.deepCopy().put("limit", limit(page, 20, 50))
                .put("from", review.get("periodStart").stringValue()).put("to", review.get("periodEnd").stringValue())
                .put("timezone", review.get("timezone").stringValue());
        var scope = review.get("scopeKey").stringValue();
        if (!scope.equals("general")) {
            query.put("projectId", scope.substring("project:".length()));
        }
        return list(connection, parse(query), new ReviewBounds(review.get("startAt").stringValue(), review.get("nextStartAt").stringValue()),
                false, kinds, generalOnly);
    }

    String fileMetadataStamp(String key) throws SQLException {
        for (var file : contexts.decisions()) {
            if (key.equals(fileKey(file.get("key").stringValue())) && file.hasNonNull("metadata")
                    && !file.hasNonNull("error") && file.path("hasContent").booleanValue(false)) {
                var entry = mapper.createObjectNode().put("projectId", file.get("projectId").stringValue());
                entry.set("metadata", file.get("metadata"));
                entry.put("title", file.get("title").stringValue());
                return privacy.digest(entry);
            }
        }
        return null;
    }

    private ObjectNode list(Query query, ReviewBounds bounds, boolean latest) throws SQLException {
        try (var connection = store.open()) {
            return list(connection, query, bounds, latest, null, false);
        }
    }

    private ObjectNode list(Connection connection, Query query, ReviewBounds bounds, boolean latest,
                            Set<String> kinds, boolean generalOnly) throws SQLException {
        var zone = JournalPeriods.zone(query.timezone());
        registerDayFunction(connection, zone);
        var files = fileRows(query, ProjectRow.list(connection));
        List<Object> parameters = new ArrayList<>();
        parameters.add(query.timezone());
        parameters.add(mapper.writeValueAsString(files.rows()));
        var conditions = conditions(query, bounds, parameters, kinds, generalOnly);
        String scopeColumn = latest ? "selectedScope" : "scopeProjectId";
        String source = latest ? "ranked" : "entries";
        String where = latest ? "WHERE position=1" : conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        String sql = HistorySql.ENTRIES + (latest ? HistorySql.LATEST_SCOPED : "") + """
                SELECT key,kind,event,sourceId,day,recordedAt,dateQuality,projectId,%s AS scopeProjectId,projectName,title,
                substr(COALESCE(body,''),1,600) AS excerpt,length(COALESCE(body,''))>600 AS truncated,href
                FROM %s %s ORDER BY day DESC,COALESCE(recordedAt,'') DESC,key DESC %s
                """.formatted(scopeColumn, source, where, latest ? "" : "LIMIT ?");
        if (!latest) {
            parameters.add(query.limit() + 1);
        }
        var items = mapper.createArrayNode();
        String nextCursor = null;
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!latest && items.size() == query.limit()) {
                        var last = items.get(items.size() - 1);
                        nextCursor = mapper.writeValueAsString(mapper.createObjectNode()
                                .put("day", last.get("day").stringValue())
                                .put("time", last.get("recordedAt").asString(""))
                                .put("key", last.get("key").stringValue()));
                        break;
                    }
                    items.add(entry(rows));
                }
            }
        }
        var output = mapper.createObjectNode().put("nextCursor", nextCursor);
        output.set("items", items);
        output.set("files", files.stats());
        return output;
    }

    private List<String> conditions(Query query, ReviewBounds bounds, List<Object> values,
                                    Set<String> kinds, boolean generalOnly) {
        List<String> where = new ArrayList<>();
        if (generalOnly) {
            where.add("scopeProjectId IS NULL");
        }
        if (kinds != null) {
            where.add("kind IN (SELECT value FROM json_each(?))");
            values.add(mapper.writeValueAsString(kinds));
        }
        if (query.hasProject()) {
            if (query.projectId() == null) {
                where.add("projectId IS NULL");
            } else {
                where.add("""
                        (scopeProjectId=? OR (kind='task' AND EXISTS(SELECT 1 FROM task_history h,json_each(%s) links
                        WHERE h.id=CAST(sourceId AS INTEGER) AND links.value=?)))
                        """.formatted(TaskService.projectIdsSql("h.snapshot")));
                values.add(query.projectId());
                values.add(query.projectId());
            }
        }
        if (query.kind() != null) {
            where.add("kind=?");
            values.add(query.kind());
        }
        if (bounds != null) {
            where.add("kind NOT IN ('review','ai_analysis')");
            where.add("((recordedAt IS NOT NULL AND recordedAt>=? AND recordedAt<?) OR (recordedAt IS NULL AND day>=? AND day<=?))");
            values.add(bounds.startAt());
            values.add(bounds.nextStartAt());
            values.add(query.from());
            values.add(query.to());
        } else {
            if (query.from() != null) {
                where.add("day>=?");
                values.add(query.from());
            }
            if (query.to() != null) {
                where.add("day<=?");
                values.add(query.to());
            }
        }
        if (query.groupTasks()) {
            where.add(groupTasks(query, values));
        }
        if (query.cursor() != null) {
            where.add("(day,COALESCE(recordedAt,''),key)<(?,?,?)");
            values.add(query.cursor().day());
            values.add(query.cursor().time());
            values.add(query.cursor().key());
        }
        return where;
    }

    private static String groupTasks(Query query, List<Object> values) {
        var conditions = new StringBuilder();
        if (query.from() != null) {
            conditions.append(" AND history_day(recorded_at,?)>=?");
            values.add(query.timezone());
            values.add(query.from());
        }
        if (query.to() != null) {
            conditions.append(" AND history_day(recorded_at,?)<=?");
            values.add(query.timezone());
            values.add(query.to());
        }
        if (query.hasProject()) {
            if (query.projectId() == null) {
                conditions.append(" AND project_id IS NULL");
            } else {
                conditions.append(" AND EXISTS(SELECT 1 FROM json_each(")
                        .append(TaskService.projectIdsSql("snapshot")).append(") WHERE value=?)");
                values.add(query.projectId());
            }
        }
        return """
                (kind!='task' OR sourceId IN (SELECT CAST(id AS TEXT) FROM (
                  SELECT id,row_number() OVER(PARTITION BY task_id ORDER BY recorded_at DESC,id DESC) AS position
                  FROM task_history WHERE 1=1%s
                ) WHERE position=1))
                """.formatted(conditions);
    }

    private FileRows fileRows(Query query, List<ProjectRow> projects) throws SQLException {
        var names = new HashMap<String, String>();
        int unavailable = 0;
        int notLoaded = 0;
        for (var project : projects) {
            if (!query.includes(project.id())) {
                continue;
            }
            var snapshot = mapper.readTree(project.snapshot());
            var metadata = project.metadata() == null ? snapshot.path("yaml").path("metadata") : mapper.readTree(project.metadata());
            names.put(project.id(), metadata.path("name").asString(snapshot.path("directoryName").asString(null)));
            if (contexts.view(project).path("privateStatus").asString().equals("unavailable")) {
                unavailable++;
            }
            if (!contexts.hasSnapshot(project)) {
                notLoaded++;
            }
        }
        int undated = 0;
        var rows = mapper.createArrayNode();
        for (var decision : contexts.decisions()) {
            var projectId = decision.get("projectId").stringValue();
            if (!query.includes(projectId)) {
                continue;
            }
            boolean error = decision.hasNonNull("error");
            boolean content = decision.path("hasContent").booleanValue(false);
            var metadata = decision.path("metadata");
            boolean dated = metadata.isObject();
            if (error || !content) {
                unavailable++;
            }
            if (!error && !dated) {
                undated++;
            }
            if (error || !content || !dated) {
                continue;
            }
            var id = metadata.get("id").stringValue();
            rows.addObject().put("key", fileKey(decision.get("key").stringValue())).put("sourceId", id)
                    .put("day", metadata.get("date").stringValue()).put("projectId", projectId).put("projectName", names.get(projectId))
                    .put("title", id + " · " + decision.get("title").stringValue()).put("href", "/projects/" + projectId + "#file-decisions");
        }
        return new FileRows(rows, mapper.createObjectNode().put("undated", undated).put("unavailable", unavailable)
                .put("notLoadedProjects", notLoaded));
    }

    private ObjectNode entry(ResultSet rows) throws SQLException {
        var output = mapper.createObjectNode();
        for (var field : ENTRY_FIELDS) {
            output.put(field, rows.getString(field));
        }
        return output.put("truncated", rows.getBoolean("truncated"));
    }

    private Query parse(JsonNode input) {
        validateFields(input, QUERY_FIELDS);
        String project = input.has("projectId") ? TaskPayload.uuid(input.get("projectId"), "projectId", true) : null;
        var kind = optionalText(input, "kind", 20);
        if (kind != null && !KINDS.contains(kind)) {
            throw ApiJson.invalid("kind");
        }
        var from = optionalDay(input, "from");
        var to = optionalDay(input, "to");
        if (from != null && to != null && from.compareTo(to) > 0) {
            throw ApiJson.invalid("to");
        }
        var timezone = input.has("timezone") ? ApiJson.text(input.get("timezone"), "timezone", 1, 100, false) : "UTC";
        var group = optionalText(input, "groupTasks", 6);
        if (group != null && !group.equals("latest")) {
            throw ApiJson.invalid("groupTasks");
        }
        return new Query(project, input.has("projectId"), kind, from, to, timezone, limit(input, 30, 100),
                cursor(optionalText(input, "cursor", 512)), group != null);
    }

    private Cursor cursor(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        JsonNode cursor;
        try {
            cursor = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value);
        } catch (JacksonException invalid) {
            throw new TaskError(400, "Некорректный JSON.");
        }
        validateFields(cursor, CURSOR_FIELDS);
        var day = ApiJson.text(cursor.get("day"), "day", 10, 10, false);
        JournalPeriods.day(day, "day");
        var time = ApiJson.text(cursor.get("time"), "time", 0, 512, false);
        if (!time.isEmpty()) {
            if (!UTC_TIMESTAMP.matcher(time).matches()) {
                throw ApiJson.invalid("time");
            }
            JournalPeriods.day(time.substring(0, 10), "time");
        }
        return new Cursor(day, time, ApiJson.text(cursor.get("key"), "key", 1, 150, false));
    }

    private int limit(JsonNode input, int fallback, int max) {
        var limit = input.get("limit");
        if (limit == null) {
            return fallback;
        }
        if (limit.isString()) {
            limit = mapper.getNodeFactory().numberNode(ApiJson.numberText(limit.stringValue(), "limit"));
        }
        return (int) ApiJson.integer(limit, "limit", 1, max);
    }

    private static void validateFields(JsonNode input, Set<String> fields) {
        ApiJson.requireObject(input);
        if (!fields.containsAll(input.propertyNames())) {
            throw ApiJson.invalid("");
        }
    }

    private static String optionalText(JsonNode input, String field, int max) {
        return input.has(field) ? ApiJson.text(input.get(field), field, 0, max, false) : null;
    }

    private static String optionalDay(JsonNode input, String field) {
        if (!input.has(field)) {
            return null;
        }
        var date = ApiJson.text(input.get(field), field, 10, 10, false);
        JournalPeriods.day(date, field);
        return date;
    }

    private static void registerDayFunction(Connection connection, ZoneId zone) throws SQLException {
        Function.create(connection, "history_day", new Function() {
            @Override
            protected void xFunc() throws SQLException {
                var instant = value_text(0);
                try {
                    result(Instant.parse(instant).atZone(zone).toLocalDate().toString());
                } catch (DateTimeParseException invalid) {
                    error("Некорректное время записи в истории.");
                }
            }
        }, 2, Function.FLAG_DETERMINISTIC);
    }

    private static String fileKey(String value) {
        return "file:" + AnalysisPrivacy.digestText(value);
    }

    private record Query(String projectId, boolean hasProject, String kind, String from, String to, String timezone,
                         int limit, Cursor cursor, boolean groupTasks) {
        boolean includes(String id) {
            return !hasProject || id.equals(projectId);
        }
    }

    private record Cursor(String day, String time, String key) { }

    private record ReviewBounds(String startAt, String nextStartAt) { }

    private record FileRows(ArrayNode rows, ObjectNode stats) { }
}
