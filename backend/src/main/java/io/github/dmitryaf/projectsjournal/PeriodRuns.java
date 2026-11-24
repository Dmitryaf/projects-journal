package io.github.dmitryaf.projectsjournal;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

@Component
final class PeriodRuns {

    private static final Set<String> PAGE_FIELDS = Set.of("limit", "cursor");
    private static final Set<String> RECENT_FIELDS = Set.of("projectIds", "view");
    private static final Set<String> IMPORT_FIELDS = Set.of("purpose", "projectId", "projectIds", "reviewId", "requestKey",
            "context", "report", "confirm");
    private static final int MAX_INPUT_BYTES = 80_000;
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final ReviewService reviews;
    private final PeriodContext context;
    private final PeriodReport reports;
    private final AnalysisPrivacy privacy;
    private final ObjectReader cursorReader;

    PeriodRuns(SqliteStore store, JsonMapper mapper, ReviewService reviews, PeriodContext context, PeriodReport reports,
               AnalysisPrivacy privacy) {
        this.store = store;
        this.mapper = mapper;
        this.reviews = reviews;
        this.context = context;
        this.reports = reports;
        this.privacy = privacy;
        this.cursorReader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    void recover(Connection connection, String now) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE analysis_runs SET status='unknown',finished_at=?,error=?,owner_id=NULL,lease_until=NULL
                WHERE status='running' AND lease_until<=?
                """)) {
            statement.setString(1, now);
            statement.setString(2, "Сервер не сохранил результат до истечения ожидания. Запрос мог быть оплачен; "
                    + "автоматического повтора нет.");
            statement.setString(3, now);
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                UPDATE analysis_runs SET status='cancelled',finished_at=?,error=?
                WHERE status='pending' AND expires_at<=?
                """)) {
            statement.setString(1, now);
            statement.setString(2, "Пакет истёк. Подготовьте и просмотрите новый.");
            statement.setString(3, now);
            statement.executeUpdate();
        }
    }

    private void recover() throws SQLException {
        store.write(connection -> {
            recover(connection, JournalPeriods.utc(Instant.now()));
            return null;
        });
    }

    ObjectNode read(String id) throws SQLException {
        recover();
        try (var connection = store.open()) {
            return read(connection, id);
        }
    }

    ObjectNode read(Connection connection, String id) throws SQLException {
        TaskPayload.uuid(StringNode.valueOf(id), "", false);
        try (var statement = connection.prepareStatement("SELECT * FROM analysis_runs WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskError(404, "Сохранённый анализ не найден.");
                }
                JsonNode payload = mapper.readTree(rows.getString("payload_json"));
                return summary(rows, payload).set("selection", mapper.readTree(rows.getString("selection_json")))
                        .set("payload", payload).set("report", readNullable(rows.getString("report_json")))
                        .put("rawOutput", rows.getString("raw_output"));
            }
        }
    }

    ObjectNode list(String reviewId, JsonNode input) throws SQLException {
        TaskPayload.uuid(StringNode.valueOf(reviewId), "", false);
        recover();
        checkFields(input, PAGE_FIELDS);
        int limit = limit(input.get("limit"));
        Long before = beforeGeneration(input.get("cursor"));
        String sql = "SELECT * FROM analysis_runs WHERE review_key=?"
                + (before == null ? "" : " AND generation<?") + " ORDER BY generation DESC LIMIT ?";
        ObjectNode result = mapper.createObjectNode();
        ArrayNode items = result.putArray("items");
        result.putNull("nextCursor");
        try (var connection = store.open(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, reviewId);
            int position = 2;
            if (before != null) {
                statement.setLong(position++, before);
            }
            statement.setInt(position, limit + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (items.size() == limit) {
                        result.put("nextCursor", privacy.json(mapper.createObjectNode()
                                .set("generation", items.get(limit - 1).path("generation"))));
                        break;
                    }
                    items.add(summary(rows, mapper.readTree(rows.getString("payload_json"))));
                }
            }
        }
        return result;
    }

    private static int limit(JsonNode value) {
        if (value == null) {
            return 10;
        }
        if (value.isString()) {
            value = DecimalNode.valueOf(ApiJson.numberText(value.asString(), "limit"));
        } else if (value.isBoolean() && value.booleanValue()) {
            return 1;
        }
        return (int) ApiJson.integer(value, "limit", 1, 30);
    }

    private Long beforeGeneration(JsonNode value) {
        if (value == null) {
            return null;
        }
        String raw = ApiJson.text(value, "cursor", 0, 200, false);
        if (raw.isEmpty()) {
            return null;
        }
        JsonNode cursor;
        try {
            cursor = cursorReader.readTree(raw);
            if (cursor.isMissingNode()) {
                throw new TaskError(400, "Некорректный JSON.");
            }
        } catch (JacksonException invalid) {
            throw new TaskError(400, "Некорректный JSON.");
        }
        checkFields(cursor, Set.of("generation"));
        return ApiJson.integer(cursor.get("generation"), "generation", 1, MAX_SAFE_INTEGER);
    }

    ObjectNode recent(JsonNode input) throws SQLException {
        checkFields(input, RECENT_FIELDS);
        List<String> ids = new ArrayList<>();
        if (input.has("projectIds")) {
            String value = ApiJson.text(input.get("projectIds"), "projectIds", 0, 400, false);
            String[] parts = value.split(",", -1);
            if (parts.length > 10) {
                throw ApiJson.invalid("projectIds");
            }
            for (String id : parts) {
                ids.add(TaskPayload.uuid(StringNode.valueOf(id), "projectIds", false));
            }
        }
        boolean discussions = input.has("view");
        if (discussions && !"discussions".equals(input.get("view").stringValue())) {
            throw ApiJson.invalid("view");
        }
        recover();
        String filter = ids.isEmpty() ? "" : "AND EXISTS (SELECT 1 FROM json_each(payload_json,'$.scope.projectIds')"
                + " WHERE value IN (" + String.join(",", Collections.nCopies(ids.size(), "?")) + "))";
        String sql = discussions ? """
                WITH turns AS (
                    SELECT id,generation,json_extract(payload_json,'$.origin.question') AS question,
                        coalesce(json_extract(payload_json,'$.origin.sessionId'),id) AS discussion
                    FROM analysis_runs
                    WHERE (status='succeeded' OR json_extract(payload_json,'$.origin.kind')='codex_runtime') %s
                ), ranked AS (
                    SELECT id,first_value(question) OVER (PARTITION BY discussion ORDER BY generation) AS title,
                        row_number() OVER (PARTITION BY discussion ORDER BY generation DESC) AS position
                    FROM turns
                )
                SELECT analysis_runs.*,ranked.title FROM ranked JOIN analysis_runs USING (id)
                WHERE position=1 ORDER BY created_at DESC,id DESC LIMIT 11
                """.formatted(filter) : """
                SELECT *, NULL AS title FROM analysis_runs WHERE status='succeeded' %s
                ORDER BY created_at DESC,id DESC LIMIT 11
                """.formatted(filter);
        ObjectNode result = mapper.createObjectNode();
        ArrayNode items = result.putArray("items");
        boolean truncated = false;
        try (var connection = store.open(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < ids.size(); index++) {
                statement.setString(index + 1, ids.get(index));
            }
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (items.size() == 10) {
                        truncated = true;
                        break;
                    }
                    JsonNode payload = mapper.readTree(rows.getString("payload_json"));
                    String title = rows.getString("title");
                    if (title == null || title.isEmpty()) {
                        title = "codex_manual".equals(payload.path("origin").path("kind").asString())
                                ? "Анализ Codex" : "Обзор периода";
                    }
                    items.add(summary(rows, payload).put("projectName", projectName(rows, payload)).put("title", title));
                }
            }
        }
        return result.put("truncated", truncated);
    }

    private static String projectName(ResultSet row, JsonNode payload) throws SQLException {
        String name = payload.path("scope").path("name").asString("");
        if (!name.isEmpty()) {
            return name;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode source : payload.path("sources")) {
            if ("project".equals(source.path("kind").asString())) {
                names.add(source.path("title").asString(""));
            }
        }
        String joined = String.join(", ", names);
        if (!joined.isEmpty()) {
            return joined;
        }
        return "general".equals(row.getString("scope_key")) ? "Общий обзор" : "Прежний проект";
    }

    ObjectNode importCodex(JsonNode input) throws SQLException {
        ObjectNode data = manualInput(input);
        String contextText = data.path("context").asString();
        JsonNode report = data.get("report");
        if (privacy.bytes(mapper.createObjectNode().put("context", contextText).set("report", report)) > MAX_INPUT_BYTES) {
            throw new TaskError(400, "Анализ и основания больше 80 000 байт.");
        }
        if (AnalysisPrivacy.sensitiveText(contextText)) {
            throw new TaskError(400,
                    "В основаниях обнаружен локальный путь или возможный секрет. Уберите его перед сохранением.");
        }
        String requestHash = privacy.digest(mapper.createObjectNode().put("origin", "codex_manual").setAll(data));
        List<String> projectIds = selectedProjects(data);
        return store.write(connection -> importCodex(connection, data, contextText, report, requestHash, projectIds));
    }

    private ObjectNode importCodex(Connection connection, ObjectNode data, String contextText, JsonNode report,
                                   String requestHash, List<String> projectIds) throws SQLException {
        Instant now = Instant.now();
        String timestamp = JournalPeriods.utc(now);
        String requestKey = data.path("requestKey").asString();
        String previous = previousImport(connection, requestKey, requestHash);
        if (previous != null) {
            recover(connection, timestamp);
            return read(connection, previous);
        }
        String reviewId = data.path("reviewId").asString();
        ObjectNode review = reviews.read(connection, reviewId);
        String scope = review.path("scopeKey").asString();
        if (!"general".equals(scope) && (projectIds.size() != 1 || !scope.equals("project:" + projectIds.getFirst())
                || !projectIds.getFirst().equals(review.path("projectId").asString(null)))) {
            throw new TaskError(400,
                    "Выберите обзор того же действующего проекта или общий обзор для нескольких проектов.");
        }
        ArrayNode projects = mapper.createArrayNode();
        projectIds.forEach(projects::add);
        ObjectNode selection = context.normalizeSelection(mapper.createObjectNode().set("projectIds", projects)
                .put("includeGeneral", false).set("sourceKinds", mapper.createArrayNode()).put("includeMetadata", true)
                .put("includeOpenTasks", false).put("includeUserText", false).put("includeRelatedReviews", false)
                .put("includeRelatedAnalyses", false).set("codexDigestIds", mapper.createArrayNode()));
        ObjectNode payload = context.build(connection, review, selection, now, false).payload();
        payload.putObject("origin").put("kind", "codex_manual").put("purpose", "project_review");
        payload.withArrayProperty("sources").addObject().put("id", "codex_context:" + requestKey)
                .put("kind", "codex_manual_context").put("projectId", projectIds.size() == 1 ? projectIds.getFirst() : null)
                .put("title", "Контекст выбранных проектов для ручного анализа Codex").put("text", contextText)
                .putNull("day").putNull("recordedAt").put("dateQuality", "external_context").put("partial", false);
        payload.withArrayProperty("limitations")
                .add("Готовый текст внесён вручную из Codex. Этот пакет не отправлялся в OpenAI API; "
                        + "исходный prompt, модель и токены внешнего сеанса не установлены.")
                .add("Внешние основания внесены автором импорта, не проверены приложением независимо и не "
                        + "реконструируют состояние проекта в прошлом.");
        if (privacy.bytes(payload) > MAX_INPUT_BYTES) {
            throw new TaskError(400, "Пакет больше 80 000 байт.");
        }
        reports.validate(payload, report);

        String id = UUID.randomUUID().toString();
        long generation = nextGeneration(connection, reviewId);
        String packetHash = privacy.digest(mapper.createObjectNode().set("payload", payload).put("model", "")
                .put("policy", PeriodContext.POLICY));
        try (var statement = connection.prepareStatement("""
                INSERT INTO analysis_runs(id,review_id,review_key,scope_key,generation,status,model,policy_version,
                    selection_json,payload_json,guards_json,packet_hash,request_key,request_hash,created_at,expires_at,
                    finished_at,trigger,report_json,raw_output)
                VALUES(?,?,?,?,?,'succeeded','',?,?,?,?,?,?,?,?,?,?,'manual',?,?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, reviewId);
            statement.setString(3, reviewId);
            statement.setString(4, scope);
            statement.setLong(5, generation);
            statement.setString(6, PeriodContext.POLICY);
            statement.setString(7, privacy.json(selection));
            statement.setString(8, privacy.json(payload));
            statement.setString(9, "[]");
            statement.setString(10, packetHash);
            statement.setString(11, requestKey);
            statement.setString(12, requestHash);
            statement.setString(13, timestamp);
            statement.setString(14, timestamp);
            statement.setString(15, timestamp);
            String encodedReport = privacy.json(report);
            statement.setString(16, encodedReport);
            statement.setString(17, encodedReport);
            statement.executeUpdate();
        }
        recover(connection, timestamp);
        return read(connection, id);
    }

    private ObjectNode manualInput(JsonNode input) {
        checkFields(input, IMPORT_FIELDS);
        if (!"project_review".equals(input.path("purpose").stringValue())) {
            throw ApiJson.invalid("purpose");
        }
        ObjectNode data = mapper.createObjectNode().put("purpose", "project_review");
        if (input.has("projectId") == input.has("projectIds")) {
            throw ApiJson.invalid("");
        }
        if (input.has("projectId")) {
            data.put("projectId", TaskPayload.uuid(input.get("projectId"), "projectId", false));
        } else {
            JsonNode ids = input.get("projectIds");
            if (!ids.isArray() || ids.isEmpty() || ids.size() > 10) {
                throw ApiJson.invalid("projectIds");
            }
            ArrayNode projects = data.putArray("projectIds");
            for (int index = 0; index < ids.size(); index++) {
                projects.add(TaskPayload.uuid(ids.get(index), "projectIds." + index, false));
            }
        }
        data.put("reviewId", TaskPayload.uuid(input.get("reviewId"), "reviewId", false));
        data.put("requestKey", TaskPayload.uuid(input.get("requestKey"), "requestKey", false));
        data.put("context", ApiJson.text(input.get("context"), "context", 1, 20_000, true));
        data.set("report", reports.normalize(input.get("report")));
        JsonNode confirm = input.path("confirm");
        if (!confirm.isBoolean() || !confirm.booleanValue()) {
            throw ApiJson.invalid("confirm");
        }
        return data.put("confirm", true);
    }

    private static List<String> selectedProjects(JsonNode data) {
        if (data.has("projectId")) {
            return List.of(data.get("projectId").asString());
        }
        Set<String> ids = new TreeSet<>();
        data.path("projectIds").forEach(value -> ids.add(value.asString()));
        return List.copyOf(ids);
    }

    private static String previousImport(Connection connection, String requestKey, String requestHash) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id,request_hash FROM analysis_runs WHERE request_key=?")) {
            statement.setString(1, requestKey);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                if (!requestHash.equals(rows.getString("request_hash"))) {
                    throw new TaskError(409, "Этот ключ уже использован для другого анализа.");
                }
                return rows.getString("id");
            }
        }
    }

    static long nextGeneration(Connection connection, String reviewKey) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT COALESCE(MAX(generation),0)+1 FROM analysis_runs WHERE review_key=?
                """)) {
            statement.setString(1, reviewKey);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 1;
            }
        }
    }

    private ObjectNode summary(ResultSet row, JsonNode payload) throws SQLException {
        return mapper.createObjectNode().put("origin", payload.path("origin").path("kind").asString("openai"))
                .put("id", row.getString("id")).put("reviewId", row.getString("review_id"))
                .put("reviewKey", row.getString("review_key")).put("scopeKey", row.getString("scope_key"))
                .put("generation", row.getLong("generation")).put("trigger", row.getString("trigger"))
                .put("approvedAt", row.getString("approved_at")).put("status", row.getString("status"))
                .put("model", row.getString("model")).put("packetHash", row.getString("packet_hash"))
                .put("createdAt", row.getString("created_at")).put("expiresAt", row.getString("expires_at"))
                .put("startedAt", row.getString("started_at")).put("finishedAt", row.getString("finished_at"))
                .put("error", row.getString("error")).put("cancelRequested", row.getBoolean("cancel_requested"))
                .set("usage", readNullable(row.getString("usage_json"))).put("responseId", row.getString("response_id"));
    }

    private JsonNode readNullable(String json) {
        return json == null || json.isEmpty() ? mapper.nullNode() : mapper.readTree(json);
    }

    private static void checkFields(JsonNode input, Set<String> fields) {
        ApiJson.requireObject(input);
        if (!fields.containsAll(input.propertyNames())) {
            throw ApiJson.invalid("");
        }
    }
}
