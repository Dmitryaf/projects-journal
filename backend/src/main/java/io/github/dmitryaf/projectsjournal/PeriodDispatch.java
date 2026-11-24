package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class PeriodDispatch {

    private static final String PROMPT = prompt();
    private static final String STALE_SOURCE = "Доступ к источнику изменён.";
    private static final String OPENAI = "openai";
    private static final String SCHEDULE_CHANGED = "Разрешение расписания отключено или изменилось.";

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final AnalysisPrivacy privacy;
    private final PeriodRuns runs;
    private final PeriodContext context;
    private final ReviewService reviews;
    private final PeriodReport report;
    private final OpenAiResponses provider;
    private final ScheduleGuard scheduleGuard;
    private final String owner = UUID.randomUUID().toString();
    private final ConcurrentHashMap<String, Active> active = new ConcurrentHashMap<>();

    PeriodDispatch(SqliteStore store, JsonMapper mapper, AnalysisPrivacy privacy, PeriodRuns runs,
                   PeriodContext context, ReviewService reviews, PeriodReport report, OpenAiResponses provider,
                   ScheduleGuard scheduleGuard) {
        this.store = store;
        this.mapper = mapper;
        this.privacy = privacy;
        this.runs = runs;
        this.context = context;
        this.reviews = reviews;
        this.report = report;
        this.provider = provider;
        this.scheduleGuard = scheduleGuard;
    }

    ObjectNode status() {
        return provider.status();
    }

    ObjectNode prepare(JsonNode input) throws SQLException {
        return prepare(input, null);
    }

    ObjectNode prepareScheduled(JsonNode input, ScheduledRun schedule) throws SQLException {
        return prepare(input, Objects.requireNonNull(schedule));
    }

    private ObjectNode prepare(JsonNode input, ScheduledRun schedule) throws SQLException {
        fields(input, Set.of("reviewId", "requestKey", "selection", "readConsent", "repeatConsent"));
        var reviewId = TaskPayload.uuid(input.get("reviewId"), "reviewId", false);
        var requestKey = TaskPayload.uuid(input.get("requestKey"), "requestKey", false);
        requireConsent(input, "readConsent");
        if (input.has("repeatConsent") && !input.get("repeatConsent").isBoolean()) {
            throw ApiJson.invalid("repeatConsent");
        }
        var selection = context.normalizeSelection(input.get("selection"));
        var hash = requestHash(reviewId, selection);
        return store.write(connection -> {
            var now = Instant.now();
            runs.recover(connection, JournalPeriods.utc(now));
            var model = Objects.toString(provider.model(), "");
            if (schedule != null) {
                scheduleGuard.verify(connection, schedule.id(), schedule.revision(), model, OPENAI, PeriodContext.POLICY);
                scheduleGuard.verifySelection(connection, schedule.id(), selection);
                var scheduledId = schedule.existing(connection);
                if (scheduledId != null) {
                    var previous = runs.read(connection, scheduledId);
                    if (!reviewId.equals(previous.path("reviewKey").asString())
                            || !schedule.id().equals(row(connection, scheduledId).scheduleId())) {
                        throw new TaskError(409, "Для расписания уже сохранён анализ другого периода.");
                    }
                    return previous;
                }
            }
            var existing = previouslyPrepared(connection, requestKey, hash);
            if (existing != null) {
                if (schedule != null) {
                    throw new TaskError(409, "Этот ключ уже использован для другого запроса. Откройте сохранённый вариант.");
                }
                return runs.read(connection, existing);
            }
            var review = reviews.read(connection, reviewId);
            var generation = PeriodRuns.nextGeneration(connection, reviewId);
            if (generation > 1 && (schedule != null || !input.path("repeatConsent").asBoolean(false))) {
                throw new TaskError(409, "Новый вариант потребует отдельного запроса и может быть оплачен. Подтвердите повтор; прежний результат сохранён.");
            }
            var selected = context.build(connection, review, selection, now, schedule != null);
            if (schedule != null && schedule.limitations() != null && !schedule.limitations().isEmpty()) {
                var payload = selected.payload();
                schedule.limitations().forEach(payload.withArrayProperty("limitations")::add);
                if (privacy.bytes(payload) > OpenAiResponses.MAX_INPUT_BYTES) {
                    throw new TaskError(400, "Пакет больше разрешённого лимита; запрос не отправлен.");
                }
                if (privacy.containsSensitive(payload)) {
                    throw new TaskError(400, "В выбранных текстах найден возможный секрет или локальный путь. Запрос не отправлен.");
                }
            }
            var id = UUID.randomUUID().toString();
            try (var statement = connection.prepareStatement("""
                    INSERT INTO analysis_runs(id,review_id,review_key,scope_key,generation,status,model,policy_version,
                        selection_json,payload_json,guards_json,packet_hash,request_key,request_hash,created_at,expires_at,
                        trigger,schedule_id,schedule_revision,automatic_key)
                    VALUES(?,?,?,?,?,'pending',?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                statement.setString(1, id);
                statement.setString(2, reviewId);
                statement.setString(3, reviewId);
                statement.setString(4, review.get("scopeKey").stringValue());
                statement.setLong(5, generation);
                statement.setString(6, model);
                statement.setString(7, PeriodContext.POLICY);
                statement.setString(8, privacy.json(selection));
                statement.setString(9, privacy.json(selected.payload()));
                statement.setString(10, privacy.json(selected.guards()));
                statement.setString(11, packetHash(selected.payload(), model, PeriodContext.POLICY));
                statement.setString(12, requestKey);
                statement.setString(13, hash);
                statement.setString(14, JournalPeriods.utc(now));
                statement.setString(15, JournalPeriods.utc(now.plusSeconds(15 * 60)));
                statement.setString(16, schedule == null ? "manual" : "scheduled");
                statement.setString(17, schedule == null ? null : schedule.id());
                if (schedule == null) {
                    statement.setNull(18, Types.INTEGER);
                } else {
                    statement.setLong(18, schedule.revision());
                }
                statement.setString(19, schedule == null ? null : schedule.key());
                statement.executeUpdate();
            }
            if (schedule != null) {
                schedule.record(connection, id);
            }
            return runs.read(connection, id);
        });
    }

    ObjectNode run(String id, JsonNode input) throws SQLException {
        uuid(id);
        fields(input, Set.of("packetHash", "consent"));
        requireConsent(input, "consent");
        var hash = ApiJson.text(input.get("packetHash"), "packetHash", 64, 64, false);
        if (!hash.matches("[a-f0-9]{64}")) {
            throw ApiJson.invalid("packetHash");
        }
        var claimed = store.write(connection -> claim(connection, id, hash));
        if (!claimed) {
            return runs.read(id);
        }
        var job = new Active(Thread.currentThread());
        active.put(id, job);
        try {
            perform(id);
        } finally {
            job.finish();
            active.remove(id, job);
        }
        return runs.read(id);
    }

    private boolean claim(Connection connection, String id, String hash) throws SQLException {
        Instant now = Instant.now();
        var initial = row(connection, id);
        if (initial.payload().path("origin").path("kind").asString().equals("codex_runtime")) {
            throw new TaskError(403, "Этот пакет запускается только через встроенный Codex.");
        }
        runs.recover(connection, JournalPeriods.utc(now));
        var row = row(connection, id);
        if (!row.packetHash().equals(hash)) {
            throw new TaskError(409, "Пакет не совпадает с просмотренным. Откройте сохранённые данные.");
        }
        if (!packetHash(row.payload(), row.model(), row.policy()).equals(row.packetHash())) {
            throw new TaskError(409, "Сохранённый пакет не прошёл проверку целостности. Запрос не отправлен.");
        }
        if (!row.status().equals("pending")) {
            return false;
        }
        if (!provider.ready()) {
            throw new TaskError(503, "Задайте OPENAI_API_KEY и CONTROL_CENTER_OPENAI_MODEL в окружении сервера.");
        }
        if (!row.model().equals(provider.model()) || !row.policy().equals(PeriodContext.POLICY)) {
            throw new TaskError(409, "Конфигурация анализа изменилась. Подготовьте новый пакет.");
        }
        if (row.reviewId() == null) {
            throw new TaskError(410, "Обзор удалён. Запрос не отправлен.");
        }
        try {
            verify(connection, row);
        } catch (TaskError denied) {
            update(connection, "UPDATE analysis_runs SET status='cancelled',error=?,finished_at=? WHERE id=?",
                    denied.getMessage(), JournalPeriods.utc(now), id);
            return false;
        }
        update(connection, """
                UPDATE analysis_runs SET status='running',approved_at=?,started_at=?,lease_until=?,owner_id=?
                WHERE id=? AND status='pending'
                """, JournalPeriods.utc(now), JournalPeriods.utc(now),
                JournalPeriods.utc(now.plusSeconds(OpenAiResponses.TIMEOUT_SECONDS + 60)), owner, id);
        return true;
    }

    private void perform(String id) throws SQLException {
        boolean dispatched = false;
        OpenAiResponses.Result received = null;
        try {
            var row = store.write(connection -> {
                var current = row(connection, id);
                if (current.cancelRequested() || !current.status().equals("running") || !owner.equals(current.owner())) {
                    throw new TaskError(409, "Отмена запрошена до получения результата.");
                }
                if ("scheduled".equals(current.trigger())
                        && (current.leaseUntil() == null || current.leaseUntil().compareTo(timestamp()) <= 0)) {
                    throw new TaskError(410, "Срок подтверждённого запуска истёк. Запрос не отправлен.");
                }
                verify(connection, current);
                return current;
            });
            if (Thread.currentThread().isInterrupted()) {
                throw new TaskError(409, "Отмена запрошена до получения результата.");
            }
            dispatched = true;
            received = provider.request(row.payload(), PROMPT, "period_review", report.schema(), report::normalize);
            report.validate(row.payload(), received.report());
            saveResult(id, received);
        } catch (TaskError failure) {
            Thread.interrupted();
            if (failure instanceof OpenAiResponses.Failure responseFailure) {
                saveFailure(id, dispatched ? responseFailure.outcome() : "cancelled", failure.getMessage(),
                        responseFailure.usage(), responseFailure.responseId());
            } else {
                saveFailure(id, dispatched ? "failed" : "cancelled", failure.getMessage(),
                        received == null ? null : received.usage(), received == null ? null : received.responseId());
            }
        } catch (RuntimeException | SQLException failure) {
            Thread.interrupted();
            saveFailure(id, dispatched ? "unknown" : "cancelled", dispatched
                            ? "Результат не сохранён. Запрос мог быть оплачен; автоматического повтора нет." : STALE_SOURCE,
                    received == null ? null : received.usage(), received == null ? null : received.responseId());
        }
    }

    private void verify(Connection connection, RunState row) throws SQLException {
        boolean scheduled = "scheduled".equals(row.trigger());
        if (scheduled || row.scheduleId() != null || row.scheduleRevision() != null || row.automaticKey() != null) {
            if (!scheduled || row.scheduleId() == null || row.scheduleRevision() == null || row.automaticKey() == null) {
                throw new TaskError(410, SCHEDULE_CHANGED);
            }
            if (!Objects.equals(provider.model(), row.model()) || !PeriodContext.POLICY.equals(row.policy())) {
                throw new TaskError(409, "Конфигурация анализа изменилась. Подготовьте новый пакет.");
            }
            scheduleGuard.verify(connection, row.scheduleId(), row.scheduleRevision(), row.model(), OPENAI, PeriodContext.POLICY);
            scheduleGuard.verifySelection(connection, row.scheduleId(), row.selection());
            if (!hasSourceText(row.payload())) {
                throw new TaskError(410, "В разрешённых источниках нет данных; запрос не отправлен.");
            }
            if (!packetHash(row.payload(), row.model(), row.policy()).equals(row.packetHash())) {
                throw new TaskError(409, "Сохранённый пакет не прошёл проверку целостности. Запрос не отправлен.");
            }
        }
        context.verify(connection, row.guards());
    }

    private static boolean hasSourceText(JsonNode payload) {
        for (var source : payload.path("sources")) {
            if (!TaskPayload.trim(source.path("text").stringValue("")).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void saveResult(String id, OpenAiResponses.Result result) throws SQLException {
        store.write(connection -> {
            update(connection, """
                    UPDATE analysis_runs SET status='succeeded',report_json=?,raw_output=?,usage_json=?,response_id=?,finished_at=?,
                      lease_until=NULL,owner_id=NULL WHERE id=? AND status='running' AND owner_id=?
                    """, privacy.json(result.report()), result.rawOutput(), usage(result.usage()), result.responseId(),
                    timestamp(), id, owner);
            return null;
        });
    }

    private void saveFailure(String id, String status, String message, JsonNode usage, String responseId) throws SQLException {
        store.write(connection -> {
            update(connection, """
                    UPDATE analysis_runs SET status=?,error=?,usage_json=?,response_id=?,finished_at=?,lease_until=NULL,owner_id=NULL
                    WHERE id=? AND status='running' AND owner_id=?
                    """, status, message, usage(usage), responseId, timestamp(), id, owner);
            return null;
        });
    }

    ObjectNode cancel(String id) throws SQLException {
        uuid(id);
        store.write(connection -> {
            switch (row(connection, id).status()) {
                case "pending" -> update(connection, "UPDATE analysis_runs SET status='cancelled',finished_at=?,error=? WHERE id=?",
                        timestamp(), "Пользователь отменил неотправленный пакет.", id);
                case "running" -> update(connection, "UPDATE analysis_runs SET cancel_requested=1 WHERE id=?", id);
                default -> { }
            }
            return null;
        });
        abort(id);
        return runs.read(id);
    }

    void abort(String id) {
        var job = active.get(id);
        if (job != null) {
            job.cancel();
        }
    }

    void delete(String id, JsonNode input) throws SQLException {
        uuid(id);
        fields(input, Set.of("confirm"));
        requireConsent(input, "confirm");
        store.write(connection -> {
            if (row(connection, id).status().equals("running")) {
                throw new TaskError(409, "Запрос ещё выполняется. Сначала запросите отмену или дождитесь его завершения.");
            }
            update(connection, "DELETE FROM analysis_runs WHERE id=?", id);
            return null;
        });
    }

    @PreDestroy
    void stop() {
        var jobs = List.copyOf(active.values());
        jobs.forEach(Active::cancel);
        try {
            CompletableFuture.allOf(jobs.stream().map(Active::done).toArray(CompletableFuture[]::new))
                    .get(OpenAiResponses.TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // The persisted ownership lease will recover any unfinished job as unknown.
        }
    }

    private String previouslyPrepared(Connection connection, String key, String hash) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id,request_hash,review_key,selection_json,payload_json FROM analysis_runs WHERE request_key=?")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                if (!hash.equals(rows.getString("request_hash"))) {
                    var payload = mapper.readTree(rows.getString("payload_json"));
                    if (payload.has("origin") || !hash.equals(requestHash(rows.getString("review_key"),
                            context.normalizeSelection(mapper.readTree(rows.getString("selection_json")))))) {
                        throw new TaskError(409, "Этот ключ подготовки уже использован с другим выбором. Откройте сохранённый пакет.");
                    }
                }
                return rows.getString("id");
            }
        }
    }

    private RunState row(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT status,model,policy_version,packet_hash,payload_json,guards_json,review_id,trigger,schedule_id,
                  schedule_revision,automatic_key,selection_json,owner_id,lease_until,cancel_requested
                FROM analysis_runs WHERE id=?
                """)) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskError(404, "Сохранённый анализ не найден.");
                }
                long revision = rows.getLong("schedule_revision");
                Long scheduleRevision = rows.wasNull() ? null : revision;
                var scheduleId = rows.getString("schedule_id");
                return new RunState(rows.getString("status"), rows.getString("model"), rows.getString("policy_version"),
                        rows.getString("packet_hash"), mapper.readTree(rows.getString("payload_json")),
                        mapper.readTree(rows.getString("guards_json")), rows.getString("review_id"), rows.getString("trigger"),
                        scheduleId, scheduleRevision, rows.getString("automatic_key"),
                        scheduleId == null ? null : mapper.readTree(rows.getString("selection_json")).asObject(),
                        rows.getString("owner_id"), rows.getString("lease_until"), rows.getBoolean("cancel_requested"));
            }
        }
    }

    private String requestHash(String reviewId, JsonNode selection) {
        var data = mapper.createObjectNode().put("reviewId", reviewId);
        data.set("selection", selection);
        return privacy.digest(data);
    }

    private String packetHash(JsonNode payload, String model, String policy) {
        var data = mapper.createObjectNode();
        data.set("payload", payload);
        return privacy.digest(data.put("model", model).put("policy", policy));
    }

    private String usage(JsonNode usage) {
        return usage == null || usage.isNull() ? null : privacy.json(usage);
    }

    private static void update(Connection connection, String sql, String... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setString(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static void fields(JsonNode input, Set<String> allowed) {
        ApiJson.requireObject(input);
        if (!allowed.containsAll(input.propertyNames())) {
            throw ApiJson.invalid("");
        }
    }

    private static void requireConsent(JsonNode input, String field) {
        var consent = input.path(field);
        if (!consent.isBoolean() || !consent.booleanValue()) {
            throw ApiJson.invalid(field);
        }
    }

    private void uuid(String id) {
        TaskPayload.uuid(mapper.getNodeFactory().stringNode(id), "", false);
    }

    private static String timestamp() {
        return JournalPeriods.utc(Instant.now());
    }

    private static String prompt() {
        try (var input = PeriodDispatch.class.getResourceAsStream("/analysis/period-review.txt")) {
            if (input == null) {
                throw new IllegalStateException("Missing period review instructions");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private record RunState(String status, String model, String policy, String packetHash, JsonNode payload,
                            JsonNode guards, String reviewId, String trigger, String scheduleId, Long scheduleRevision,
                            String automaticKey, ObjectNode selection, String owner, String leaseUntil, boolean cancelRequested) { }

    private static final class Active {
        private final Thread thread;
        private final CompletableFuture<Void> done = new CompletableFuture<>();

        Active(Thread thread) {
            this.thread = thread;
        }

        CompletableFuture<Void> done() {
            return done;
        }

        synchronized void cancel() {
            if (!done.isDone()) {
                thread.interrupt();
            }
        }

        synchronized void finish() {
            Thread.interrupted();
            done.complete(null);
        }
    }
}
