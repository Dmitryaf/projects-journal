package io.github.dmitryaf.projectsjournal;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

@Component
final class ReviewService {

    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Set<String> CREATE_FIELDS = Set.of("projectId", "kind", "anchorDate");
    private static final Set<String> PATCH_FIELDS = Set.of("revision", "userText");
    private static final Set<String> DELETE_FIELDS = Set.of("revision");
    private static final Set<String> PAGE_FIELDS = Set.of("cursor", "limit");
    private static final Set<String> CURSOR_FIELDS = Set.of("day", "id");

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final JournalSettings settings;
    private final ObjectReader cursorReader;

    ReviewService(SqliteStore store, JsonMapper mapper, JournalSettings settings) {
        this.store = store;
        this.mapper = mapper;
        this.settings = settings;
        this.cursorReader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    ObjectNode read(String id) throws SQLException {
        try (var connection = store.open()) {
            return read(connection, id);
        }
    }

    ObjectNode read(Connection connection, String id) throws SQLException {
        validateId(id);
        try (var statement = connection.prepareStatement("SELECT * FROM reviews WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskError(404, "Обзор не найден.");
                }
                return summary(rows).put("startAt", rows.getString("start_at"))
                        .put("nextStartAt", rows.getString("next_start_at")).put("userText", rows.getString("user_text"))
                        .put("revision", rows.getLong("revision")).put("createdAt", rows.getString("created_at"))
                        .put("updatedAt", rows.getString("updated_at"));
            }
        }
    }

    ObjectNode latestProject(String projectId) throws SQLException {
        validateId(projectId);
        try (var connection = store.open();
             var statement = connection.prepareStatement("""
                     SELECT r.id,r.period_kind,r.period_start,r.period_end,
                       substr(CASE WHEN trim(r.user_text)<>'' THEN r.user_text
                         ELSE json_extract(a.report_json,'$.narrative') END,1,600) AS excerpt,
                       CASE WHEN trim(r.user_text)<>'' THEN NULL ELSE a.id END AS analysis_id
                     FROM reviews r LEFT JOIN analysis_runs a ON a.id=(
                       SELECT id FROM analysis_runs WHERE review_id=r.id AND status='succeeded'
                         AND trim(COALESCE(json_extract(report_json,'$.narrative'),''))<>''
                       ORDER BY created_at DESC,id DESC LIMIT 1)
                     WHERE r.scope_key=? AND (trim(r.user_text)<>'' OR a.id IS NOT NULL)
                     ORDER BY r.period_end DESC,r.period_start DESC,r.updated_at DESC,r.id DESC LIMIT 1
                     """)) {
            statement.setString(1, "project:" + projectId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? mapper.createObjectNode().put("id", rows.getString("id"))
                        .put("kind", rows.getString("period_kind")).put("periodStart", rows.getString("period_start"))
                        .put("periodEnd", rows.getString("period_end")).put("excerpt", rows.getString("excerpt"))
                        .put("analysisId", rows.getString("analysis_id")) : null;
            }
        }
    }

    ObjectNode create(JsonNode input) throws SQLException {
        return create(input, null);
    }

    ObjectNode createScheduled(String projectId, String kind, String anchorDate, String timezone) throws SQLException {
        return create(mapper.createObjectNode().put("projectId", projectId).put("kind", kind)
                .put("anchorDate", anchorDate), timezone);
    }

