package io.github.dmitryaf.projectsjournal;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class PeriodContext {

    static final String POLICY = "period-review-v7";
    private static final int MAX_INPUT_BYTES = 80_000;
    private static final Set<String> SOURCE_KINDS = Set.of("task", "check", "decision", "file_decision", "observation");
    private static final String STALE = "Источник изменён, удалён или доступ отозван после подготовки. "
            + "Просмотрите новый пакет; запрос не отправлен.";
    private static final String NO_EVENTS = "В выбранных источниках нет данных. "
            + "Выберите другие источники или сохраните собственный итог.";
    private static final String DIALOG_LIMIT = "Ограниченная выборка user/assistant из последних 50 обращений: "
            + "не более 8 обращений с текстом, 40 сообщений и 1000 байт на исходную выдержку. "
            + "Сохранены только выбранные выдержки, возможны дополнительные сокращения. "
            + "Пересказ, намерения и заявления ассистента не доказывают выполнения работы. "
            + "Нет автоматического наблюдения за изменениями после checkedAt.";
    private static final Set<String> SELECTION_FIELDS = Set.of("projectIds", "includeGeneral", "sourceKinds",
            "includeMetadata", "includeOpenTasks", "includeUserText", "includeRelatedReviews", "includeRelatedAnalyses",
            "includeProjectHistory", "codexDigestIds");
    private static final String PRIOR_COMPLETIONS = """
            SELECT h.id,h.recorded_at,h.snapshot FROM task_history h
            WHERE EXISTS(SELECT 1 FROM json_each(%s) WHERE value=?) AND h.kind='completed' AND h.recorded_at<?
            AND h.id=(SELECT MAX(previous.id) FROM task_history previous
              WHERE previous.task_id=h.task_id AND EXISTS(SELECT 1 FROM json_each(%s) WHERE value=?)
              AND previous.kind='completed' AND previous.recorded_at<?)
            ORDER BY h.recorded_at DESC,h.id DESC LIMIT 5
            """.formatted(TaskService.projectIdsSql("h.snapshot"), TaskService.projectIdsSql("previous.snapshot"));

    private final JsonMapper mapper;
    private final HistoryService history;
    private final ReviewService reviews;
    private final TaskService tasks;
    private final ProjectService projects;
    private final AnalysisPrivacy privacy;
    private final SavedCodexDigest digests;

    PeriodContext(JsonMapper mapper, HistoryService history, ReviewService reviews, TaskService tasks,
            ProjectService projects, AnalysisPrivacy privacy, JournalSettings settings) {
        this.mapper = mapper;
        this.history = history;
        this.reviews = reviews;
        this.tasks = tasks;
        this.projects = projects;
        this.privacy = privacy;
        this.digests = new SavedCodexDigest(mapper, settings, privacy);
    }

    record Prepared(ObjectNode payload, ArrayNode guards) {
    }

    ObjectNode normalizeSelection(JsonNode input) {
        ApiJson.requireObject(input);
        if (!SELECTION_FIELDS.containsAll(input.propertyNames())) {
            throw ApiJson.invalid("");
        }
        var result = mapper.createObjectNode().set("projectIds", selectionArray(input, "projectIds", 10));
        result.put("includeGeneral", bool(input, "includeGeneral"));
        result.set("sourceKinds", selectionArray(input, "sourceKinds", 5));
        for (String field : List.of("includeMetadata", "includeOpenTasks", "includeUserText", "includeRelatedReviews")) {
            result.put(field, bool(input, field));
        }
        result.put("includeRelatedAnalyses", input.has("includeRelatedAnalyses") && bool(input, "includeRelatedAnalyses"));
        if (input.has("includeProjectHistory")) {
            result.put("includeProjectHistory", bool(input, "includeProjectHistory"));
        }
        result.set("codexDigestIds", input.has("codexDigestIds")
                ? selectionArray(input, "codexDigestIds", 20) : mapper.createArrayNode());
        if (result.path("sourceKinds").isEmpty() && result.path("codexDigestIds").isEmpty()
                && List.of("includeMetadata", "includeOpenTasks", "includeUserText", "includeRelatedReviews",
                        "includeRelatedAnalyses", "includeProjectHistory").stream().noneMatch(flag -> on(result, flag))) {
            throw new TaskError(400, "Выберите хотя бы один источник.");
        }
        return result;
    }

    private ArrayNode selectionArray(JsonNode input, String name, int maximum) {
        JsonNode values = input.get(name);
        if (values == null || !values.isArray() || values.size() > maximum) {
            throw ApiJson.invalid(name);
        }
        var sorted = new TreeSet<String>();
        for (int index = 0; index < values.size(); index++) {
            JsonNode item = values.get(index);
            String field = name + "." + index;
            if (name.equals("sourceKinds")) {
                String kind = ApiJson.text(item, field, 1, 40, false);
                if (!SOURCE_KINDS.contains(kind)) {
                    throw ApiJson.invalid(field);
                }
                sorted.add(kind);
            } else {
                sorted.add(TaskPayload.uuid(item, field, false));
            }
        }
        var result = mapper.createArrayNode();
        sorted.forEach(result::add);
        return result;
    }

    private static boolean bool(JsonNode input, String field) {
        JsonNode node = input.get(field);
        if (node == null || !node.isBoolean()) {
            throw ApiJson.invalid(field);
        }
        return node.booleanValue();
    }

    Prepared build(Connection connection, ObjectNode review, ObjectNode selection, Instant now, boolean allowEmpty)
            throws SQLException {
        var ids = JournalSettings.strings(selection.path("projectIds"));
        boolean general = on(selection, "includeGeneral");
        String project = scopeProject(review);
        if (project != null && (general || ids.size() != 1 || !project.equals(ids.getFirst()))) {
            throw new TaskError(400, "Проектный обзор использует только свой проект. Для сравнения проектов откройте общий обзор.");
        }
        if (ids.isEmpty() && !general) {
            throw new TaskError(400, "Выберите проекты или явно разрешите общие записи.");
        }
        var draft = new Sources(ids, general);
        addProjects(connection, draft, on(selection, "includeMetadata"));
        if (on(selection, "includeOpenTasks")) {
            addOpenTasks(connection, draft);
        }
        Set<String> kinds = new HashSet<>(JournalSettings.strings(selection.path("sourceKinds")));
        if (!kinds.isEmpty()) {
            addEvents(connection, review, kinds, draft);
        }
        if (on(selection, "includeUserText")) {
            addReview(draft, review, false);
        }
        if (on(selection, "includeProjectHistory")) {
            addBackground(connection, review, draft);
        }
        if (on(selection, "includeRelatedReviews") || on(selection, "includeRelatedAnalyses")) {
            addRelated(connection, review, selection, draft);
        }
        addDigests(connection, review, selection, draft);
        if (draft.sources.isEmpty() && !allowEmpty) {
            throw new TaskError(400, NO_EVENTS);
        }
        ObjectNode packet = payload(review, selection, now, draft);
        int bytes = privacy.bytes(packet);
        if (bytes > MAX_INPUT_BYTES) {
            throw new TaskError(400, "Пакет больше 80 000 байт (" + bytes + "). Выберите неделю вместо месяца, "
                    + "меньше проектов или источников; пакет не обрезается и не отправлен.");
        }
        if (privacy.containsSensitive(packet)) {
            throw new TaskError(400, "В выбранных текстах найден возможный секрет или локальный путь. "
                    + "Исключите источник либо исправьте текст.");
        }
        return new Prepared(packet, draft.guards);
    }

    private void addProjects(Connection connection, Sources draft, boolean metadata) throws SQLException {
        for (String id : draft.projects) {
            ProjectRow row = ProjectRow.find(connection, id);
            if (row == null) {
                throw new TaskError(410, "Выбранный проект удалён. Запрос не подготовлен.");
            }
            draft.guard("project", id, digests.projectStamp(row));
            if (metadata) {
                var fields = projectFields(row);
                draft.guard("project_metadata", id, privacy.digest(fields));
                draft.add(source("project:" + id, "project", id).put("title", value(fields, "name"))
                        .put("text", privacy.json(fields)).putNull("day")
                        .put("recordedAt", value(mapper.readTree(row.snapshot()), "scannedAt"))
                        .put("dateQuality", "current").put("partial", false));
            }
        }
    }

    private ObjectNode projectFields(ProjectRow row) {
        var metadata = projects.metadata(row);
        return mapper.createObjectNode().set("name", metadata.get("name")).set("type", metadata.get("type"))
                .set("goal", metadata.get("goal")).set("currentFocus", metadata.get("current_focus"))
                .set("status", metadata.get("status")).set("stage", metadata.get("stage"))
                .set("blockedBy", metadata.get("blocked_by")).put("available", row.available());
    }

    private void addOpenTasks(Connection connection, Sources draft) throws SQLException {
        int count = 0;
        for (ObjectNode task : tasks.list(connection)) {
            if (task.hasNonNull("completedAt") || task.hasNonNull("cancellation")) {
                continue;
            }
            var memberships = TaskService.projectIds(task);
            var selected = memberships.stream().filter(draft.projects::contains).toList();
            if (memberships.isEmpty() ? !draft.general : selected.isEmpty()) {
                continue;
            }
            if (++count > 100) {
                throw new TaskError(400, "Больше 100 открытых задач. Выберите меньше проектов или исключите текущие задачи.");
            }
            var text = mapper.createObjectNode().set("code", task.get("code")).set("description", task.get("description"))
                    .set("expectedResult", task.get("expectedResult")).set("acceptance", task.get("acceptance"));
            String id = value(task, "id");
            var result = source("open-task:" + id, "open_task", selected.isEmpty() ? null : selected.getFirst());
            selected.forEach(result.putArray("projectIds")::add);
            draft.add(result.put("title", value(task, "title")).put("text", privacy.json(text)).putNull("day")
                    .put("recordedAt", value(task, "updatedAt")).put("dateQuality", "current").put("partial", false));
            draft.guard("task", id, value(task, "revision"));
        }
    }

    private void addEvents(Connection connection, ObjectNode review, Set<String> kinds, Sources draft) throws SQLException {
        var scopes = new ArrayList<>(draft.projects);
        if (draft.general) {
            scopes.add(null);
        }
        Set<String> seen = new HashSet<>();
        for (String project : scopes) {
            Set<String> selected = new HashSet<>(kinds);
            if (project == null) {
                selected.remove("file_decision");
            }
            if (selected.isEmpty()) {
                continue;
            }
            String cursor = null;
            var scopedReview = review.deepCopy().put("scopeKey", project == null ? "general" : "project:" + project);
            do {
                var page = history.forReview(connection, scopedReview, page(cursor), selected, project == null);
                if (cursor == null && selected.contains("file_decision")) {
                    var stats = page.path("files");
                    draft.undated += stats.path("undated").longValue();
                    draft.unavailable += stats.path("unavailable").longValue();
                    draft.notLoaded += stats.path("notLoadedProjects").longValue();
                }
                for (JsonNode entry : page.path("items")) {
                    seen.add(value(entry, "key"));
                    if (seen.size() > 200) {
                        throw new TaskError(400, "Больше 200 исходных событий. Выберите неделю вместо месяца, "
                                + "меньше проектов или источников; пакет не обрезается и не отправлен.");
                    }
                    addEvent(connection, draft, project, entry);
                }
                cursor = value(page, "nextCursor");
            } while (cursor != null);
        }
    }

    private void addEvent(Connection connection, Sources draft, String project, JsonNode entry) throws SQLException {
        String kind = value(entry, "kind");
        String key = value(entry, "key");
        String id = value(entry, "sourceId");
        String text = value(entry, "excerpt");
        boolean partial = on(entry, "truncated");
        if ("decision".equals(kind)) {
            var row = first(connection, "SELECT data FROM records WHERE kind='decisions' AND id=?", id);
            if (row == null) {
                throw new TaskError(410, STALE);
            }
            var decision = mapper.readTree(value(row, "data"));
            var body = mapper.createObjectNode();
            for (String name : List.of("context", "decision", "reason", "status")) {
                if (decision.has(name)) {
                    body.set(name, decision.get(name));
                }
            }
            text = privacy.json(body);
            partial = false;
        }
        draft.add(source(key, kind, kind.equals("task") ? project : value(entry, "scopeProjectId"))
                .put("title", value(entry, "title")).put("text", text).put("day", value(entry, "day"))
                .put("recordedAt", value(entry, "recordedAt")).put("dateQuality", value(entry, "dateQuality"))
                .put("partial", partial));
        switch (kind) {
            case "observation", "decision" -> draft.guard(kind, id, currentStamp(connection, kind, id));
            case "check" -> {
                String guardKind = key.startsWith("check:entry:") ? "check_entry" : "check";
                draft.guard(guardKind, id, currentStamp(connection, guardKind, id));
            }
            case "file_decision" -> draft.guard(kind, key, history.fileMetadataStamp(key));
            default -> { }
        }
    }

    private void addReview(Sources draft, JsonNode review, boolean prior) {
        String id = value(review, "id");
        draft.guard("review", id, value(review, "revision"));
        String text = value(review, "userText");
        if (TaskPayload.trim(text).isEmpty()) {
            return;
        }
        String from = value(review, "periodStart");
        String to = value(review, "periodEnd");
        String prefix = prior ? "Предыстория" : value(review, "kind");
        draft.add(source("review:" + id, "review_text", scopeProject(review))
                .put("title", prefix + ": " + from + " — " + to).put("text", text).put("day", from)
                .put("recordedAt", value(review, "updatedAt")).put("dateQuality", prior ? "background" : "current")
                .put("partial", false));
    }

    private void addBackground(Connection connection, JsonNode review, Sources draft) throws SQLException {
        String from = value(review, "periodStart");
        String start = value(review, "startAt");
        var zone = JournalPeriods.zone(value(review, "timezone"));
        for (String project : draft.projects) {
            for (var completion : rows(connection, PRIOR_COMPLETIONS, project, start, project, start)) {
                var task = mapper.readTree(value(completion, "snapshot"));
                String text = task.path("result").path("summary").asString("");
                String recorded = value(completion, "recorded_at");
                String day = Instant.parse(recorded).atZone(zone).toLocalDate().toString();
                draft.add(source("task:" + value(completion, "id"), "task", project)
                        .put("title", "Предыстория: " + value(task, "code") + " · " + value(task, "title"))
                        .put("text", excerpt(text)).put("day", day).put("recordedAt", recorded)
                        .put("dateQuality", "background").put("partial", text.length() > 2000));
            }
            String earlierReviews = """
                    SELECT id FROM reviews WHERE scope_key=? AND period_end<? AND next_start_at<=? AND trim(user_text)<>''
                    ORDER BY period_end DESC,id DESC LIMIT 3
                    """;
            for (var row : rows(connection, earlierReviews, "project:" + project, from, start)) {
                addReview(draft, reviews.read(connection, value(row, "id")), true);
            }
            var observations = """
                    SELECT id,body,occurred_on,updated_at,revision FROM observations WHERE scope_project_id=? AND occurred_on<?
                    ORDER BY occurred_on DESC,id DESC LIMIT 5
                    """;
            for (var row : rows(connection, observations, project, from)) {
                String id = value(row, "id");
                String text = value(row, "body");
                String day = value(row, "occurred_on");
                draft.add(source("observation:" + id, "observation", project).put("title", "Предыстория: наблюдение " + day)
                        .put("text", excerpt(text)).put("day", day).put("recordedAt", value(row, "updated_at"))
                        .put("dateQuality", "background").put("partial", text.length() > 2000));
                draft.guard("observation", id, value(row, "revision"));
            }
        }
    }

    private void addRelated(Connection connection, JsonNode review, ObjectNode selection, Sources draft) throws SQLException {
        String cursor = null;
        int count = 0;
        do {
            var page = reviews.related(value(review, "id"), page(cursor));
            for (var item : page.path("items")) {
                String project = scopeProject(item);
                if (project == null ? !draft.general : !draft.projects.contains(project)) {
                    continue;
                }
                if (++count > 20) {
                    throw new TaskError(400, "Больше 20 связанных обзоров. Исключите их или выберите меньше проектов.");
                }
                if (on(selection, "includeRelatedReviews")) {
                    addReview(draft, reviews.read(connection, value(item, "id")), false);
                }
                if (on(selection, "includeRelatedAnalyses")) {
                    addRelatedAnalysis(connection, project, item, draft);
                }
            }
            cursor = value(page, "nextCursor");
        } while (cursor != null);
    }

    private void addRelatedAnalysis(Connection connection, String project, JsonNode item, Sources draft) throws SQLException {
        var run = first(connection, """
                SELECT id,report_json,packet_hash,review_id,finished_at FROM analysis_runs
                WHERE review_key=? AND status='succeeded' ORDER BY generation DESC LIMIT 1
                """, value(item, "id"));
        if (run == null) {
            return;
        }
        String id = value(run, "id");
        String from = value(item, "periodStart");
        draft.add(source("analysis:" + id, "ai_analysis", project).put("title", "AI: " + from + " — " + value(item, "periodEnd"))
                .put("text", String.valueOf(value(run, "report_json"))).put("day", from)
                .put("recordedAt", String.valueOf(value(run, "finished_at"))).put("dateQuality", "model").put("partial", false));
        draft.guard("analysis", id, privacy.digest(mapper.createObjectNode().set("report_json", run.get("report_json"))
                .set("packet_hash", run.get("packet_hash")).set("review_id", run.get("review_id"))));
    }

    private void addDigests(Connection connection, JsonNode review, JsonNode selection, Sources draft) throws SQLException {
        var counts = new HashMap<String, Integer>();
        for (String id : JournalSettings.strings(selection.path("codexDigestIds"))) {
            var saved = digests.read(connection, id);
            String project = value(saved, "scopeProjectId");
            if (counts.merge(project, 1, Integer::sum) > 2) {
                throw new TaskError(400, "Выберите не более двух диалогов каждого проекта.");
            }
            if (!on(saved, "eligible") || !Objects.equals(value(saved, "reviewKey"), value(review, "id"))
                    || !draft.projects.contains(project)) {
                throw new TaskError(410, "Итог Codex недоступен для этого периода/проекта или требует повторного просмотра.");
            }
            var text = mapper.createObjectNode().set("thread", saved.get("thread")).set("sourceHash", saved.get("sourceHash"))
                    .set("readerPolicy", saved.get("policyVersion")).set("summary", saved.get("summary"))
                    .set("excerpts", saved.get("excerpts")).set("capturedAt", saved.get("capturedAt"))
                    .set("checkedAt", saved.get("checkedAt")).put("limitations", DIALOG_LIMIT);
            draft.add(source("codex_digest:" + id, "codex_digest", project)
                    .put("title", "Итог диалога: " + value(saved.path("thread"), "name"))
                    .put("text", privacy.json(text)).put("day", value(saved.path("period"), "from"))
                    .put("recordedAt", value(saved, "capturedAt")).put("dateQuality", "dialog_extract").put("partial", true));
            draft.guard("codex_digest", id, digests.stamp(saved));
        }
    }

    private ObjectNode payload(JsonNode review, JsonNode selection, Instant now, Sources draft) {
        String prepared = JournalPeriods.utc(now);
        var packet = mapper.createObjectNode().put("policyVersion", POLICY).put("preparedAt", prepared);
        packet.putObject("period").set("kind", review.get("kind")).set("from", review.get("periodStart"))
                .set("to", review.get("periodEnd")).set("timezone", review.get("timezone"))
                .set("startAt", review.get("startAt")).set("nextStartAt", review.get("nextStartAt"))
                .put("incomplete", prepared.compareTo(value(review, "nextStartAt")) < 0);
        packet.putObject("scope").set("name", review.get("projectName")).set("reviewId", review.get("id"))
                .set("key", review.get("scopeKey")).set("projectIds", selection.get("projectIds"))
                .put("includesGeneral", draft.general);
        var sources = packet.putArray("sources");
        draft.sources.values().forEach(sources::add);
        var limits = packet.putArray("limitations");
        if (on(selection, "includeProjectHistory")) {
            limits.add("Предыстория: до пяти последних завершённых задач, трёх непустых обзоров и пяти наблюдений до начала "
                    + "периода для каждого выбранного проекта. Это контекст, не события текущего периода и не полная история.");
        }
        limits.add("Прежние AI-итоги — производные предположения, не независимые факты; вложенные старые ссылки не входят в этот пакет.");
        limits.add("Источники выбраны пользователем; отсутствие записи не доказывает отсутствие работы.");
        limits.add("Сохранённые результаты — сообщения авторов, не независимая проверка.");
        limits.add("Текущие цели, открытые задачи и тексты не восстанавливают состояние на конец периода.");
        limits.add("События передаются выдержками до 600 символов с отметкой partial; файловые решения — только датированные "
                + "метаданные последнего прочитанного индекса, без нового чтения файлов.");
        limits.add("Git, пути, заметки проекта, тела файлов и приватные каталоги не включены. Полные переписки не передаются.");
        limits.add(selection.path("codexDigestIds").isEmpty() ? "Выдержки диалогов Codex не выбраны и не включены."
                : "Включены только выбранные сохранённые выдержки Codex и их краткие итоги. Намерения пользователя и заявления "
                  + "ассистента не доказывают выполнения работы; автоматическая выборка не является независимым фактом.");
        if (JournalSettings.strings(selection.path("sourceKinds")).contains("file_decision")) {
            limits.add("Файловый индекс выбранных проектов: без дат " + draft.undated + ", недоступных источников "
                    + draft.unavailable + ", без прочитанного индекса " + draft.notLoaded + ".");
        }
        if (draft.general) {
            limits.add("Общие записи включают отвязанные. Прежняя принадлежность некоторых локальных решений и текущих задач "
                    + "не восстанавливается.");
        }
        return packet;
    }

    void verify(Connection connection, JsonNode guards) throws SQLException {
        if (guards == null || !guards.isArray()) {
            throw new TaskError(410, STALE);
        }
        for (JsonNode guard : guards) {
            String kind = guard.path("kind").stringValue(null);
            String id = guard.path("id").stringValue(null);
            String expected = guard.path("stamp").stringValue(null);
            if (kind == null || id == null || expected == null || !expected.equals(currentStamp(connection, kind, id))) {
                throw new TaskError(410, STALE);
            }
        }
    }

    private String currentStamp(Connection connection, String kind, String id) throws SQLException {
        return switch (kind) {
            case "codex_digest" -> {
                try {
                    yield digests.stamp(digests.read(connection, id));
                } catch (TaskError | JacksonException missing) {
                    yield null;
                }
            }
            case "project", "project_metadata" -> {
                var row = ProjectRow.find(connection, id);
                yield row == null ? null : kind.equals("project") ? digests.projectStamp(row) : privacy.digest(projectFields(row));
            }
            case "file_decision" -> history.fileMetadataStamp(id);
            case "analysis" -> rowDigest(connection, """
                    SELECT report_json,packet_hash,review_id FROM analysis_runs WHERE id=? AND status='succeeded'
                    """, id);
            case "check" -> rowDigest(connection, "SELECT project_id,data,updated_at FROM checks WHERE id=?", id);
            case "check_entry" -> rowDigest(connection, """
                    SELECT c.project_id,e.data,e.updated_at,c.updated_at AS parent_updated_at
                    FROM check_entries e JOIN checks c ON c.id=e.check_id WHERE e.id=?
                    """, id);
            case "decision" -> rowDigest(connection, "SELECT project_id,data,updated_at FROM records WHERE kind='decisions' AND id=?", id);
            case "task" -> revision(connection, "SELECT revision FROM task_identity WHERE record_id=?", id);
            case "observation" -> revision(connection, "SELECT revision FROM observations WHERE id=?", id);
            case "review" -> revision(connection, "SELECT revision FROM reviews WHERE id=?", id);
            default -> null;
        };
    }

    private String rowDigest(Connection connection, String sql, String id) throws SQLException {
        ObjectNode row = first(connection, sql, id);
        return row == null ? null : privacy.digest(row);
    }

    private String revision(Connection connection, String sql, String id) throws SQLException {
        ObjectNode row = first(connection, sql, id);
        return row == null ? null : value(row, "revision");
    }

    private ObjectNode first(Connection connection, String sql, String... parameters) throws SQLException {
        var values = rows(connection, sql, parameters);
        return values.isEmpty() ? null : values.getFirst();
    }

    private List<ObjectNode> rows(Connection connection, String sql, String... parameters) throws SQLException {
        var output = new ArrayList<ObjectNode>();
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (var rows = statement.executeQuery()) {
                var columns = rows.getMetaData();
                while (rows.next()) {
                    var row = mapper.createObjectNode();
                    for (int index = 1; index <= columns.getColumnCount(); index++) {
                        row.put(columns.getColumnLabel(index), rows.getString(index));
                    }
                    output.add(row);
                }
            }
        }
        return output;
    }

    private ObjectNode source(String id, String kind, String project) {
        return mapper.createObjectNode().put("id", id).put("kind", kind).put("projectId", project);
    }

    private ObjectNode page(String cursor) {
        var page = mapper.createObjectNode().put("limit", 50);
        return cursor == null ? page : page.put("cursor", cursor);
    }

    private static String excerpt(String text) {
        return text.substring(0, Math.min(2000, text.length()));
    }

    private static String scopeProject(JsonNode row) {
        String scope = value(row, "scopeKey");
        return scope.equals("general") ? null : scope.substring("project:".length());
    }

    private static String value(JsonNode node, String key) {
        return node.path(key).asString(null);
    }

    private static boolean on(JsonNode node, String key) {
        return node.path(key).booleanValue(false);
    }

    private final class Sources {
        private final List<String> projects;
        private final boolean general;
        private final Map<String, ObjectNode> sources = new LinkedHashMap<>();
        private final ArrayNode guards = mapper.createArrayNode();
        private long undated;
        private long unavailable;
        private long notLoaded;

        Sources(List<String> projects, boolean general) {
            this.projects = projects;
            this.general = general;
        }

        void add(ObjectNode source) {
            var existing = sources.putIfAbsent(value(source, "id"), source);
            String project = value(source, "projectId");
            if (existing != null && project != null && !project.equals(value(existing, "projectId"))) {
                var linked = new TreeSet<>(TaskService.projectIds(existing));
                linked.add(project);
                linked.forEach(existing.putArray("projectIds")::add);
            }
        }

        void guard(String kind, String id, String stamp) {
            if (stamp == null) {
                throw new TaskError(410, STALE);
            }
            guards.addObject().put("kind", kind).put("id", id).put("stamp", stamp);
        }
    }
}
