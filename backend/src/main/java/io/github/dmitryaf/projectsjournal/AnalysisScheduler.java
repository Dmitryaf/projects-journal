package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class AnalysisScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(AnalysisScheduler.class);
    private static final String UNCERTAIN = "Не удалось подтвердить исход отправленного запроса. Возможно использование "
            + "лимитов или оплата; продолжение требует подтверждения без повтора этого периода.";

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final ScheduleGuard guard;
    private final ReviewService reviews;
    private final PeriodRuns runs;
    private final PeriodContext context;
    private final PeriodDispatch openAi;
    private final CodexContext dialogs;
    private final CodexAnalysis codex;
    private final String owner = UUID.randomUUID().toString();
    private final ReentrantLock tickLock = new ReentrantLock();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("analysis-scheduler").factory());
    private volatile boolean stopping;

    AnalysisScheduler(SqliteStore store, JsonMapper mapper, ScheduleGuard guard, ReviewService reviews, PeriodRuns runs,
                      PeriodContext context, PeriodDispatch openAi, CodexContext dialogs, CodexAnalysis codex) {
        this.store = store;
        this.mapper = mapper;
        this.guard = guard;
        this.reviews = reviews;
        this.runs = runs;
        this.context = context;
        this.openAi = openAi;
        this.dialogs = dialogs;
        this.codex = codex;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        timer.scheduleWithFixedDelay(this::tick, 0, 60, TimeUnit.SECONDS);
    }

    void tick() {
        if (stopping || !tickLock.tryLock()) {
            return;
        }
        try {
            var schedule = claim();
            if (schedule != null) {
                process(schedule);
            }
        } catch (SQLException | RuntimeException failure) {
            LOG.warn("Scheduled analysis tick failed ({})", failure.getClass().getSimpleName());
        } finally {
            tickLock.unlock();
        }
    }

    private ScheduleGuard.Row claim() throws SQLException {
        return store.write(connection -> {
            Instant now = Instant.now();
            var id = first(connection, """
                    SELECT id FROM analysis_schedules WHERE enabled=1 AND (lease_until IS NULL OR lease_until<=?)
                    ORDER BY updated_at,id LIMIT 1
                    """, JournalPeriods.utc(now));
            if (id == null) {
                return null;
            }
            execute(connection, "UPDATE analysis_schedules SET lease_owner=?,lease_until=?,updated_at=? WHERE id=?",
                    owner, JournalPeriods.utc(now.plusSeconds(120)), JournalPeriods.utc(now), id);
            return guard.row(connection, id);
        });
    }

    private void process(ScheduleGuard.Row row) throws SQLException {
        String key = "schedule:" + row.id() + ":" + row.kind() + ":" + row.nextPeriodStart();
        try {
            boolean usesCodex = row.provider().equals("codex");
            var status = openAi.status();
            if (!usesCodex && status.path("model").isString() && !row.model().equals(status.get("model").stringValue())) {
                throw new TaskError(410, "Модель изменилась. Подтвердите расписание заново.");
            }
            verify(row);
            var unfinished = unfinished(row, key);
            if (unfinished != null) {
                var previous = runs.read(unfinished);
                switch (value(previous, "status")) {
                    case "running" -> { return; }
                    case "unknown" -> {
                        pause(row, unfinished, UNCERTAIN);
                        return;
                    }
                    default -> { }
                }
            }
            var bounds = JournalPeriods.bounds(row.kind(), row.nextPeriodStart(), row.timezone());
            if (Instant.parse(bounds.nextStartAt()).isAfter(Instant.now())) {
                return;
            }
            var next = bounds(row, Instant.parse(bounds.nextStartAt()));
            if (!Instant.parse(next.nextStartAt()).isAfter(Instant.now())) {
                var current = bounds(row, Instant.now());
                advance(row, current.periodStart(), null, "Пропущено несколько периодов во время простоя. "
                        + "Они не отправлены; следующий выпуск после завершения периода с " + current.periodStart() + ".");
                return;
            }
            if (usesCodex) {
                var available = codex.status();
                if (stopping) {
                    return;
                }
                verify(row);
                if (!available.path("available").asBoolean(false) || !available.path("authenticated").asBoolean(false)) {
                    pause(row, null, "Войдите в отдельный профиль Codex для приложения и проверьте подключение. "
                            + "Продолжение требует подтверждения.");
                    return;
                }
            } else if (!status.path("ready").asBoolean(false)) {
                advance(row, next.periodStart(), null,
                        "AI временно недоступен. Этот период пропущен без отправки; следующие выпуски остаются включены.");
                return;
            }
            processPeriod(row, key, next.periodStart(), usesCodex);
        } catch (IOException | SQLException | RuntimeException failure) {
            if (!stopping) {
                var uncertain = unfinished(row, key);
                if (uncertain != null) {
                    pause(row, uncertain, UNCERTAIN);
                } else if (failure instanceof TaskError error && (error.status() == 403 || error.status() == 410)) {
                    pause(row, null, error.getMessage());
                } else {
                    advance(row, bounds(row, Instant.now()).periodStart(), null,
                            "Техническая ошибка подготовки. Этот период пропущен без повтора; следующие выпуски "
                                    + "остаются включены. Проверьте сохранённые запуски.");
                }
            }
        } finally {
            store.write(connection -> execute(connection,
                    "UPDATE analysis_schedules SET lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",
                    row.id(), owner));
        }
    }

    private void processPeriod(ScheduleGuard.Row row, String key, String next, boolean usesCodex) throws IOException, SQLException {
        if (stopping) {
            return;
        }
        String projectId = row.scopeKey().equals("general") ? null : row.scopeKey().substring("project:".length());
        var review = reviews.createScheduled(projectId, row.kind(), row.nextPeriodStart(), row.timezone());
        if (Instant.parse(value(review, "nextStartAt")).isAfter(Instant.now())) {
            return;
        }
        String reviewId = value(review, "id");
        String existing;
        try (var connection = store.open()) {
            existing = first(connection, "SELECT id FROM analysis_runs WHERE review_key=? ORDER BY generation LIMIT 1", reviewId);
            if (existing != null && first(connection, "SELECT id FROM analysis_runs WHERE id=? AND automatic_key=?", existing, key) == null) {
                advance(row, next, null, "За этот период уже есть анализ. Повтор не отправлен.");
                return;
            }
        }

        var selection = context.normalizeSelection(row.selection());
        List<String> limitations = existing == null ? capture(reviewId, row, selection) : List.of();
        if (stopping) {
            return;
        }
        verify(row);
        ObjectNode run;
        if (existing != null) {
            run = runs.read(existing);
        } else {
            var scheduled = new ScheduledRun(row.id(), row.revision(), key, limitations);
            var input = mapper.createObjectNode().put("reviewId", reviewId).put("requestKey", UUID.randomUUID().toString())
                    .put("readConsent", true).set("selection", selection);
            if (usesCodex && !row.model().equals("codex-default")) {
                input.put("model", row.model());
            }
            run = usesCodex ? codex.prepareScheduled(input, scheduled) : openAi.prepareScheduled(input, scheduled);
        }
        if (stopping) {
            return;
        }
        boolean hasData = hasData(run);
        if (value(run, "status").equals("pending")) {
            var consent = mapper.createObjectNode().put("packetHash", value(run, "packetHash")).put("consent", true);
            String id = value(run, "id");
            run = !hasData ? openAi.cancel(id)
                    : usesCodex ? codex.start(id, consent).get("run").asObject()
                    : openAi.run(id, consent);
        }
        String id = value(run, "id");
        switch (value(run, "status")) {
            case "running" -> { }
            case "unknown" -> pause(row, id, UNCERTAIN);
            case "succeeded" -> advance(row, next, id, "Результат сохранён.");
            default -> advance(row, next, id, hasData
                    ? "Этот выпуск завершился ошибкой или отменён. Повтор не отправлен; следующие периоды остаются включены."
                    : "В разрешённых источниках нет данных; запрос не отправлен.");
        }
    }

    private List<String> capture(String reviewId, ScheduleGuard.Row row, ObjectNode selection) throws IOException, SQLException {
        var input = mapper.createObjectNode().put("reviewId", reviewId).put("readConsent", true).put("saveConsent", true);
        var projects = input.putArray("projects");
        for (var project : row.access()) {
            if (project.has("codexRevision")) {
                projects.addObject().put("projectId", value(project, "id")).set("revision", project.get("codexRevision"));
            }
        }
        if (projects.isEmpty()) {
            return List.of();
        }
        var prepared = dialogs.prepare(input);
        var digests = selection.putArray("codexDigestIds");
        prepared.path("items").forEach(item -> digests.add(value(item, "id")));
        var limitations = new ArrayList<String>();
        prepared.path("limitations").forEach(item -> limitations.add(item.stringValue()));
        return limitations;
    }

    private void verify(ScheduleGuard.Row row) throws SQLException {
        try (var connection = store.open()) {
            guard.verify(connection, row.id(), row.revision(), row.model(), row.provider(),
                    row.provider().equals("codex") ? CodexAnalysis.POLICY : PeriodContext.POLICY);
        }
    }

    private String unfinished(ScheduleGuard.Row row, String key) throws SQLException {
        try (var connection = store.open()) {
            return first(connection, """
                    SELECT id FROM analysis_runs WHERE schedule_id=? AND automatic_key=? AND status IN ('running','unknown')
                    """, row.id(), key);
        }
    }

    private void pause(ScheduleGuard.Row row, String runId, String note) throws SQLException {
        store.write(connection -> execute(connection, """
                UPDATE analysis_schedules SET enabled=0,revision=revision+1,last_run_id=COALESCE(?,last_run_id),note=?,updated_at=?
                WHERE id=? AND revision=? AND lease_owner=?
                """, runId, note, JournalPeriods.utc(Instant.now()), row.id(), row.revision(), owner));
    }

    private void advance(ScheduleGuard.Row row, String next, String runId, String note) throws SQLException {
        store.write(connection -> execute(connection, """
                UPDATE analysis_schedules SET next_period_start=?,last_run_id=COALESCE(?,last_run_id),note=?,updated_at=?
                WHERE id=? AND revision=? AND enabled=1 AND lease_owner=?
                """, next, runId, note, JournalPeriods.utc(Instant.now()), row.id(), row.revision(), owner));
    }

    private static JournalPeriods.Bounds bounds(ScheduleGuard.Row row, Instant instant) {
        var day = LocalDate.ofInstant(instant, JournalPeriods.zone(row.timezone())).toString();
        return JournalPeriods.bounds(row.kind(), day, row.timezone());
    }

    private static boolean hasData(JsonNode run) {
        for (var source : run.path("payload").path("sources")) {
            if (!TaskPayload.trim(source.path("text").asString("")).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String value(JsonNode node, String field) {
        return node.path(field).asString();
    }

    private static String first(Connection connection, String sql, String... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setString(index + 1, values[index]);
            }
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private static int execute(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            return statement.executeUpdate();
        }
    }

    @PreDestroy
    void stop() {
        stopping = true;
        timer.shutdownNow();
        try {
            timer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