    private ObjectNode create(JsonNode input, String scheduledTimezone) throws SQLException {
        checkFields(input, CREATE_FIELDS);
        String projectId = TaskPayload.uuid(input.get("projectId"), "projectId", true);
        String kind = ApiJson.text(input.get("kind"), "kind", 4, 5, false);
        String anchor = ApiJson.text(input.get("anchorDate"), "anchorDate", 10, 10, false);
        return store.write(connection -> {
            String timezone = scheduledTimezone == null
                    ? settings.read(connection).get("timezone").asString()
                    : scheduledTimezone;
            var bounds = JournalPeriods.bounds(kind, anchor, timezone);
            String scope = projectId == null ? "general" : "project:" + projectId;
            String existing = existingId(connection, scope, kind, bounds.periodStart());
            if (existing != null) {
                return read(connection, existing);
            }

            String projectName = null;
            if (projectId != null) {
                ProjectRow project = ProjectRow.find(connection, projectId);
                if (project == null) {
                    throw new TaskError(400, "Проект не найден. Выберите существующий проект или общий обзор.");
                }
                projectName = projectName(project);
            }

            String id = UUID.randomUUID().toString();
            String now = now();
            try (var statement = connection.prepareStatement("""
                    INSERT INTO reviews(id,project_id,scope_key,project_name,period_kind,period_start,period_end,
                        timezone,start_at,next_start_at,user_text,revision,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,'',1,?,?)
                    """)) {
                statement.setString(1, id);
                statement.setString(2, projectId);
                statement.setString(3, scope);
                statement.setString(4, projectName);
                statement.setString(5, kind);
                statement.setString(6, bounds.periodStart());
                statement.setString(7, bounds.periodEnd());
                statement.setString(8, bounds.timezone());
                statement.setString(9, bounds.startAt());
                statement.setString(10, bounds.nextStartAt());
                statement.setString(11, now);
                statement.setString(12, now);
                statement.executeUpdate();
            }
            return read(connection, id);
        });
    }

    private String projectName(ProjectRow project) {
        JsonNode snapshot = mapper.readTree(project.snapshot());
        JsonNode metadata = project.metadata() == null ? snapshot.path("yaml").path("metadata")
                : mapper.readTree(project.metadata());
        return metadata.path("name").asString(snapshot.path("directoryName").asString(null));
    }

