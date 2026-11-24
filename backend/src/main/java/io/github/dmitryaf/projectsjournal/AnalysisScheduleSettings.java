package io.github.dmitryaf.projectsjournal;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

@Component
final class AnalysisScheduleSettings {

    private static final String CHANGED = "Расписание изменено. Обновите страницу.";
    private static final String STANDING_CHANGED = "Источники или доступ изменились. Подтвердите их заново в обзоре.";
    private static final Pattern CODEX_MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}");
    private static final Set<String> ENABLE_FIELDS = Set.of("provider", "expectedPolicy", "reviewId", "revision",
            "selection", "expectedModel", "readConsent", "sendConsent", "codexProjectIds",
            "codexReadConsent", "codexSendConsent");

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final ReviewService reviews;
    private final PeriodContext context;
    private final AnalysisPrivacy privacy;
    private final ScheduleGuard guard;
    private final PeriodDispatch openai;
    private final CodexAnalysis codex;

    AnalysisScheduleSettings(SqliteStore store, JsonMapper mapper, ReviewService reviews, PeriodContext context,
                             AnalysisPrivacy privacy, ScheduleGuard guard, PeriodDispatch openai, CodexAnalysis codex) {
        this.store = store;
        this.mapper = mapper;
        this.reviews = reviews;
        this.context = context;
        this.privacy = privacy;
        this.guard = guard;
        this.openai = openai;
        this.codex = codex;
    }

    ObjectNode list(JsonNode input) throws SQLException {
        fields(input, Set.of("cursor", "limit"));
        String cursor = input.has("cursor") ? uuid(input.get("cursor"), "cursor") : null;
        int limit = listLimit(input.get("limit"));
        try (var connection = store.open();
                var statement = connection.prepareStatement("SELECT * FROM analysis_schedules "
                        + (cursor == null ? "" : "WHERE id>? ") + "ORDER BY id LIMIT ?")) {
            int parameter = 1;
            if (cursor != null) {
                statement.setString(parameter++, cursor);
            }
            statement.setInt(parameter, limit + 1);
            var result = mapper.createObjectNode();
            var items = result.putArray("items");
            String next = null;
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (items.size() == limit) {
                        next = items.get(limit - 1).path("id").stringValue();
                        break;
                    }
                    items.add(dto(connection, guard.readRow(rows)));
                }
            }
            return result.put("nextCursor", next);
        }
    }

    ObjectNode forReview(String id) throws SQLException {
        try (var connection = store.open()) {
            ObjectNode review = reviews.read(connection, id);
            var row = forScope(connection, text(review, "scopeKey"), text(review, "kind"));
            return row == null ? null : dto(connection, row);
        }
    }

    ObjectNode enable(JsonNode input) throws SQLException {
        Enable approval = parseEnable(input);
        verifyProvider(approval.provider(), approval.model());
        Mutation mutation = store.write(connection -> {
            ObjectNode review = reviews.read(connection, approval.reviewId());
            verifyReview(review, approval.selection(), approval.provider());
            var old = forScope(connection, text(review, "scopeKey"), text(review, "kind"));
            if (!Objects.equals(old == null ? null : old.revision(), approval.revision())) {
                throw new TaskError(409, CHANGED);
            }
            var access = guard.captureAccess(connection, approval.selection(), approval.codexProjects());
            return activate(connection, old, review, approval.selection(), access, approval.provider(), approval.model(),
                    text(review, "timezone"));
        });
        stopLocalRuns(mutation);
        return mutation.result();
    }

    ObjectNode disable(String id, JsonNode input) throws SQLException {
        requireId(id);
        fields(input, Set.of("revision"));
        long revision = revision(input.get("revision"));
        Mutation mutation = store.write(connection -> {
            require(connection, id, revision);
            String now = JournalPeriods.utc(Instant.now());
            update(connection, """
                    UPDATE analysis_schedules SET enabled=0,revision=revision+1,lease_owner=NULL,lease_until=NULL,
                    note=?,updated_at=? WHERE id=?
                    """, "Отключено пользователем.", now, id);
            List<String> running = cancelRuns(connection, id, now);
            return new Mutation(dto(connection, guard.row(connection, id)), running);
        });
        stopLocalRuns(mutation);
        return mutation.result();
    }

    ObjectNode resume(String id, JsonNode input) throws SQLException {
        requireId(id);
        fields(input, Set.of("revision", "confirm"));
        requireConsent(input, "confirm");
        long revision = revision(input.get("revision"));
        ScheduleGuard.Row prior;
        try (var connection = store.open()) {
            prior = require(connection, id, revision);
            verifyStanding(connection, prior);
        }
        verifyProvider(prior.provider(), prior.model());
        var now = Instant.now();
        var today = now.atZone(JournalPeriods.zone(prior.timezone())).toLocalDate().toString();
        var review = reviews.createScheduled(scopeProject(prior.scopeKey()), prior.kind(), today, prior.timezone());
        Mutation mutation = store.write(connection -> {
            var live = require(connection, id, revision);
            var selection = verifyStanding(connection, live);
            ObjectNode currentReview = reviews.read(connection, text(review, "id"));
            verifyReview(currentReview, selection, live.provider());
            if (!live.scopeKey().equals(text(currentReview, "scopeKey")) || !live.kind().equals(text(currentReview, "kind"))) {
                throw new TaskError(410, STANDING_CHANGED);
            }
            // Resume the exact saved access. A new Codex grant revision requires fresh approval.
            verifyProvider(live.provider(), live.model());
            return activate(connection, live, currentReview, selection, live.access(), live.provider(), live.model(), live.timezone());
        });
        stopLocalRuns(mutation);
        return mutation.result();
    }

    void delete(String id, JsonNode input) throws SQLException {
        requireId(id);
        fields(input, Set.of("revision"));
        long revision = revision(input.get("revision"));
        Mutation mutation = store.write(connection -> {
            require(connection, id, revision);
            List<String> running = cancelRuns(connection, id, JournalPeriods.utc(Instant.now()));
            update(connection, "DELETE FROM analysis_schedules WHERE id=? AND revision=?", id, revision);
            return new Mutation(null, running);
        });
        stopLocalRuns(mutation);
    }

    private ObjectNode verifyStanding(Connection connection, ScheduleGuard.Row row) throws SQLException {
        var selection = guard.approvedSelection(row);
        guard.verifyAccess(connection, selection, row.access());
        if (!policy(row.provider()).equals(row.policy())) {
            throw new TaskError(410, STANDING_CHANGED);
        }
        return selection;
    }

    private Mutation activate(Connection connection, ScheduleGuard.Row old, JsonNode review, ObjectNode selection,
                              ArrayNode access, String provider, String model, String timezone) throws SQLException {
        var instant = Instant.now();
        String now = JournalPeriods.utc(instant);
        String kind = text(review, "kind");
        String today = instant.atZone(JournalPeriods.zone(timezone)).toLocalDate().toString();
        String start = JournalPeriods.bounds(kind, today, timezone).periodStart();
        String id = old == null ? UUID.randomUUID().toString() : old.id();
        update(connection, """
                INSERT INTO analysis_schedules(id,scope_key,project_name,period_kind,timezone,model,policy_version,
                  selection_json,access_json,revision,enabled,enabled_at,next_period_start,updated_at,provider)
                VALUES(?,?,?,?,?,?,?,?,?,?,1,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET provider=excluded.provider,timezone=excluded.timezone,model=excluded.model,
                  policy_version=excluded.policy_version,selection_json=excluded.selection_json,access_json=excluded.access_json,
                  revision=excluded.revision,enabled=1,enabled_at=excluded.enabled_at,next_period_start=excluded.next_period_start,
                  lease_owner=NULL,lease_until=NULL,note=NULL,updated_at=excluded.updated_at
                """, id, text(review, "scopeKey"), text(review, "projectName"), kind, timezone, model, policy(provider),
                privacy.json(selection), privacy.json(access), old == null ? 1L : old.revision() + 1L, now, start, now, provider);
        List<String> running = old == null ? List.of() : cancelRuns(connection, id, now);
        return new Mutation(dto(connection, guard.row(connection, id)), running);
    }

    private static List<String> cancelRuns(Connection connection, String schedule, String now) throws SQLException {
        var running = new ArrayList<String>();
        try (var statement = connection.prepareStatement("SELECT id FROM analysis_runs WHERE schedule_id=? AND status='running'")) {
            statement.setString(1, schedule);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    running.add(rows.getString(1));
                }
            }
        }
        update(connection, """
                UPDATE analysis_runs SET status='cancelled',finished_at=?,error=? WHERE schedule_id=? AND status='pending'
                """, now, "Пользователь отменил неотправленный пакет.", schedule);
        update(connection, "UPDATE analysis_runs SET cancel_requested=1 WHERE schedule_id=? AND status='running'", schedule);
        return running;
    }

    private void stopLocalRuns(Mutation mutation) {
        for (String id : mutation.running()) {
            openai.abort(id);
            codex.abort(id);
        }
    }

    private void verifyProvider(String provider, String model) {
        if ("openai".equals(provider)) {
            var status = openai.status();
            if (!status.path("ready").booleanValue(false) || !model.equals(text(status, "model"))) {
                throw new TaskError(409, "Проверьте настроенную модель перед включением.");
            }
        } else if (!CODEX_MODEL.matcher(model).matches()) {
            throw new TaskError(400, "Проверьте модель Codex.");
        }
    }

    private static void verifyReview(JsonNode review, ObjectNode selection, String provider) {
        var ids = JournalSettings.strings(selection.path("projectIds"));
        boolean general = selection.path("includeGeneral").booleanValue();
        String project = scopeProject(text(review, "scopeKey"));
        if ("codex".equals(provider) && ids.isEmpty()) {
            throw new TaskError(400, "Выберите хотя бы один проект для Codex.");
        }
        if (!selection.path("codexDigestIds").isEmpty()) {
            throw new TaskError(400, "Итоги Codex разрешены только для ручного анализа с просмотром пакета.");
        }
        if (project != null && (general || ids.size() != 1 || !project.equals(ids.getFirst()))) {
            throw new TaskError(400, "Проектное расписание использует только свой проект.");
        }
        if (ids.isEmpty() && !general) {
            throw new TaskError(400, "Выберите проекты или общие записи.");
        }
    }

    private Enable parseEnable(JsonNode input) {
        fields(input, ENABLE_FIELDS);
        requireConsent(input, "readConsent");
        requireConsent(input, "sendConsent");
        String provider = input.has("provider") ? ApiJson.text(input.get("provider"), "provider", 1, 10, false) : "openai";
        if (!"openai".equals(provider) && !"codex".equals(provider)) {
            throw ApiJson.invalid("provider");
        }
        String model = ApiJson.text(input.get("expectedModel"), "expectedModel", 1, 100, false);
        String expectedPolicy = input.has("expectedPolicy")
                ? ApiJson.text(input.get("expectedPolicy"), "expectedPolicy", 1, 100, false) : null;
        if ("codex".equals(provider)) {
            if (expectedPolicy == null) {
                throw new TaskError(400, "Подтвердите актуальный промпт Codex.");
            }
            if (!CodexAnalysis.POLICY.equals(expectedPolicy)) {
                throw new TaskError(409, "Промпт Codex изменился. Просмотрите его и подтвердите расписание заново.");
            }
        }
        String reviewId = uuid(input.get("reviewId"), "reviewId");
        JsonNode rev = input.get("revision");
        Long expected = rev != null && rev.isNull() ? null : revision(rev);
        var selection = context.normalizeSelection(input.get("selection"));
        var codexProjects = readCodexProjects(input.get("codexProjectIds"));
        var selected = JournalSettings.strings(selection.path("projectIds"));
        if (new HashSet<>(codexProjects).size() != codexProjects.size() || !selected.containsAll(codexProjects)) {
            throw new TaskError(400, "Codex разрешается только для выбранных проектов.");
        }
        for (String field : List.of("codexReadConsent", "codexSendConsent")) {
            if (input.has(field)) {
                requireConsent(input, field);
            }
        }
        if (!codexProjects.isEmpty() && (!input.has("codexReadConsent") || !input.has("codexSendConsent"))) {
            throw new TaskError(400, "Для новых диалогов нужны отдельные согласия на чтение и отправку.");
        }
        return new Enable(reviewId, expected, selection, provider, model, codexProjects);
    }

    private static List<String> readCodexProjects(JsonNode input) {
        if (input == null) {
            return List.of();
        }
        if (!input.isArray() || input.size() > 10) {
            throw ApiJson.invalid("codexProjectIds");
        }
        var ids = new ArrayList<String>();
        for (int index = 0; index < input.size(); index++) {
            ids.add(uuid(input.get(index), "codexProjectIds." + index));
        }
        return ids;
    }

    private ScheduleGuard.Row forScope(Connection connection, String scope, String kind) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM analysis_schedules WHERE scope_key=? AND period_kind=?")) {
            statement.setString(1, scope);
            statement.setString(2, kind);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? guard.readRow(rows) : null;
            }
        }
    }

    private ScheduleGuard.Row require(Connection connection, String id, long revision) throws SQLException {
        var row = guard.row(connection, id);
        if (row == null) {
            throw new TaskError(404, "Расписание не найдено.");
        }
        if (row.revision() != revision) {
            throw new TaskError(409, CHANGED);
        }
        return row;
    }

    private ObjectNode dto(Connection connection, ScheduleGuard.Row row) throws SQLException {
        var result = mapper.createObjectNode().put("id", row.id()).put("provider", row.provider())
                .put("scopeKey", row.scopeKey()).put("projectName", row.projectName()).put("kind", row.kind())
                .put("timezone", row.timezone()).put("model", row.model()).set("selection", row.selection());
        var codex = result.putArray("codexProjectIds");
        for (JsonNode item : row.access()) {
            if (item.has("codexRevision")) {
                codex.add(text(item, "id"));
            }
        }
        return result.put("revision", row.revision()).put("enabled", row.enabled())
                .put("nextPeriodStart", row.nextPeriodStart()).put("lastRunId", row.lastRunId())
                .put("lastRunReviewKey", lastReview(connection, row.lastRunId())).put("note", row.note())
                .put("updatedAt", row.updatedAt());
    }

    private static String lastReview(Connection connection, String lastRunId) throws SQLException {
        if (lastRunId == null) {
            return null;
        }
        try (var statement = connection.prepareStatement("SELECT review_key FROM analysis_runs WHERE id=?")) {
            statement.setString(1, lastRunId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static void update(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static String policy(String provider) {
        return "codex".equals(provider) ? CodexAnalysis.POLICY : PeriodContext.POLICY;
    }

    private static String scopeProject(String scope) {
        return "general".equals(scope) ? null : scope.substring("project:".length());
    }

    private static void fields(JsonNode input, Set<String> expected) {
        ApiJson.requireObject(input);
        if (!expected.containsAll(input.propertyNames())) {
            throw ApiJson.invalid("");
        }
    }

    private static void requireConsent(JsonNode input, String field) {
        if (!input.path(field).booleanValue(false)) {
            throw ApiJson.invalid(field);
        }
    }

    private static int listLimit(JsonNode input) {
        if (input == null) {
            return 20;
        }
        if (input.isString()) {
            input = DecimalNode.valueOf(ApiJson.numberText(input.stringValue(), "limit"));
        } else if (input.isBoolean() && input.booleanValue()) {
            return 1;
        }
        return (int) ApiJson.integer(input, "limit", 1, 30);
    }

    private static long revision(JsonNode input) {
        return ApiJson.integer(input, "revision", 1L, 9_007_199_254_740_991L);
    }

    private static String uuid(JsonNode input, String field) {
        return TaskPayload.uuid(input, field, false);
    }

    private static void requireId(String value) {
        uuid(StringNode.valueOf(value), "");
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).stringValue(null);
    }

    private record Enable(String reviewId, Long revision, ObjectNode selection, String provider, String model,
                          List<String> codexProjects) {
    }

    private record Mutation(ObjectNode result, List<String> running) {
    }
}
