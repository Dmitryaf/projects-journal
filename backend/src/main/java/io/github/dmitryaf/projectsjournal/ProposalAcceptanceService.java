package io.github.dmitryaf.projectsjournal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Set;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class ProposalAcceptanceService {

    private static final Set<String> APPROVAL_FIELDS = Set.of("kind", "projectId", "title", "description",
            "reason", "uncertainty", "cheapCheck", "expectedResult", "acceptance", "priority", "allowDuplicate", "consent");
    private static final String SELECT = """
            SELECT a.*,EXISTS(SELECT 1 FROM records r WHERE r.id=a.result_id
              AND r.kind=CASE WHEN a.kind='task' THEN 'tasks' ELSE 'ideas' END) AS result_exists
            FROM analysis_acceptances a
            """;

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final AnalysisPrivacy privacy;
    private final PeriodRuns runs;
    private final TaskService tasks;
    private final JournalRecords records;

    ProposalAcceptanceService(SqliteStore store, JsonMapper mapper, AnalysisPrivacy privacy, PeriodRuns runs,
                              TaskService tasks, JournalRecords records) {
        this.store = store;
        this.mapper = mapper;
        this.privacy = privacy;
        this.runs = runs;
        this.tasks = tasks;
        this.records = records;
    }

    ObjectNode read(String id) throws SQLException {
        uuid(id);
        try (var connection = store.open()) {
            return read(connection, id);
        }
    }

    JsonNode forRun(String id) throws SQLException {
        uuid(id);
        var result = mapper.createArrayNode();
        try (var connection = store.open();
             var statement = connection.prepareStatement(SELECT + " WHERE a.run_key=? ORDER BY a.proposal_index")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(dto(rows));
                }
            }
        }
        return result;
    }

    ObjectNode list(JsonNode input) throws SQLException {
        ApiJson.requireObject(input);
        if (!Set.of("cursor", "limit").containsAll(input.propertyNames())) {
            throw ApiJson.invalid("");
        }
        var cursor = input.has("cursor") ? TaskPayload.uuid(input.get("cursor"), "cursor", false) : null;
        int limit = input.has("limit") ? (int) ApiJson.integer(mapper.getNodeFactory().numberNode(
                ApiJson.numberText(ApiJson.text(input.get("limit"), "limit", 0, 100, false), "limit")), "limit", 1, 30) : 20;
        var output = mapper.createObjectNode().putNull("nextCursor");
        var items = output.putArray("items");
        try (var connection = store.open();
             var statement = connection.prepareStatement(SELECT + (cursor == null ? "" : " WHERE a.id>?") + " ORDER BY a.id LIMIT ?")) {
            if (cursor != null) {
                statement.setString(1, cursor);
            }
            statement.setInt(cursor == null ? 1 : 2, limit + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (items.size() == limit) {
                        output.put("nextCursor", items.get(items.size() - 1).get("id").stringValue());
                        break;
                    }
                    items.add(dto(rows));
                }
            }
        }
        return output;
    }

    ObjectNode links(Connection connection) throws SQLException {
        var output = mapper.createObjectNode();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT a.id,a.result_id FROM analysis_acceptances a JOIN records r
                ON r.id=a.result_id AND r.kind=CASE WHEN a.kind='task' THEN 'tasks' ELSE 'ideas' END
                """)) {
            while (rows.next()) {
                output.put(rows.getString("result_id"), rows.getString("id"));
            }
        }
        return output;
    }

    ObjectNode accept(String runId, String rawIndex, JsonNode input) throws SQLException {
        uuid(runId);
        int index = (int) ApiJson.integer(mapper.getNodeFactory().numberNode(ApiJson.numberText(rawIndex, "index")), "index", 0, 7);
        var approved = approval(input);
        String approvedJson = privacy.json(approved);
        return store.write(connection -> {
            var existing = existing(connection, runId, index, approvedJson);
            if (existing != null) {
                return existing;
            }
            var run = runs.read(connection, runId);
            var proposal = run.path("report").path("proposals").path(index);
            if (!run.path("status").asString().equals("succeeded") || !proposal.isObject()) {
                throw new TaskError(409, "Для принятия нужен сохранённый успешный анализ и существующее предложение.");
            }
            var projectId = approved.get("projectId").asString(null);
            var projectIds = JournalSettings.strings(proposal.path("projectIds"));
            if (projectId == null ? !projectIds.isEmpty() : !projectIds.contains(projectId)) {
                throw new TaskError(400, "Выберите один из проектов предложения. Общая запись разрешена только для общего предложения.");
            }
            if (projectId != null && ProjectRow.find(connection, projectId) == null) {
                throw new TaskError(410, "Проект удалён. Предложение сохраняется только для чтения.");
            }
            var sources = new HashMap<String, JsonNode>();
            run.path("payload").path("sources").forEach(source -> sources.put(source.path("id").asString(), source));
            var original = mapper.createObjectNode().put("origin", run.path("origin").asString("openai"))
                    .put("reviewKey", run.get("reviewKey").stringValue());
            original.set("proposal", proposal);
            var evidence = original.putArray("sources");
            for (var sourceId : proposal.path("sources")) {
                var source = sources.get(sourceId.stringValue());
                if (source == null) {
                    throw new TaskError(409, "Основания предложения не входят в сохранённый пакет.");
                }
                evidence.add(source);
            }
            original.put("model", run.get("model").stringValue()).put("packetHash", run.get("packetHash").stringValue());
            original.set("period", run.path("payload").path("period"));

            var id = acceptanceId(runId, index);
            var uncertainty = approved.get("uncertainty").stringValue();
            var description = String.join("\n\n", approved.get("description").stringValue(),
                    "Основание: " + approved.get("reason").stringValue(),
                    "Неизвестное: " + (uncertainty.isEmpty() ? "Не указано" : uncertainty),
                    "Дешёвая проверка: " + approved.get("cheapCheck").stringValue(),
                    "Редакция и источники: /proposal-acceptances?id=" + id);
            var kind = approved.get("kind").stringValue();
            var data = mapper.createObjectNode().put("title", approved.get("title").stringValue())
                    .put("projectId", projectId).put("description", description);
            ObjectNode result;
            if (kind.equals("task")) {
                for (var field : Set.of("expectedResult", "acceptance", "priority", "allowDuplicate")) {
                    data.set(field, approved.get(field));
                }
                data.put("requestKey", "period-proposal:" + runId + ":" + index);
                result = tasks.create(connection, data);
            } else {
                result = records.save(connection, "ideas", null, data);
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO analysis_acceptances(id,analysis_run_id,run_key,proposal_index,kind,result_id,approved_json,original_json,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """)) {
                statement.setString(1, id);
                statement.setString(2, runId);
                statement.setString(3, runId);
                statement.setInt(4, index);
                statement.setString(5, kind);
                statement.setString(6, result.get("id").stringValue());
                statement.setString(7, approvedJson);
                statement.setString(8, privacy.json(original));
                statement.setString(9, JournalPeriods.utc(Instant.now()));
                statement.executeUpdate();
            }
            return read(connection, id);
        });
    }

    private ObjectNode read(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement(SELECT + " WHERE a.id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskError(404, "Принятое предложение не найдено.");
                }
                return dto(rows);
            }
        }
    }

    private ObjectNode existing(Connection connection, String runId, int index, String approvedJson) throws SQLException {
        try (var statement = connection.prepareStatement(SELECT + " WHERE a.run_key=? AND a.proposal_index=?")) {
            statement.setString(1, runId);
            statement.setInt(2, index);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                if (!privacy.json(mapper.readTree(rows.getString("approved_json"))).equals(approvedJson)) {
                    throw new TaskError(409, "Это предложение уже принято с другой редакцией. Откройте сохранённую запись; исходная задача или идея не изменена.");
                }
                return dto(rows);
            }
        }
    }

    private ObjectNode dto(ResultSet row) throws SQLException {
        var kind = row.getString("kind");
        var resultId = row.getString("result_id");
        var dto = mapper.createObjectNode().put("id", row.getString("id")).put("runId", row.getString("analysis_run_id"))
                .put("runKey", row.getString("run_key")).put("proposalIndex", row.getInt("proposal_index")).put("kind", kind)
                .put("resultId", resultId).put("resultExists", row.getBoolean("result_exists"))
                .put("resultHref", (kind.equals("task") ? "/tasks?task=" : "/ideas#idea-") + resultId)
                .put("createdAt", row.getString("created_at"));
        dto.set("approved", mapper.readTree(row.getString("approved_json")));
        dto.set("original", mapper.readTree(row.getString("original_json")));
        return dto;
    }

    private ObjectNode approval(JsonNode input) {
        ApiJson.requireObject(input);
        if (!APPROVAL_FIELDS.containsAll(input.propertyNames())) {
            throw ApiJson.invalid("");
        }
        var kind = ApiJson.text(input.get("kind"), "kind", 4, 4, false);
        if (!kind.equals("task") && !kind.equals("idea")) {
            throw ApiJson.invalid("kind");
        }
        var consent = input.path("consent");
        if (!consent.isBoolean() || !consent.booleanValue()) {
            throw ApiJson.invalid("consent");
        }
        var project = input.path("projectId");
        var priority = input.has("priority") ? ApiJson.text(input.get("priority"), "priority", 1, 6, false) : "normal";
        if (!Set.of("low", "normal", "high").contains(priority)) {
            throw ApiJson.invalid("priority");
        }
        if (input.has("allowDuplicate") && !input.get("allowDuplicate").isBoolean()) {
            throw ApiJson.invalid("allowDuplicate");
        }
        return mapper.createObjectNode().put("kind", kind).put("projectId", TaskPayload.uuid(project, "projectId", true))
                .put("title", ApiJson.text(input.get("title"), "title", 1, 240, true))
                .put("description", ApiJson.text(input.get("description"), "description", 0, 3000, true))
                .put("reason", ApiJson.text(input.get("reason"), "reason", 1, 1500, true))
                .put("uncertainty", ApiJson.text(input.get("uncertainty"), "uncertainty", 0, 1500, true))
                .put("cheapCheck", ApiJson.text(input.get("cheapCheck"), "cheapCheck", 1, 1500, true))
                .put("expectedResult", optionalText(input, "expectedResult")).put("acceptance", optionalText(input, "acceptance"))
                .put("priority", priority).put("allowDuplicate", input.path("allowDuplicate").asBoolean(false)).put("consent", true);
    }

    private static String optionalText(JsonNode input, String field) {
        return input.has(field) ? ApiJson.text(input.get(field), field, 0, 1500, true) : "";
    }

    private void uuid(String input) {
        TaskPayload.uuid(mapper.getNodeFactory().stringNode(input), "", false);
    }

    private static String acceptanceId(String runId, int index) {
        try {
            var sha = MessageDigest.getInstance("SHA-1");
            sha.update(HexFormat.of().parseHex(runId.replace("-", "")));
            var bytes = sha.digest(("proposal:" + index).getBytes(StandardCharsets.UTF_8));
            bytes[6] = (byte) ((bytes[6] & 15) | 80);
            bytes[8] = (byte) ((bytes[8] & 63) | 128);
            var hex = HexFormat.of().formatHex(bytes, 0, 16);
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                    + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-1 недоступен", unavailable);
        }
    }
}
