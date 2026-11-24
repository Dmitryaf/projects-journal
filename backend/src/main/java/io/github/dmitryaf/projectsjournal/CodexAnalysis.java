package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.text.Collator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class CodexAnalysis {

    static final String POLICY = "codex-project-review-v5";
    static final String DEFAULT_QUESTION = "Помоги осмыслить развитие выбранных проектов за этот период. Что изменилось по сути, "
            + "что это даёт и куда разумно двигаться дальше? Посмотри на каждый проект отдельно, а затем на общую картину: "
            + "есть ли полезные связи, переносимые идеи или возможности, которые я мог упустить? Пиши коротко и простыми словами. "
            + "Не придумывай прогресс и не добавляй советы только ради заполнения ответа.";
    private static final String PROMPT = loadPrompt();
    private static final String DEFAULT_MODEL = "codex-default";
    private static final String BAD_OUTPUT = "Ответ Codex не прошёл проверку формата, источников или приватности.";
    private static final String STALE_FILES = "Выбранные файлы или доступ изменились. Просмотрите новый пакет.";
    private static final String CAPACITY = "Уже выполняются два анализа Codex. Дождитесь окончания или остановите один.";
    private static final Set<String> PARENT_POLICIES = Set.of(POLICY, "codex-project-review-v4", "codex-project-review-v3",
            "codex-project-review-v2", "codex-project-review-v1");
    private static final Set<String> PREPARE_FIELDS = Set.of("reviewId", "requestKey", "selection", "question", "model", "files",
            "parentRunId", "readConsent");
    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}");
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Logger LOG = LoggerFactory.getLogger(CodexAnalysis.class);

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final ObjectReader reader;
    private final ReviewService reviews;
    private final PeriodRuns runs;
    private final PeriodContext context;
    private final PeriodReport reports;
    private final AnalysisPrivacy privacy;
    private final CodexRepositoryContext repository;
    private final CodexExecutor executor;
    private final ScheduleGuard scheduleGuard;
    private final String owner = UUID.randomUUID().toString();
    private final ConcurrentHashMap<String, Job> active = new ConcurrentHashMap<>();
    private final Object lifecycle = new Object();
    private final ScheduledExecutorService leases = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("codex-analysis-lease").factory());
    private volatile boolean stopping;

    CodexAnalysis(SqliteStore store, JsonMapper mapper, ReviewService reviews, PeriodRuns runs, PeriodContext context,
                  PeriodReport reports, AnalysisPrivacy privacy, CodexRepositoryContext repository, CodexExecutor executor,
                  ScheduleGuard scheduleGuard) {
        this.store = store;
        this.mapper = mapper;
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.reviews = reviews;
        this.runs = runs;
        this.context = context;
        this.reports = reports;
        this.privacy = privacy;
        this.repository = repository;
        this.executor = executor;
        this.scheduleGuard = scheduleGuard;
    }

    ObjectNode status() {
        return status(false);
    }

    ObjectNode status(boolean refresh) {
        return executor.probe(refresh);
    }

    ObjectNode instructions() {
        return mapper.createObjectNode().put("policy", POLICY).put("question", DEFAULT_QUESTION).put("instruction", PROMPT);
    }

    ObjectNode login() {
        synchronized (lifecycle) {
            ensureRunning();
            if (!active.isEmpty()) {
                throw new TaskError(409, "Дождитесь завершения анализа.");
            }
            return executor.login();
        }
    }

    ObjectNode cancelLogin() {
        executor.cancelLogin();
        return status();
    }

    ObjectNode files(JsonNode input) throws SQLException, IOException {
        strict(input, Set.of("projectIds", "readConsent"));
        consent(input, "readConsent");
        JsonNode ids = input.get("projectIds");
        if (ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > 10) {
            throw ApiJson.invalid("projectIds");
        }
        List<String> projects = new ArrayList<>();
        for (int index = 0; index < ids.size(); index++) {
            projects.add(TaskPayload.uuid(ids.get(index), "projectIds." + index, false));
        }
        return repository.files(projects);
    }

    ObjectNode conversation(JsonNode input) throws SQLException {
        strict(input, Set.of("sessionId"));
        String sessionId = TaskPayload.uuid(input.get("sessionId"), "sessionId", false);
        recover();
        ObjectNode result = mapper.createObjectNode().put("sessionId", sessionId);
        ArrayNode turns = result.putArray("turns");
        try (var connection = store.open(); var statement = connection.prepareStatement("""
                SELECT id FROM analysis_runs
                WHERE json_extract(payload_json,'$.origin.kind')='codex_runtime'
                  AND json_extract(payload_json,'$.origin.sessionId')=? ORDER BY generation LIMIT 20
                """)) {
            connection.setAutoCommit(false);
            statement.setString(1, sessionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    turns.add(run(connection, rows.getString("id")));
                }
            }
            connection.commit();
        }
        return result;
    }

    ObjectNode read(String id) throws SQLException {
        ObjectNode run = runs.read(id);
        origin(run, "Это не встроенный анализ Codex.");
        Job job = active.get(id);
        return mapper.createObjectNode().set("run", run).put("progress", job == null ? null : job.progress);
    }

    ObjectNode prepare(JsonNode input) throws SQLException, IOException {
        return prepare(input, null);
    }

    ObjectNode prepareScheduled(JsonNode input, ScheduledRun schedule) throws SQLException, IOException {
        return prepare(input, Objects.requireNonNull(schedule));
    }

    private ObjectNode prepare(JsonNode input, ScheduledRun schedule) throws SQLException, IOException {
        ObjectNode data = preparation(input);
        if (schedule != null) {
            verifyScheduledOrigin(data);
        }
        String reviewId = value(data, "reviewId");
        String requestHash = privacy.digest(data);
        Draft draft;
        try (var connection = store.open()) {
            connection.setAutoCommit(false);
            ObjectNode previous = earlierRun(connection, data, requestHash, schedule);
            if (previous != null) {
                connection.commit();
                return read(value(previous, "id")).path("run").asObject();
            }
            draft = draft(connection, data, reviews.read(connection, reviewId), schedule);
            connection.commit();
        }
        return store.write(connection -> {
            Instant now = Instant.now();
            String timestamp = JournalPeriods.utc(now);
            ObjectNode previous = earlierRun(connection, data, requestHash, schedule);
            runs.recover(connection, timestamp);
            if (previous != null) {
                return run(connection, value(previous, "id"));
            }
            ObjectNode review = reviews.read(connection, reviewId);
            if (!Objects.equals(draft.review().get("projectId"), review.get("projectId"))) {
                throw new TaskError(410, "Обзор или выбранный проект изменился. Подготовьте новый пакет.");
            }
            context.verify(connection, draft.guards().get("context"));
            checkParent(connection, draft.payload().path("origin"), "");
            String id = UUID.randomUUID().toString();
            long generation = PeriodRuns.nextGeneration(connection, reviewId);
            insert(connection, id, generation, data, requestHash, draft, timestamp, JournalPeriods.utc(now.plusSeconds(900)),
                    schedule);
            if (schedule != null) {
                schedule.record(connection, id);
            }
            return run(connection, id);
        });
    }

    private ObjectNode earlierRun(Connection connection, ObjectNode data, String requestHash, ScheduledRun schedule)
            throws SQLException {
        String requestKey = value(data, "requestKey");
        if (schedule == null) {
            return previous(connection, requestKey, requestHash, data);
        }

        String reviewId = value(data, "reviewId");
        scheduleGuard.verify(connection, schedule.id(), schedule.revision(), data.path("model").asString(DEFAULT_MODEL),
                "codex", POLICY);
        scheduleGuard.verifySelection(connection, schedule.id(), data.path("selection").asObject());
        String existing = schedule.existing(connection);
        if (existing != null) {
            ObjectNode previous = run(connection, existing);
            if (!reviewId.equals(value(previous, "reviewKey"))) {
                throw new TaskError(409, "Для расписания уже сохранён анализ другого периода.");
            }
            return previous;
        }
        try (var statement = connection.prepareStatement("SELECT 1 FROM analysis_runs WHERE review_key=? LIMIT 1")) {
            statement.setString(1, reviewId);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw new TaskError(409, "За этот период уже есть анализ. Автоматического повтора нет.");
                }
            }
        }
        if (previous(connection, requestKey, requestHash, data) != null) {
            throw new TaskError(409, "Этот ключ уже использован для другого запроса. Откройте сохранённый вариант.");
        }
        return null;
    }

    private Draft draft(Connection connection, ObjectNode data, ObjectNode review, ScheduledRun schedule)
            throws SQLException, IOException {
        ObjectNode selection = data.path("selection").asObject();
        List<String> projects = JournalSettings.strings(selection.path("projectIds"));
        if (!"general".equals(value(review, "scopeKey")) && (projects.size() != 1
                || !projects.getFirst().equals(value(review, "projectId")))) {
            throw new TaskError(400, "Обзор должен относиться к выбранному проекту либо быть общим.");
        }
        JsonNode files = data.path("files");
        String parentId = value(data, "parentRunId");
        String model = data.path("model").asString(DEFAULT_MODEL);
        ObjectNode parent = null;
        ObjectNode payload;
        ObjectNode guards;
        String sessionId;
        if (parentId != null) {
            parent = run(connection, parentId);
            JsonNode parentOrigin = origin(parent);
            String policy = value(parentOrigin, "policy");
            if (!"succeeded".equals(value(parent, "status")) || !value(review, "id").equals(value(parent, "reviewId"))
                    || policy == null || !PARENT_POLICIES.contains(policy) || !parentOrigin.path("files").isArray()
                    || value(parentOrigin, "sessionId") == null) {
                throw new TaskError(409, "Уточнять можно завершённый анализ поддерживаемой версии. "
                        + "Для неизвестного результата создайте новый анализ.");
            }
            if (!privacy.digest(parent.path("selection")).equals(privacy.digest(selection))
                    || !sortedFileKeys(parentOrigin.path("files")).equals(sortedFileKeys(files))
                    || !model.equals(value(parent, "model"))) {
                throw new TaskError(409, "Для изменения проектов, файлов, источников или модели начните новое обсуждение.");
            }
            // Use a stable ordering independent of the system locale.
            files = parentOrigin.get("files");
            payload = parent.path("payload").asObject().deepCopy();
            guards = verifiedRow(connection, parent, policy).guards().asObject();
            sessionId = value(parentOrigin, "sessionId");
        } else {
            var prepared = context.build(connection, review, selection, Instant.now(), schedule != null);
            var selected = repository.sources(connection, projects, files);
            payload = prepared.payload();
            payload.withArrayProperty("sources").addAll(selected.sources());
            ArrayNode limitations = payload.withArrayProperty("limitations");
            if (!files.isEmpty()) {
                for (int index = limitations.size() - 1; index >= 0; index--) {
                    if (limitations.get(index).asString().startsWith("Git, пути,")) {
                        limitations.remove(index);
                    }
                }
            }
            limitations.add("Встроенный Codex получает только просмотренный пакет. Инструменты, команды, внешние приложения "
                    + "и самостоятельное чтение файлов отключены. Полного аудита репозитория и полной истории переписок нет.");
            if (!files.isEmpty()) {
                limitations.add("Файлы включены явным выбором: только tracked UTF-8 файлы до 8 КБ каждый. "
                        + "Это текущие тексты, а не восстановление состояния на конец периода.");
            }
            guards = mapper.createObjectNode().set("context", prepared.guards()).set("files", selected.stamps());
            sessionId = UUID.randomUUID().toString();
        }
        payload.putObject("origin").put("kind", "codex_runtime").put("purpose", "project_review")
                .put("sessionId", sessionId).put("parentRunId", parentId).put("question", value(data, "question"))
                .put("policy", POLICY).set("files", files);
        if (schedule != null && schedule.limitations() != null) {
            schedule.limitations().forEach(payload.withArrayProperty("limitations")::add);
        }
        if (privacy.bytes(payload) > 80_000 || privacy.containsSensitive(payload)) {
            throw new TaskError(400, "Пакет больше 80 КБ либо содержит возможный секрет/локальный путь. "
                    + "Уменьшите выбор источников.");
        }
        prompt(connection, payload, parent);
        return new Draft(review, payload, guards, model);
    }

    private void insert(Connection connection, String id, long generation, ObjectNode data, String requestHash, Draft draft,
                        String now, String expiry, ScheduledRun schedule) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO analysis_runs(id,review_id,review_key,scope_key,generation,status,model,policy_version,
                    selection_json,payload_json,guards_json,packet_hash,request_key,request_hash,created_at,expires_at,
                    trigger,schedule_id,schedule_revision,automatic_key)
                VALUES(?,?,?,?,?,'pending',?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, value(data, "reviewId"));
            statement.setString(3, value(data, "reviewId"));
            statement.setString(4, value(draft.review(), "scopeKey"));
            statement.setLong(5, generation);
            statement.setString(6, draft.model());
            statement.setString(7, POLICY);
            statement.setString(8, privacy.json(data.path("selection")));
            statement.setString(9, privacy.json(draft.payload()));
            statement.setString(10, privacy.json(draft.guards()));
            statement.setString(11, packetHash(draft.payload(), draft.model(), POLICY));
            statement.setString(12, value(data, "requestKey"));
            statement.setString(13, requestHash);
            statement.setString(14, now);
            statement.setString(15, expiry);
            statement.setString(16, schedule == null ? "manual" : "scheduled");
            if (schedule == null) {
                statement.setNull(17, Types.VARCHAR);
                statement.setNull(18, Types.INTEGER);
                statement.setNull(19, Types.VARCHAR);
            } else {
                statement.setString(17, schedule.id());
                statement.setLong(18, schedule.revision());
                statement.setString(19, schedule.key());
            }
            statement.executeUpdate();
        }
    }

    private ObjectNode preparation(JsonNode input) {
        strict(input, PREPARE_FIELDS);
        ObjectNode data = mapper.createObjectNode()
                .put("reviewId", TaskPayload.uuid(input.get("reviewId"), "reviewId", false))
                .put("requestKey", TaskPayload.uuid(input.get("requestKey"), "requestKey", false));
        ObjectNode selection = context.normalizeSelection(input.get("selection"));
        if (selection.path("projectIds").isEmpty()) {
            throw new TaskError(400, "Выберите хотя бы один проект.");
        }
        data.set("selection", selection);
        String question = input.has("question") ? ApiJson.text(input.get("question"), "question", 0, 4000, true) : "";
        if (input.has("parentRunId") && question.isEmpty()) {
            throw ApiJson.invalid("");
        }
        if (AnalysisPrivacy.sensitiveText(question)) {
            throw new TaskError(400, "В вопросе возможный секрет или локальный путь. Уберите его перед отправкой.");
        }
        data.put("question", question.isEmpty() ? DEFAULT_QUESTION : question);
        if (input.has("model")) {
            String model = ApiJson.text(input.get("model"), "model", 1, 100, false);
            if (!MODEL.matcher(model).matches()) {
                throw ApiJson.invalid("model");
            }
            data.put("model", model);
        }
        data.set("files", normalizedFiles(input.get("files")));
        if (input.has("parentRunId")) {
            data.put("parentRunId", TaskPayload.uuid(input.get("parentRunId"), "parentRunId", false));
        }
        consent(input, "readConsent");
        return data.put("readConsent", true);
    }

    private ArrayNode normalizedFiles(JsonNode input) {
        ArrayNode result = mapper.createArrayNode();
        if (input == null) {
            return result;
        }
        if (!input.isArray() || input.size() > 12) {
            throw ApiJson.invalid("files");
        }
        List<ObjectNode> files = new ArrayList<>();
        for (int index = 0; index < input.size(); index++) {
            JsonNode file = input.get(index);
            strict(file, Set.of("projectId", "path"));
            String project = TaskPayload.uuid(file.get("projectId"), "files." + index + ".projectId", false);
            String path = ApiJson.text(file.get("path"), "files." + index + ".path", 1, 240, false);
            files.add(mapper.createObjectNode().put("projectId", project).put("path", path));
        }
        Collator order = Collator.getInstance();
        files.sort((a, b) -> order.compare(fileKey(a), fileKey(b)));
        files.forEach(result::add);
        return result;
    }

    private ObjectNode previous(Connection connection, String key, String hash, ObjectNode data) throws SQLException {
        String id;
        String storedHash;
        try (var statement = connection.prepareStatement("SELECT id,request_hash FROM analysis_runs WHERE request_key=?")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return null;
                }
                id = rows.getString("id");
                storedHash = rows.getString("request_hash");
            }
        }
        if (hash.equals(storedHash)) {
            return run(connection, id);
        }
        ObjectNode candidate = runs.read(connection, id);
        JsonNode prior = candidate.path("payload").path("origin");
        if ("codex_runtime".equals(value(prior, "kind")) && prior.path("files").isArray()
                && sortedFileKeys(prior.path("files")).equals(sortedFileKeys(data.path("files")))
                && storedHash.equals(privacy.digest(data.deepCopy().set("files", prior.get("files"))))) {
            return candidate;
        }
        throw new TaskError(409, "Этот ключ уже использован для другого запроса. Откройте сохранённый вариант.");
    }

    private static String fileKey(JsonNode file) {
        return value(file, "projectId") + "/" + value(file, "path");
    }

    private static List<String> sortedFileKeys(JsonNode files) {
        List<String> keys = new ArrayList<>();
        files.forEach(file -> keys.add(fileKey(file)));
        keys.sort(String::compareTo);
        return keys;
    }

    ObjectNode start(String id, JsonNode input) throws SQLException, IOException {
        strict(input, Set.of("packetHash", "consent"));
        consent(input, "consent");
        String packetHash = ApiJson.text(input.get("packetHash"), "packetHash", 64, 64, false);
        if (!HASH.matcher(packetHash).matches()) {
            throw ApiJson.invalid("packetHash");
        }
        ObjectNode response = read(id);
        ObjectNode reviewed = response.path("run").asObject();
        checkPacketHash(reviewed, packetHash);
        if (!"pending".equals(value(reviewed, "status"))) {
            return response;
        }
        if (active.size() >= 2) {
            throw new TaskError(409, CAPACITY);
        }
        ensureRunning();
        try (var connection = store.open()) {
            verifySchedule(connection, reviewed, row(connection, id));
        }
        ObjectNode status = executor.probe();
        ensureRunning();
        if (!status.path("available").asBoolean(false)) {
            throw new TaskError(503, "Codex CLI недоступен. Проверьте установку и вход через codex login.");
        }
        if (status.path("authenticated").isBoolean() && !status.path("authenticated").booleanValue()) {
            throw new TaskError(503, "Войдите в отдельный профиль Codex для приложения.");
        }
        String prompt;
        try (var connection = store.open()) {
            verify(connection, reviewed);
            JsonNode origin = origin(reviewed);
            String parentId = value(origin, "parentRunId");
            prompt = prompt(connection, reviewed.path("payload").asObject(), parentId == null ? null : run(connection, parentId));
        }

        synchronized (lifecycle) {
            ensureRunning();
            if (store.write(connection -> claim(connection, reviewed))) {
                Job job = new Job();
                active.put(id, job);
                Thread.ofVirtual().name("codex-analysis").start(() -> execute(reviewed, prompt, job));
            }
        }
        return read(id);
    }

    private boolean claim(Connection connection, ObjectNode reviewed) throws SQLException {
        Instant now = Instant.now();
        String timestamp = JournalPeriods.utc(now);
        runs.recover(connection, timestamp);
        String id = value(reviewed, "id");
        ObjectNode current = run(connection, id);
        checkPacketHash(current, value(reviewed, "packetHash"));
        if (!"pending".equals(value(current, "status"))) {
            return false;
        }
        Row row = verifiedRow(connection, current, POLICY);
        verifySchedule(connection, current, row);
        if (row.cancelRequested()) {
            throw stopped();
        }
        try (var statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM analysis_runs WHERE status='running'
                AND json_extract(payload_json,'$.origin.kind')='codex_runtime'
                """); var rows = statement.executeQuery()) {
            if (rows.next() && rows.getInt(1) >= 2) {
                throw new TaskError(409, CAPACITY);
            }
        }
        requireReview(connection, current);
        context.verify(connection, row.guards().get("context"));
        checkParent(connection, origin(current), id);
        try (var statement = connection.prepareStatement("""
                UPDATE analysis_runs SET status='running',approved_at=?,started_at=?,lease_until=?,owner_id=?
                WHERE id=? AND status='pending'
                """)) {
            statement.setString(1, timestamp);
            statement.setString(2, timestamp);
            statement.setString(3, JournalPeriods.utc(now.plusSeconds(30)));
            statement.setString(4, owner);
            statement.setString(5, id);
            return statement.executeUpdate() == 1;
        }
    }

    private void execute(ObjectNode reviewed, String prompt, Job job) {
        String id = value(reviewed, "id");
        ScheduledFuture<?> heartbeat = null;
        try {
            heartbeat = leases.scheduleAtFixedRate(() -> renew(id, job), 1, 1, TimeUnit.SECONDS);
            beforeDispatch(reviewed, job);
            String selected = value(reviewed, "model");
            var result = executor.execute(prompt, DEFAULT_MODEL.equals(selected) ? null : selected, job.cancellation,
                    stage -> job.progress = stage, () -> beforeDispatch(reviewed, job));
            ObjectNode report = report(result.output());
            reports.validate(reviewed.path("payload"), report);
            saveSuccess(reviewed, job, result, report);
        } catch (SQLException | RuntimeException error) {
            saveFailure(id, error);
        } finally {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            job.cancellation.close();
            active.remove(id, job);
            job.done.complete(null);
        }
    }

    private void beforeDispatch(ObjectNode reviewed, Job job) {
        String id = value(reviewed, "id");
        try (var connection = store.open()) {
            assertOwner(row(connection, id), job);
            verify(connection, reviewed);
            Row current = row(connection, id);
            assertOwner(current, job);
            verifySchedule(connection, reviewed, current);
            context.verify(connection, current.guards().get("context"));
        } catch (IOException exception) {
            throw new TaskError(410, STALE_FILES);
        } catch (SQLException exception) {
            throw new TaskError(503, "Не удалось проверить пакет перед отправкой. Запрос не отправлен.");
        }
    }

    private void verify(Connection connection, ObjectNode reviewed) throws SQLException, IOException {
        ObjectNode current = run(connection, value(reviewed, "id"));
        checkPacketHash(current, value(reviewed, "packetHash"));
        requireReview(connection, current);
        Row row = verifiedRow(connection, current, POLICY);
        verifySchedule(connection, current, row);
        context.verify(connection, row.guards().get("context"));
        var selected = repository.sources(connection,
                JournalSettings.strings(current.path("selection").path("projectIds")), origin(current).path("files"));
        if (!privacy.digest(selected.stamps()).equals(privacy.digest(row.guards().get("files")))) {
            throw new TaskError(410, STALE_FILES);
        }
    }

    private void requireReview(Connection connection, ObjectNode run) throws SQLException {
        String reviewId = value(run, "reviewId");
        if (reviewId == null) {
            throw new TaskError(410, "Обзор удалён. Запрос не отправлен.");
        }
        reviews.read(connection, reviewId);
    }

    private void verifySchedule(Connection connection, JsonNode run, Row row) throws SQLException {
        boolean scheduled = "scheduled".equals(value(run, "trigger"));
        if (!scheduled && row.scheduleId() == null && row.scheduleRevision() == null && row.automaticKey() == null) {
            return;
        }
        if (!scheduled || row.scheduleId() == null || row.scheduleRevision() == null || row.automaticKey() == null) {
            throw new TaskError(410, "Разрешение расписания отключено или изменилось.");
        }
        verifyScheduledOrigin(origin(run));
        scheduleGuard.verify(connection, row.scheduleId(), row.scheduleRevision(), value(run, "model"), "codex", POLICY);
        scheduleGuard.verifySelection(connection, row.scheduleId(), run.path("selection").asObject());
    }

    private static void verifyScheduledOrigin(JsonNode preparation) {
        if (preparation.hasNonNull("parentRunId") || !preparation.path("files").isArray()
                || !preparation.path("files").isEmpty() || !DEFAULT_QUESTION.equals(value(preparation, "question"))) {
            throw new TaskError(400, "Регулярный разбор использует промпт по умолчанию без файлов и прежнего обсуждения.");
        }
    }

    private void assertOwner(Row row, Job job) {
        if (stopping || job.cancellation.cancelled() || row.cancelRequested() || !owner.equals(row.ownerId())
                || !"running".equals(row.status()) || row.leaseUntil() == null
                || row.leaseUntil().compareTo(JournalPeriods.utc(Instant.now())) <= 0) {
            throw stopped();
        }
    }

    private void renew(String id, Job job) {
        Instant now = Instant.now();
        try (var connection = store.open(); var statement = connection.prepareStatement("""
                UPDATE analysis_runs SET lease_until=? WHERE id=? AND owner_id=? AND status='running'
                AND cancel_requested=0 AND lease_until>?
                """)) {
            statement.setString(1, JournalPeriods.utc(now.plusSeconds(30)));
            statement.setString(2, id);
            statement.setString(3, owner);
            statement.setString(4, JournalPeriods.utc(now));
            if (statement.executeUpdate() == 0) {
                job.cancellation.cancel();
            }
        } catch (SQLException unavailable) {
            job.cancellation.cancel();
        }
    }

    private ObjectNode report(String output) {
        if (output.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new TaskError(400, "Ответ Codex превышает допустимый размер.");
        }
        try {
            return reports.normalize(reader.readTree(output));
        } catch (JacksonException | TaskError invalid) {
            throw new TaskError(400, BAD_OUTPUT);
        }
    }

    private void saveSuccess(ObjectNode reviewed, Job job, CodexExecutor.Result result, ObjectNode report)
            throws SQLException {
        store.write(connection -> {
            String id = value(reviewed, "id");
            ObjectNode current = run(connection, id);
            checkPacketHash(current, value(reviewed, "packetHash"));
            Row row = verifiedRow(connection, current, POLICY);
            if (!owner.equals(row.ownerId()) || row.cancelRequested() || job.cancellation.cancelled()
                    || !"running".equals(row.status()) || row.leaseUntil() == null
                    || row.leaseUntil().compareTo(JournalPeriods.utc(Instant.now())) <= 0) {
                throw new CodexExecutor.Failure("Выполнение остановлено. Полученный после остановки ответ не принят.", "unknown");
            }
            for (JsonNode guard : row.guards().path("context")) {
                if ("project".equals(value(guard, "kind"))) {
                    ProjectRow project = ProjectRow.find(connection, value(guard, "id"));
                    if (project == null || !projectStamp(project).equals(value(guard, "stamp"))) {
                        throw new TaskError(410, "Доступ к проекту изменился во время анализа. Ответ не принят.");
                    }
                }
            }
            try (var statement = connection.prepareStatement("""
                    UPDATE analysis_runs SET status='succeeded',finished_at=?,report_json=?,raw_output=?,usage_json=?,
                        response_id=?,owner_id=NULL,lease_until=NULL,error=NULL
                    WHERE id=? AND owner_id=? AND status='running'
                    """)) {
                statement.setString(1, JournalPeriods.utc(Instant.now()));
                statement.setString(2, privacy.json(report));
                statement.setString(3, result.output());
                statement.setString(4, result.usage() == null ? null : privacy.json(result.usage()));
                statement.setString(5, result.threadId());
                statement.setString(6, id);
                statement.setString(7, owner);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private String projectStamp(ProjectRow project) {
        return privacy.digest(mapper.createObjectNode().put("visibility", project.visibility())
                .put("privateContext", project.privateContextPath()).put("allowlist", project.publicationAllowlist()));
    }

    private void saveFailure(String id, Exception error) {
        String state = error instanceof CodexExecutor.Failure failure ? failure.state() : "failed";
        String message = error instanceof CodexExecutor.Failure || error instanceof TaskError ? error.getMessage() : BAD_OUTPUT;
        try (var connection = store.open(); var statement = connection.prepareStatement("""
                UPDATE analysis_runs SET status=?,finished_at=?,error=?,owner_id=NULL,lease_until=NULL
                WHERE id=? AND owner_id=? AND status='running'
                """)) {
            statement.setString(1, state);
            statement.setString(2, JournalPeriods.utc(Instant.now()));
            statement.setString(3, message);
            statement.setString(4, id);
            statement.setString(5, owner);
            statement.executeUpdate();
        } catch (SQLException unavailable) {
            LOG.warn("Не удалось сохранить состояние выполнения Codex. Оно будет восстановлено по истечении ожидания.");
        }
    }

    void abort(String id) {
        Job job = active.get(id);
        if (job != null) {
            job.cancellation.cancel();
        }
    }

    @PreDestroy
    void stop() {
        List<Job> jobs;
        synchronized (lifecycle) {
            stopping = true;
            jobs = List.copyOf(active.values());
        }
        try {
            executor.cancelLogin();
        } finally {
            jobs.forEach(job -> job.cancellation.cancel());
            CompletableFuture.allOf(jobs.stream().map(job -> job.done).toArray(CompletableFuture<?>[]::new)).join();
            leases.shutdownNow();
        }
    }

    private void recover() throws SQLException {
        store.write(connection -> {
            runs.recover(connection, JournalPeriods.utc(Instant.now()));
            return null;
        });
    }

    private Row row(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT status,policy_version,packet_hash,guards_json,owner_id,cancel_requested,schedule_id,
                    schedule_revision,automatic_key,lease_until
                FROM analysis_runs WHERE id=?
                """)) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskError(404, "Анализ не найден.");
                }
                long revisionValue = rows.getLong("schedule_revision");
                Long revision = rows.wasNull() ? null : revisionValue;
                return new Row(rows.getString("status"), rows.getString("policy_version"), rows.getString("packet_hash"),
                        mapper.readTree(rows.getString("guards_json")), rows.getString("owner_id"),
                        rows.getBoolean("cancel_requested"), rows.getString("schedule_id"), revision,
                        rows.getString("automatic_key"), rows.getString("lease_until"));
            }
        }
    }

    private Row verifiedRow(Connection connection, JsonNode run, String policy) throws SQLException {
        Row row = row(connection, value(run, "id"));
        if (!row.guards().path("context").isArray() || !row.guards().path("files").isArray()
                || !policy.equals(value(origin(run), "policy")) || !policy.equals(row.policy())
                || !packetHash(run.path("payload"), value(run, "model"), policy).equals(row.packetHash())) {
            throw new TaskError(409, "Политика или сохранённый пакет изменились. Подготовьте новый анализ.");
        }
        return row;
    }

    private String packetHash(JsonNode payload, String model, String policy) {
        return privacy.digest(mapper.createObjectNode().set("payload", payload).put("model", model).put("policy", policy));
    }

    private ObjectNode run(Connection connection, String id) throws SQLException {
        ObjectNode run = runs.read(connection, id);
        origin(run, "Это не встроенный анализ Codex.");
        return run;
    }

    private static JsonNode origin(JsonNode run) {
        return origin(run, "Выберите встроенный анализ Codex.");
    }

    private static JsonNode origin(JsonNode run, String error) {
        JsonNode origin = run.path("payload").path("origin");
        if (!"codex_runtime".equals(value(origin, "kind"))) {
            throw new TaskError(403, error);
        }
        return origin;
    }

    private static void checkParent(Connection connection, JsonNode origin, String ownId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT id,status FROM analysis_runs WHERE json_extract(payload_json,'$.origin.sessionId')=?
                AND id!=? ORDER BY generation DESC LIMIT 1
                """)) {
            statement.setString(1, value(origin, "sessionId"));
            statement.setString(2, ownId);
            String parent = value(origin, "parentRunId");
            try (var rows = statement.executeQuery()) {
                boolean exists = rows.next();
                if (parent != null && (!exists || !parent.equals(rows.getString("id")) || !"succeeded".equals(rows.getString("status")))) {
                    throw new TaskError(409, "Обсуждение уже изменилось. Откройте последний ответ; повторного запуска нет.");
                }
                if (parent == null && exists) {
                    throw new TaskError(409, "Обсуждение уже существует.");
                }
            }
        }
    }

    private String prompt(Connection connection, ObjectNode payload, ObjectNode parent) throws SQLException {
        ObjectNode packet = payload.deepCopy();
        JsonNode origin = packet.remove("origin");
        ArrayNode chain = mapper.createArrayNode();
        Set<String> seen = new HashSet<>();
        while (parent != null) {
            if (!seen.add(value(parent, "id")) || chain.size() >= 5 || !"succeeded".equals(value(parent, "status"))
                    || !parent.hasNonNull("report")) {
                throw new TaskError(409, "История обсуждения неполна или достигнут лимит шести ответов. Начните новый анализ.");
            }
            JsonNode source = origin(parent);
            if (!Objects.equals(value(source, "sessionId"), value(origin, "sessionId"))) {
                throw new TaskError(409, "История обсуждения неполна. Начните новый анализ.");
            }
            chain.insert(0, mapper.createObjectNode().set("question", source.get("question"))
                    .set("report", parent.get("report")));
            String preceding = value(source, "parentRunId");
            parent = preceding == null ? null : run(connection, preceding);
        }
        String prompt = PROMPT + "\n" + privacy.json(mapper.createObjectNode().set("question", origin.get("question"))
                .set("context", packet).set("conversation", chain));
        if (prompt.getBytes(StandardCharsets.UTF_8).length > 100_000) {
            throw new TaskError(400, "Обсуждение вместе с контекстом больше 100 КБ. "
                    + "Начните новый анализ с меньшим набором источников.");
        }
        return prompt;
    }

    private static void checkPacketHash(JsonNode run, String hash) {
        if (!hash.equals(value(run, "packetHash"))) {
            throw new TaskError(409, "Пакет не совпадает с просмотренным.");
        }
    }

    private void ensureRunning() {
        if (stopping) {
            throw new TaskError(409, "Codex останавливается. Запрос не отправлен.");
        }
    }

    private static TaskError stopped() {
        return new TaskError(409, "Запуск остановлен до отправки.");
    }

    private static void consent(JsonNode input, String field) {
        JsonNode value = input.path(field);
        if (!value.isBoolean() || !value.booleanValue()) {
            throw ApiJson.invalid(field);
        }
    }

    private static void strict(JsonNode input, Set<String> fields) {
        ApiJson.requireObject(input);
        if (!fields.containsAll(input.propertyNames())) {
            throw ApiJson.invalid("");
        }
    }

    private static String value(JsonNode node, String field) {
        return node.path(field).stringValue(null);
    }

    private static String loadPrompt() {
        try {
            return new ClassPathResource("codex-analysis-prompt.txt").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException missing) {
            throw new IllegalStateException("Инструкция анализа Codex недоступна.", missing);
        }
    }

    private record Draft(ObjectNode review, ObjectNode payload, ObjectNode guards, String model) {
    }

    private record Row(String status, String policy, String packetHash, JsonNode guards, String ownerId,
                       boolean cancelRequested, String scheduleId, Long scheduleRevision, String automaticKey,
                       String leaseUntil) {
    }

    private static final class Job {
        private final CodexCancellation cancellation = new CodexCancellation();
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private volatile String progress = "Подготовка запуска";
    }
}