    private static String existingId(Connection connection, String scope, String kind, String periodStart)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT id,period_start,timezone,start_at FROM reviews
                WHERE scope_key=? AND period_kind=? AND period_start BETWEEN ? AND ?
                ORDER BY period_start DESC
                """)) {
            statement.setString(1, scope);
            statement.setString(2, kind);
            statement.setString(3, LocalDate.parse(periodStart).minusDays(2).toString());
            statement.setString(4, periodStart);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (periodStart.equals(rows.getString("period_start")) || hasHistoricalOffset(rows)) {
                        return rows.getString("id");
                    }
                }
                return null;
            }
        }
    }

    private static boolean hasHistoricalOffset(ResultSet row) throws SQLException {
        // Older releases could shift saved period starts by one or two days in zones with sub-minute offsets.
        // Consecutive weeks or months cannot start that close together.
        try {
            var zone = JournalPeriods.zone(row.getString("timezone"));
            var instant = Instant.parse(row.getString("start_at"));
            return zone.getRules().getOffset(instant).getTotalSeconds() % 60 != 0;
        } catch (DateTimeException | TaskError invalid) {
            return false;
        }
    }

    ObjectNode patch(String id, JsonNode input) throws SQLException {
        checkFields(input, PATCH_FIELDS);
        long revision = revision(input);
        String text = ApiJson.text(input.get("userText"), "userText", 0, 60_000, false);
        return store.write(connection -> {
            checkRevision(read(connection, id), revision);
            try (var statement = connection.prepareStatement("""
                    UPDATE reviews SET user_text=?,revision=revision+1,updated_at=? WHERE id=?
                    """)) {
                statement.setString(1, text);
                statement.setString(2, now());
                statement.setString(3, id);
                statement.executeUpdate();
            }
            return read(connection, id);
        });
    }

    void delete(String id, JsonNode input) throws SQLException {
        checkFields(input, DELETE_FIELDS);
        long revision = revision(input);
        store.write(connection -> {
            checkRevision(read(connection, id), revision);
            try (var statement = connection.prepareStatement("""
                    UPDATE analysis_runs SET status='cancelled',error=?,finished_at=?
                    WHERE review_id=? AND status='pending'
                    """)) {
                statement.setString(1, "Обзор удалён до отправки.");
                statement.setString(2, now());
                statement.setString(3, id);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("DELETE FROM reviews WHERE id=?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    ObjectNode related(String id, JsonNode input) throws SQLException {
        try (var connection = store.open()) {
            connection.setAutoCommit(false);
            ObjectNode current = read(connection, id);
            checkFields(input, PAGE_FIELDS);
            int limit = pageLimit(input);
            Cursor cursor = pageCursor(input);
            String start = current.get("periodStart").asString();
            String end = current.get("periodEnd").asString();
            String scope = current.get("scopeKey").asString();
            List<String> clauses = new ArrayList<>(List.of("id<>?", "period_start<=?", "period_end>=?"));
            List<String> parameters = new ArrayList<>(List.of(id, end, start));
            if (!"general".equals(scope)) {
                clauses.add("scope_key=?");
                clauses.add("period_kind='week'");
                parameters.add(scope);
            } else if ("week".equals(current.get("kind").asString())) {
                clauses.add("scope_key<>'general'");
                clauses.add("period_kind='week'");
            } else {
                clauses.add("(period_kind='week' OR (period_kind='month' AND scope_key<>'general'))");
            }
            if (cursor != null) {
                clauses.add("(period_start,id)<(?,?)");
                parameters.add(cursor.day());
                parameters.add(cursor.id());
            }

            ObjectNode result = mapper.createObjectNode().putNull("nextCursor");
            var items = result.putArray("items");
            try (var statement = connection.prepareStatement("SELECT * FROM reviews WHERE "
                    + String.join(" AND ", clauses) + " ORDER BY period_start DESC,id DESC LIMIT ?")) {
                for (int index = 0; index < parameters.size(); index++) {
                    statement.setString(index + 1, parameters.get(index));
                }
                statement.setInt(parameters.size() + 1, limit + 1);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if (items.size() == limit) {
                            JsonNode last = items.get(limit - 1);
                            ObjectNode next = mapper.createObjectNode().set("day", last.get("periodStart"))
                                    .set("id", last.get("id"));
                            result.put("nextCursor", mapper.writeValueAsString(next));
                            break;
                        }
                        boolean overlaps = rows.getString("period_start").compareTo(start) < 0
                                || rows.getString("period_end").compareTo(end) > 0;
                        items.add(summary(rows).put("overlapsBoundary", overlaps));
                    }
                }
            }
            connection.commit();
            return result;
        }
    }

    private int pageLimit(JsonNode input) {
        JsonNode value = input.get("limit");
        if (value == null) {
            return 20;
        }
        if (value.isString()) {
            value = DecimalNode.valueOf(ApiJson.numberText(value.asString(), "limit"));
        } else if (value.isBoolean() && value.booleanValue()) {
            return 1;
        }
        return (int) ApiJson.integer(value, "limit", 1, 50);
    }

    private Cursor pageCursor(JsonNode input) {
        if (!input.has("cursor")) {
            return null;
        }
        String cursor = ApiJson.text(input.get("cursor"), "cursor", 0, 512, false);
        if (cursor.isEmpty()) {
            return null;
        }
        JsonNode parsed;
        try {
            parsed = cursorReader.readTree(cursor);
            if (parsed.isMissingNode()) {
                throw new TaskError(400, "Некорректный JSON.");
            }
        } catch (JacksonException invalid) {
            throw new TaskError(400, "Некорректный JSON.");
        }
        checkFields(parsed, CURSOR_FIELDS);
        String day = JournalPeriods.day(parsed.path("day").asString(null), "day").toString();
        return new Cursor(day, TaskPayload.uuid(parsed.get("id"), "id", false));
    }

    private ObjectNode summary(ResultSet row) throws SQLException {
        return mapper.createObjectNode().put("id", row.getString("id"))
                .put("projectId", row.getString("project_id")).put("scopeKey", row.getString("scope_key"))
                .put("projectName", row.getString("project_name")).put("kind", row.getString("period_kind"))
                .put("periodStart", row.getString("period_start")).put("periodEnd", row.getString("period_end"))
                .put("timezone", row.getString("timezone"));
    }

    private static void validateId(String id) {
        TaskPayload.uuid(StringNode.valueOf(id), "", false);
    }

    private static void checkFields(JsonNode input, Set<String> fields) {
        ApiJson.requireObject(input);
        if (!fields.containsAll(input.propertyNames())) {
            throw ApiJson.invalid("");
        }
    }

    private static long revision(JsonNode input) {
        return ApiJson.integer(input.get("revision"), "revision", 1, MAX_SAFE_INTEGER);
    }

    private static void checkRevision(JsonNode current, long revision) {
        if (current.get("revision").longValue() != revision) {
            throw new TaskError(409, "Обзор уже изменён. Сравните сохранённый текст с черновиком; текущий ввод не записан.");
        }
    }

    private static String now() {
        return JournalPeriods.utc(Instant.now());
    }

    private record Cursor(String day, String id) {
    }
}
