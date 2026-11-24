package io.github.dmitryaf.projectsjournal;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class CheckService {

    private static final Set<String> CHECK_STATUSES = Set.of("active", "paused");
    private static final Set<String> OUTCOMES = Set.of("continue", "change", "stop");
    private static final Set<String> ENTRY_KINDS = Set.of("action", "evidence");
    private static final Set<String> EVIDENCE_TYPES = Set.of("fact", "observation", "problem", "decision");
    private static final DateTimeFormatter INSTANT = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final SqliteStore store;
    private final JsonMapper mapper;

    CheckService(SqliteStore store, JsonMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    ArrayNode list(Connection connection) throws SQLException {
        var output = mapper.createArrayNode();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT * FROM checks ORDER BY created_at DESC")) {
            while (rows.next()) {
                output.add(check(rows));
            }
        }
        return output;
    }

    ArrayNode entries(Connection connection) throws SQLException {
        var output = mapper.createArrayNode();
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT * FROM check_entries ORDER BY created_at DESC")) {
            while (rows.next()) {
                output.add(entry(rows));
            }
        }
        return output;
    }

    ObjectNode save(String id, JsonNode input, boolean allowParallel) throws SQLException {
        ObjectNode data = parseCheck(input);
        String checkId = id == null ? UUID.randomUUID().toString() : id;
        return store.write(connection -> {
            ObjectNode existing = id == null ? null : requireCheck(connection, id);
            if (existing != null && status(existing, "completed")) {
                throw new TaskError(409, "Завершённая проверка остаётся в архиве. Создайте следующую.");
            }
            String start = data.path("startedAt").asString();
            if (start.compareTo(LocalDate.now().toString()) > 0) {
                throw new TaskError(400, "Дата запуска не может быть в будущем.");
            }
            String review = data.path("reviewAt").asString(null);
            if (review != null && review.compareTo(start) < 0) {
                throw new TaskError(400, "Дата пересмотра раньше запуска.");
            }
            String first = existing == null ? null : entryDate(connection, id, true);
            if (first != null && first.compareTo(start) < 0) {
                throw new TaskError(400, "Дата запуска позже записанной хронологии.");
            }
            String project = data.path("projectId").asString(null);
            String idea = data.path("ideaId").asString(null);
            if (project != null && ProjectRow.find(connection, project) == null) {
                throw new TaskError(400, "Проект не найден.");
            }
            if (idea != null && !ideaExists(connection, idea)) {
                throw new TaskError(400, "Идея не найдена.");
            }
            boolean activating = status(data, "active") && (existing == null || !status(existing, "active")
                    || !Objects.equals(idea, existing.path("ideaId").asString(null)));
            if (activating && idea != null && !allowParallel && hasParallel(connection, idea, checkId)) {
                throw new TaskError(409, "Для этой идеи уже есть активная проверка. Подтвердите создание параллельной.");
            }
            String now = INSTANT.format(Instant.now());
            try (var statement = connection.prepareStatement("""
                    INSERT INTO checks(id,project_id,idea_id,data,created_at,updated_at) VALUES(?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET project_id=excluded.project_id,idea_id=excluded.idea_id,
                    data=excluded.data,updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, checkId);
                statement.setString(2, project);
                statement.setString(3, idea);
                statement.setString(4, mapper.writeValueAsString(data));
                statement.setString(5, existing == null ? now : existing.path("createdAt").asString());
                statement.setString(6, now);
                statement.executeUpdate();
            }
            if (existing == null && idea != null) {
                try (var statement = connection.prepareStatement("""
                        UPDATE records SET data=json_set(data,'$.state','testing'),updated_at=? WHERE id=?
                        """)) {
                    statement.setString(1, now);
                    statement.setString(2, idea);
                    statement.executeUpdate();
                }
            }
            return requireCheck(connection, checkId);
        });
    }

    ObjectNode complete(String id, JsonNode input) throws SQLException {
        return store.write(connection -> {
            ObjectNode check = requireCheck(connection, id);
            if (status(check, "completed")) {
                throw new TaskError(409, "Проверка уже завершена.");
            }
            ObjectNode conclusion = parseConclusion(input);
            String completed = conclusion.path("completedAt").asString();
            String started = check.path("startedAt").asString();
            String last = entryDate(connection, id, false);
            if (completed.compareTo(LocalDate.now().toString()) > 0 || completed.compareTo(started) < 0
                    || (last != null && completed.compareTo(last) < 0)) {
                throw new TaskError(400, "Дата завершения должна быть между последней записью и сегодняшним днём.");
            }
            check.put("status", "completed").put("completedAt", completed).set("conclusion", conclusion);
            try (var statement = connection.prepareStatement("UPDATE checks SET data=?,updated_at=? WHERE id=?")) {
                statement.setString(1, mapper.writeValueAsString(check));
                statement.setString(2, INSTANT.format(Instant.now()));
                statement.setString(3, id);
                statement.executeUpdate();
            }
            return requireCheck(connection, id);
        });
    }

    ObjectNode saveEntry(String checkId, String id, JsonNode input) throws SQLException {
        String entryId = id == null ? UUID.randomUUID().toString() : id;
        return store.write(connection -> {
            ObjectNode check = requireCheck(connection, checkId);
            ObjectNode data = parseEntry(input);
            String date = data.path("occurredAt").asString();
            if (date.compareTo(check.path("startedAt").asString()) < 0
                    || date.compareTo(check.path("completedAt").asString(LocalDate.now().toString())) > 0) {
                throw new TaskError(400, "Дата записи вне периода проверки.");
            }
            String now = INSTANT.format(Instant.now());
            String created = id == null ? now : requireEntry(connection, checkId, id).path("createdAt").asString();
            try (var statement = connection.prepareStatement("""
                    INSERT INTO check_entries(id,check_id,data,created_at,updated_at) VALUES(?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET data=excluded.data,updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, entryId);
                statement.setString(2, checkId);
                statement.setString(3, mapper.writeValueAsString(data));
                statement.setString(4, created);
                statement.setString(5, now);
                statement.executeUpdate();
            }
            return requireEntry(connection, checkId, entryId);
        });
    }

    void deleteEntry(String checkId, String id) throws SQLException {
        store.write(connection -> {
            requireCheck(connection, checkId);
            try (var statement = connection.prepareStatement("DELETE FROM check_entries WHERE id=? AND check_id=?")) {
                statement.setString(1, id);
                statement.setString(2, checkId);
                if (statement.executeUpdate() == 0) {
                    throw new TaskError(404, "Запись не найдена.");
                }
            }
            return null;
        });
    }

    private static boolean ideaExists(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM records WHERE id=? AND kind='ideas'")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static boolean hasParallel(Connection connection, String idea, String checkId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM checks WHERE idea_id=? AND id<>? AND json_extract(data,'$.status')='active' LIMIT 1
                """)) {
            statement.setString(1, idea);
            statement.setString(2, checkId);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static String entryDate(Connection connection, String id, boolean earliest) throws SQLException {
        String date = "json_extract(data,'$.occurredAt')";
        String aggregate = earliest ? "MIN(" + date + ")" : "MAX(" + date + ")";
        try (var statement = connection.prepareStatement("SELECT " + aggregate + " FROM check_entries WHERE check_id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private ObjectNode requireCheck(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM checks WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskError(404, "Проверка не найдена.");
                }
                return check(rows);
            }
        }
    }

    private ObjectNode requireEntry(Connection connection, String checkId, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM check_entries WHERE id=? AND check_id=?")) {
            statement.setString(1, id);
            statement.setString(2, checkId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskError(404, "Запись хронологии не найдена.");
                }
                return entry(rows);
            }
        }
    }

    private ObjectNode check(ResultSet row) throws SQLException {
        return stamped(row).put("projectId", row.getString("project_id")).put("ideaId", row.getString("idea_id"));
    }

    private ObjectNode entry(ResultSet row) throws SQLException {
        return stamped(row).put("checkId", row.getString("check_id"));
    }

    private ObjectNode stamped(ResultSet row) throws SQLException {
        return mapper.readTree(row.getString("data")).asObject().put("id", row.getString("id"))
                .put("createdAt", row.getString("created_at")).put("updatedAt", row.getString("updated_at"));
    }

    private ObjectNode parseCheck(JsonNode input) {
        ApiJson.requireObject(input);
        return mapper.createObjectNode().put("title", ApiJson.text(input.get("title"), "title", 1, 240, true))
                .put("projectId", TaskPayload.uuid(input.get("projectId"), "projectId", true))
                .put("ideaId", TaskPayload.uuid(input.get("ideaId"), "ideaId", true))
                .put("question", required(input, "question"))
                .put("assumption", required(input, "assumption"))
                .put("expectedExternalResult", required(input, "expectedExternalResult"))
                .put("startedAt", date(input.get("startedAt"), "startedAt"))
                .put("reviewAt", input.hasNonNull("reviewAt") ? date(input.get("reviewAt"), "reviewAt") : null)
                .put("reviewCondition", optional(input, "reviewCondition"))
                .put("continueIf", required(input, "continueIf"))
                .put("stopIf", required(input, "stopIf"))
                .put("nextExternalAction", required(input, "nextExternalAction"))
                .put("status", input.has("status") ? choice(input.get("status"), "status", CHECK_STATUSES) : "active")
                .putNull("conclusion").putNull("completedAt");
    }

    private ObjectNode parseConclusion(JsonNode input) {
        ApiJson.requireObject(input);
        return mapper.createObjectNode().put("actualResult", required(input, "actualResult"))
                .put("whatWasConfirmedOrRejected", required(input, "whatWasConfirmedOrRejected"))
                .put("outcome", choice(input.get("outcome"), "outcome", OUTCOMES))
                .put("basis", required(input, "basis")).put("nextQuestion", optional(input, "nextQuestion"))
                .put("completedAt", date(input.get("completedAt"), "completedAt"));
    }

    private ObjectNode parseEntry(JsonNode input) {
        ApiJson.requireObject(input);
        String kind = choice(input.get("kind"), "kind", ENTRY_KINDS);
        String occurred = date(input.get("occurredAt"), "occurredAt");
        String text = required(input, "text");
        String type = input.hasNonNull("type") ? choice(input.get("type"), "type", EVIDENCE_TYPES) : null;
        String url = input.has("url") ? validUrl(input.get("url")) : "";
        JsonNode numeric = input.get("numericValue");
        if (numeric != null && !numeric.isNull() && (!numeric.isNumber() || !Double.isFinite(numeric.doubleValue()))) {
            throw ApiJson.invalid("numericValue");
        }
        boolean action = kind.equals("action");
        var data = mapper.createObjectNode().put("kind", kind).put("occurredAt", occurred).put("text", text)
                .put("type", action ? null : type).put("url", url);
        if (action || numeric == null || numeric.isNull()) {
            return data.putNull("numericValue");
        }
        return data.put("numericValue", numeric.doubleValue());
    }

    private static String validUrl(JsonNode input) {
        String value = ApiJson.text(input, "url", 0, 2000, false);
        if (value.isEmpty()) {
            return value;
        }
        if (value.regionMatches(true, 0, "http://", 0, 7) || value.regionMatches(true, 0, "https://", 0, 8)) {
            try {
                var url = UriComponentsBuilder.fromUriString(value, UriComponentsBuilder.ParserType.WHAT_WG).build();
                if (url.getHost() != null && !url.getHost().isEmpty()) {
                    return value;
                }
            } catch (IllegalArgumentException invalid) {
                // Return the standard validation error without parser diagnostics.
            }
        }
        throw ApiJson.invalid("url");
    }

    private static String date(JsonNode input, String field) {
        String value = ApiJson.text(input, field, 10, 10, false);
        try {
            if (LocalDate.parse(value).toString().equals(value)) {
                return value;
            }
        } catch (DateTimeParseException invalid) {
            // Use the same validation error for impossible dates and non-date input.
        }
        throw ApiJson.invalid(field);
    }

    private static String required(JsonNode input, String field) {
        return ApiJson.text(input.get(field), field, 1, 10_000, true);
    }

    private static String optional(JsonNode input, String field) {
        return input.has(field) ? ApiJson.text(input.get(field), field, 0, 10_000, true) : "";
    }

    private static String choice(JsonNode input, String field, Set<String> values) {
        String value = ApiJson.text(input, field, 1, 40, false);
        if (!values.contains(value)) {
            throw ApiJson.invalid(field);
        }
        return value;
    }

    private static boolean status(JsonNode check, String status) {
        return status.equals(check.path("status").asString());
    }

    ArrayNode views(Connection connection, List<ObjectNode> projects, JsonNode configuration, ArrayNode entries)
            throws SQLException {
        var checks = list(connection);
        var recentCommits = new HashMap<String, List<LocalDate>>();
        projects.stream().filter(project -> project.path("available").booleanValue()).forEach(project -> {
            var dates = new ArrayList<LocalDate>();
            for (var commit : project.path("snapshot").path("git").path("commits")) {
                try {
                    dates.add(OffsetDateTime.parse(commit.path("date").asString())
                            .atZoneSameInstant(ZoneId.systemDefault()).toLocalDate());
                } catch (DateTimeParseException invalid) {
                    // An unreadable saved Git date is not evidence of recent internal work.
                }
            }
            recentCommits.put(project.path("id").asString(), dates);
        });
        var today = LocalDate.now();
        for (var node : checks) {
            var check = node.asObject();
            String action = latest(entries, check.path("id").asString(), "action");
            String evidence = latest(entries, check.path("id").asString(), "evidence");
            var start = LocalDate.parse(check.path("startedAt").asString());
            var end = check.hasNonNull("completedAt") ? LocalDate.parse(check.path("completedAt").asString()) : today;
            long days = daysBetween(action == null ? start : LocalDate.parse(action), end);
            check.put("lastExternalActionAt", action).put("lastEvidenceAt", evidence)
                    .put("daysWithoutMovement", days).put("daysActive", daysBetween(start, end));
            var signals = check.putArray("signals");
            if (status(check, "active")) {
                checkSignals(signals, check, days, configuration, today,
                        recentCommits.getOrDefault(check.path("projectId").asString(null), List.of()));
            }
        }
        return checks;
    }

    private static String latest(ArrayNode entries, String checkId, String kind) {
        String latest = null;
        for (var entry : entries) {
            String date = entry.path("occurredAt").asString();
            if (checkId.equals(entry.path("checkId").asString()) && kind.equals(entry.path("kind").asString())
                    && (latest == null || date.compareTo(latest) > 0)) {
                latest = date;
            }
        }
        return latest;
    }

    private static long daysBetween(LocalDate from, LocalDate to) {
        return Math.max(0, ChronoUnit.DAYS.between(from, to));
    }

    private static void checkSignals(ArrayNode signals, JsonNode check, long days, JsonNode settings,
            LocalDate today, List<LocalDate> commitDates) {
        long attention = settings.path("movementAttentionDays").longValue();
        if (days >= settings.path("movementDecisionDays").longValue()) {
            signal(signals, "movement", "decision", days + " дн. без внешнего движения. Пора решить: продолжить, изменить или остановить проверку.");
        } else if (days >= attention) {
            signal(signals, "movement", "attention", days + " дн. не было внешнего движения");
        } else if (days >= settings.path("movementInfoDays").longValue()) {
            signal(signals, "movement", "info", "Давно не было внешнего шага: " + days + " дн.");
        }
        String reviewAt = check.path("reviewAt").asString(null);
        if (reviewAt != null && reviewAt.compareTo(today.toString()) <= 0) {
            signal(signals, "review", "decision", "Наступила дата пересмотра: " + reviewAt);
        }
        if (days >= attention && commitDates.stream().anyMatch(date -> !date.isAfter(today) && daysBetween(date, today) <= 7)) {
            signal(signals, "internal-only", "attention", "Проект активно меняется, но " + days + " дн. не было внешней проверки.");
        }
    }

    private static void signal(ArrayNode signals, String code, String level, String message) {
        signals.addObject().put("code", code).put("level", level).put("message", message);
    }
}
