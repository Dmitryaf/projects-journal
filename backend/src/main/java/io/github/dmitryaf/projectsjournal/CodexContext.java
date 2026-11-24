package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

@Component
final class CodexContext {

    private static final String POLICY = SavedCodexDigest.POLICY;
    private static final String ACCESS_REVOKED = "Разрешение чтения Codex отключено или проект изменился. Подтвердите доступ заново.";
    private static final String GRANT_CHANGED = "Проект или разрешение изменены. Обновите страницу.";
    private static final String ALREADY_CHANGED = "Итог изменён. Обновите страницу.";
    private static final long MAX_REVISION = 9_007_199_254_740_991L;
    private static final long TTL_SECONDS = 15 * 60;

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final JournalSettings settings;
    private final ReviewService reviews;
    private final ProjectService projects;
    private final AnalysisPrivacy privacy;
    private final CodexReaderTransport transport;
    private final SavedCodexDigest savedDigests;
    private final CodexProjectRoots projectRoots;
    private final Object monitor = new Object();
    private final Map<String, Selection> selections = new HashMap<>();
    private final Map<String, Cached> previews = new HashMap<>();
    private final Map<String, Active> active = new HashMap<>();
    private boolean stopping;

    CodexContext(SqliteStore store, JsonMapper mapper, JournalSettings settings, ReviewService reviews,
                 ProjectService projects, AnalysisPrivacy privacy, CodexReaderTransport transport) {
        this.store = store;
        this.mapper = mapper;
        this.settings = settings;
        this.reviews = reviews;
        this.projects = projects;
        this.privacy = privacy;
        this.transport = transport;
        this.savedDigests = new SavedCodexDigest(mapper, settings, privacy);
        this.projectRoots = new CodexProjectRoots(store, settings);
    }

    ObjectNode grant(String projectId) throws SQLException {
        try (var connection = store.open()) {
            return grant(connection, projectId);
        }
    }

    ObjectNode grant(Connection connection, String projectId) throws SQLException {
        requireUuid(projectId);
        return grantDto(permission(connection, projectId), projectId);
    }

    private ObjectNode grantDto(Access current, String projectId) {
        if (current.project() == null) {
            throw new TaskError(404, "Проект не найден.");
        }
        return mapper.createObjectNode().put("projectId", projectId).put("approved", current.approved())
                .put("enabled", current.enabled()).put("revision", current.revision());
    }

    ObjectNode configure(String projectId, JsonNode input) throws SQLException, IOException {
        requireUuid(projectId);
        boolean enabled = bool(input, "enabled", null);
        fields(input, enabled ? Set.of("revision", "enabled", "readConsent") : Set.of("revision", "enabled"));
        Long expected = nullableRevision(input);
        if (enabled) {
            confirm(input, "readConsent");
        }
        Access prior;
        try (var connection = store.open()) {
            prior = permission(connection, projectId);
            grantDto(prior, projectId);
        }
        String stamp = savedDigests.projectStamp(prior.project());
        List<String> roots = enabled ? projectRoots.verified(projectId) : List.of();
        long updatedRevision = store.write(connection -> {
            Access current = permission(connection, projectId);
            ProjectRow live = current.project();
            if (live == null || !Objects.equals(current.revision(), expected) || !Objects.equals(current.revision(), prior.revision())
                    || !stamp.equals(savedDigests.projectStamp(live)) || !live.path().equals(prior.project().path())) {
                throw new TaskError(409, GRANT_CHANGED);
            }
            if (enabled && (!live.available() || JournalSettings.strings(settings.read(connection).path("roots")).stream()
                    .noneMatch(root -> ProjectFiles.within(Path.of(root), Path.of(live.path()))))) {
                throw new TaskError(409, "Сначала обновите доступность проекта и его каталоги.");
            }
            long next = current.revision() == null ? 1L : current.revision() + 1L;
            change(connection, """
                    INSERT INTO codex_grants(project_id,revision,enabled,access_stamp,roots_json,updated_at,policy_version)
                    VALUES(?,?,?,?,?,?,?) ON CONFLICT(project_id) DO UPDATE SET revision=excluded.revision,
                    enabled=excluded.enabled,access_stamp=excluded.access_stamp,roots_json=excluded.roots_json,
                    updated_at=excluded.updated_at,policy_version=excluded.policy_version
                    """, projectId, next, enabled ? 1 : 0,
                    stamp, privacy.json(mapper.valueToTree(roots)), now(), POLICY);
            return next;
        });
        abort(projectId, updatedRevision);
        return grant(projectId);
    }

    private Access permission(Connection connection, String id) throws SQLException {
        ProjectRow row = ProjectRow.find(connection, id);
        try (var statement = connection.prepareStatement("SELECT * FROM codex_grants WHERE project_id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return new Access(row, null, false, false, null, null);
                }
                boolean approved = rows.getBoolean("enabled");
                String stamp = rows.getString("access_stamp");
                String roots = rows.getString("roots_json");
                return new Access(row, rows.getLong("revision"), approved, savedDigests.permitted(connection, row, approved,
                        rows.getString("policy_version"), stamp, roots), stamp, roots);
            }
        }
    }

    private Access access(String projectId, long revision) throws SQLException {
        try (var connection = store.open()) {
            return access(connection, projectId, revision);
        }
    }

    private Access access(Connection connection, String projectId, long revision) throws SQLException {
        Access live = permission(connection, projectId);
        if (!live.enabled() || live.revision() == null || live.revision() != revision) {
            throw new TaskError(410, ACCESS_REVOKED);
        }
        return live;
    }

    ObjectNode probe(String projectId, long revision) throws SQLException {
        requireUuid(projectId);
        access(projectId, revision);
        return transport.probe();
    }

    ObjectNode threads(String projectId, JsonNode input) throws SQLException, IOException {
        requireUuid(projectId);
        fields(input, Set.of("revision", "cursor", "archived"));
        long revision = revision(input, "revision");
        String cursor = input.has("cursor") ? ApiJson.text(input.get("cursor"), "cursor", 0, 4096, false) : null;
        boolean archived = bool(input, "archived", false);
        return operation(projectId, revision, (rpc, roots, ignored) -> {
            var params = mapper.createObjectNode().set("cwd", mapper.valueToTree(roots)).put("limit", 10).put("cursor", cursor)
                    .put("archived", archived).put("useStateDbOnly", true).put("sortKey", "updated_at").put("sortDirection", "desc");
            var page = CodexExtract.page(rpc.request("thread/list", params), 10);
            var threadRows = new ArrayList<CodexExtract.ThreadRow>();
            for (JsonNode item : page.data()) {
                threadRows.add(CodexExtract.thread(item));
            }
            if (threadRows.stream().anyMatch(row -> !belongs(roots, row.cwd()))) {
                throw new TaskError(403, "Codex вернул диалог вне выбранного проекта; список отклонён.");
            }
            var result = mapper.createObjectNode();
            var items = result.putArray("items");
            for (var thread : threadRows) {
                remember(projectId, thread.id(), revision);
                items.add(thread.dto(mapper));
            }
            return result.put("nextCursor", page.cursor());
        });
    }

    ObjectNode preview(String projectId, JsonNode input) throws SQLException, IOException {
        requireUuid(projectId);
        fields(input, Set.of("revision", "reviewId", "threadId"));
        long revision = revision(input, "revision");
        String reviewId = uuid(input, "reviewId");
        String thread = ApiJson.text(input.get("threadId"), "threadId", 1, 100, false);
        var review = reviews.read(reviewId);
        if (!"general".equals(value(review, "scopeKey")) && !("project:" + projectId).equals(value(review, "scopeKey"))) {
            throw new TaskError(403, "Для обзора другого проекта этот диалог недоступен.");
        }
        access(projectId, revision);
        synchronized (monitor) {
            Selection selected = selections.get(selectionKey(projectId, thread));
            if (selected == null || selected.revision() != revision || !selected.expires().isAfter(Instant.now())) {
                throw new TaskError(410, "Сначала откройте список диалогов выбранного проекта и выберите диалог заново.");
            }
        }
        return operation(projectId, revision, (rpc, roots, approved) ->
                buildPreview(rpc, roots, projectId, thread, review, approved));
    }

    private ObjectNode buildPreview(CodexExtract.Reader rpc, List<String> roots, String projectId, String threadId,
                                    ObjectNode review, Access approved) {
        var thread = thread(rpc, threadId, roots);
        var captured = CodexExtract.read(mapper, privacy, rpc, thread, review);
        if (thread(rpc, thread.id(), roots).updatedAt() != thread.updatedAt()) {
            throw new TaskError(409, "Диалог изменился во время чтения. Повторите просмотр.");
        }
        if (captured.excerpts().isEmpty()) {
            throw new TaskError(400, "В ограниченной выборке не найден текст за этот период. Проверяются до 50 последних обращений; "
                    + "записи без даты и команды исключаются.");
        }
        if (privacy.bytes(captured.excerpts()) > 64_000) {
            throw new TaskError(400, "Текст превышает лимит просмотра. Выберите другой диалог.");
        }
        String id = UUID.randomUUID().toString();
        Instant expires = Instant.now().plusSeconds(TTL_SECONDS);
        var dto = mapper.createObjectNode().put("id", id).put("projectId", projectId).put("reviewId", value(review, "id"))
                .set("thread", thread.dto(mapper)).put("expiresAt", JournalPeriods.utc(expires))
                .put("sourceHash", captured.sourceHash()).set("excerpts", captured.excerpts());
        dto.putArray("limitations")
                .add("Отбор по времени начала обращения. Отдельные ответы могли появиться позже; "
                        + "Показанная дата — начало обращения, не точное время отдельного сообщения.")
                .add("Только текст user/assistant. Предложения и сообщения о выполнении не являются подтверждением работы.")
                .add("Проверены до 50 последних turns, не более 8 turns с текстом и 40 сообщений; "
                        + "выдержка показана до 1000 байт с отметкой сокращения.")
                .add("Сокращённая выборка: " + (captured.partial() ? "да" : "нет") + ". Turns без даты: " + captured.undated()
                        + ". Исключённые нетекстовые элементы: " + captured.omitted() + ".")
                .add("Команды, tool output, вложения и reasoning не сохраняются. Ассистентский текст всё равно может содержать "
                        + "пересказ команд или личные сведения; проверьте его вручную.");
        var suggestion = CodexExtract.select(dto);
        if (!suggestion.isEmpty()) {
            var ids = dto.putObject("suggested").put("summary", CodexExtract.SUMMARY).putArray("excerptIds");
            suggestion.forEach(item -> ids.add(value(item, "id")));
        }
        synchronized (monitor) {
            previews.values().removeIf(p -> !p.expires().isAfter(Instant.now()));
            if (previews.size() >= 20) {
                throw new TaskError(429, "Слишком много открытых просмотров. Дождитесь их истечения.");
            }
            ensureRunning();
            previews.put(id, new Cached(dto, expires, approved.revision(), approved.stamp(), mapper.createObjectNode()
                    .put("from", value(review, "periodStart")).put("to", value(review, "periodEnd"))
                    .put("timezone", value(review, "timezone"))));
        }
        return dto.deepCopy();
    }

    private CodexExtract.ThreadRow thread(CodexExtract.Reader rpc, String id, List<String> roots) {
        var metadata = CodexExtract.thread(rpc.request("thread/read",
                mapper.createObjectNode().put("threadId", id).put("includeTurns", false)).path("thread"));
        if (!metadata.id().equals(id) || !belongs(roots, metadata.cwd())) {
            throw new TaskError(403, "Диалог не принадлежит разрешённому каталогу проекта.");
        }
        return metadata;
    }

    ObjectNode prepare(JsonNode input) throws SQLException, IOException {
        fields(input, Set.of("reviewId", "projects", "readConsent", "saveConsent"));
        confirm(input, "readConsent");
        confirm(input, "saveConsent");
        String reviewId = uuid(input, "reviewId");
        ObjectNode review = reviews.read(reviewId);
        var requested = input.path("projects");
        if (!requested.isArray() || requested.isEmpty() || requested.size() > 10) {
            throw ApiJson.invalid("projects");
        }
        var grants = new ArrayList<ProjectGrant>();
        var seen = new HashSet<String>();
        for (JsonNode item : requested) {
            fields(item, Set.of("projectId", "revision"));
            String id = uuid(item, "projectId");
            if (!seen.add(id)) {
                throw new TaskError(400, "Проект выбран несколько раз.");
            }
            grants.add(new ProjectGrant(id, revision(item, "revision")));
        }
        // Validate the whole explicit scope before touching any dialogue.
        for (var grant : grants) {
            if (!"general".equals(value(review, "scopeKey"))
                    && !("project:" + grant.id()).equals(value(review, "scopeKey"))) {
                throw new TaskError(403, "Выберите только проект этого обзора.");
            }
            access(grant.id(), grant.revision());
        }
        var result = mapper.createObjectNode();
        var items = result.putArray("items");
        var limits = result.putArray("limitations");
        for (ProjectGrant grant : grants) {
            try {
                prepareProject(grant, review, items, limits);
            } catch (TaskError | IOException | SQLException unavailable) {
                if (unavailable instanceof TaskError refusal
                        && (refusal.status() == 403 || refusal.status() == 409 || refusal.status() == 410)) {
                    throw refusal;
                }
                limits.add("Контекст Codex проекта " + grant.id() + " недоступен. Основные источники можно использовать без него.");
            }
        }
        return result;
    }

    private void prepareProject(ProjectGrant grant, ObjectNode review, ArrayNode items, ArrayNode limits)
            throws SQLException, IOException {
        String project = grant.id();
        long revision = grant.revision();
        String reviewId = value(review, "id");
        var page = threads(project, mapper.createObjectNode().put("revision", revision));
        var startSeconds = Instant.parse(value(review, "startAt")).toEpochMilli() / 1000.0;
        int considered = 0;
        int selected = 0;
        for (JsonNode thread : page.path("items")) {
            if (thread.path("updatedAt").longValue() < startSeconds) {
                continue;
            }
            if (++considered > 4 || selected == 2) {
                break;
            }
            String threadId = value(thread, "id");
            var previous = priorDigest(reviewId, project, threadId);
            ObjectNode fresh;
            try {
                fresh = preview(project, previewParams(revision, reviewId, threadId));
            } catch (TaskError failure) {
                if (failure.status() == 503) {
                    access(project, revision);
                    if (previous != null) {
                        markStale(previous);
                    }
                    limits.add("Проект " + project + ": один диалог пропущен, потому что его не удалось прочитать. "
                            + "Остальные кандидаты проверены в прежнем лимите.");
                    continue;
                }
                if (failure.status() == 400) {
                    if (previous != null) {
                        markStale(previous);
                        limits.add("Проект " + project + ": ранее сохранённый диалог больше не содержит доступной выборки. "
                                + "Итог исключён до пересмотра.");
                    }
                    continue;
                }
                throw failure;
            }
            try {
                if (previous != null) {
                    if (previous.path("eligible").booleanValue() && sameSource(previous, fresh)) {
                        var current = read(value(previous, "id"));
                        access(project, revision);
                        if (!sameRevision(previous, current)) {
                            throw new TaskError(409, "Итог изменён во время подготовки. Повторите просмотр.");
                        }
                        items.add(current);
                        selected++;
                    } else {
                        markStale(previous);
                        limits.add("Проект " + project + ": диалог «" + value(previous.path("thread"), "name")
                                + "» требует пересмотра. Прежний итог и выдержки сохранены, но не включены в анализ.");
                    }
                    continue;
                }
                var extraction = CodexExtract.select(fresh);
                if (extraction.isEmpty()) {
                    continue;
                }
                if (savedCount(reviewId, project) >= 4) {
                    limits.add("Проект " + project + ": сохранены четыре итога для этого обзора. Чтобы добавить другой диалог, "
                            + "пересмотрите или явно удалите ненужные итоги. Сохранённый текст не изменён.");
                    continue;
                }
                String previewId = value(fresh, "id");
                applyAutoExcerptLimits(previewId, extraction);
                var data = mapper.createObjectNode().put("previewId", previewId).putNull("revision")
                        .put("summary", CodexExtract.SUMMARY).put("confirm", true);
                var ids = data.putArray("excerptIds");
                extraction.forEach(item -> ids.add(value(item, "id")));
                items.add(save(project, data));
                selected++;
            } finally {
                discardPreview(value(fresh, "id"));
            }
        }
        limits.add("Проект " + project + ": выбрано " + selected
                + " диалогов; проверены до четырёх недавних диалогов из первой страницы. Это ограниченный отбор, не полный архив.");
    }

    private void applyAutoExcerptLimits(String id, List<ObjectNode> extraction) {
        synchronized (monitor) {
            var cached = previews.get(id);
            if (cached == null) {
                throw new TaskError(410, "Просмотр истёк. Прочитайте диалог заново.");
            }
            var dto = cached.dto().deepCopy();
            var selected = new HashMap<String, ObjectNode>();
            extraction.forEach(excerpt -> selected.put(value(excerpt, "id"), excerpt));
            var limited = mapper.createArrayNode();
            dto.path("excerpts").forEach(item -> limited.add(selected.getOrDefault(value(item, "id"), item.asObject())));
            dto.set("excerpts", limited);
            previews.put(id, new Cached(dto, cached.expires(), cached.revision(), cached.stamp(), cached.period()));
        }
    }

    ObjectNode save(String projectId, JsonNode input) throws SQLException, IOException {
        requireUuid(projectId);
        fields(input, Set.of("previewId", "revision", "summary", "excerptIds", "confirm"));
        confirm(input, "confirm");
        String previewId = uuid(input, "previewId");
        Long revision = nullableRevision(input);
        String summary = ApiJson.text(input.get("summary"), "summary", 1, 6000, true);
        var ids = excerptIds(input);
        var request = mapper.createObjectNode().put("summary", summary);
        ids.stream().distinct().sorted().forEach(request.putArray("excerptIds")::add);
        String hash = privacy.digest(request);
        try (var connection = store.open()) {
            var previous = replay(connection, projectId, previewId, hash);
            if (previous != null) {
                return previous;
            }
        }
        Cached cached;
        synchronized (monitor) {
            cached = previews.get(previewId);
        }
        if (cached == null || !projectId.equals(value(cached.dto(), "projectId"))
                || !cached.expires().isAfter(Instant.now())) {
            throw new TaskError(410, "Просмотр истёк. Прочитайте диалог заново.");
        }
        var excerpts = selectedExcerpts(cached.dto(), ids);
        String threadId = value(cached.dto().path("thread"), "id");
        String reviewId = value(cached.dto(), "reviewId");
        remember(projectId, threadId, cached.revision());
        var fresh = preview(projectId, previewParams(cached.revision(), reviewId, threadId));
        discardPreview(value(fresh, "id"));
        if (!sameSource(fresh, cached.dto())) {
            throw new TaskError(409, "Диалог изменился после просмотра. Подготовьте новый итог.");
        }
        return store.write(connection -> {
            var repeated = replay(connection, projectId, previewId, hash);
            if (repeated != null) {
                return repeated;
            }
            var access = access(connection, projectId, cached.revision());
            reviews.read(connection, reviewId);
            var old = first(connection, "SELECT id,revision,scope_project_id FROM codex_digests WHERE review_key=? AND thread_id=?",
                    reviewId, threadId);
            if (old != null && !projectId.equals(value(old, "scope_project_id"))) {
                throw new TaskError(409, "Диалог раньше был сохранён для другого проекта. Удалите прежний итог перед переносом.");
            }
            if (!Objects.equals(old == null ? null : old.path("revision").asLong(), revision)) {
                throw new TaskError(409, "Итог уже изменён. Обновите страницу.");
            }
            var saved = savedForProject(connection, reviewId, projectId);
            String oldId = old == null ? null : value(old, "id");
            long eligible = saved.stream().filter(item -> !Objects.equals(oldId, value(item, "id"))
                    && item.path("eligible").booleanValue()).count();
            if (eligible >= 2) {
                throw new TaskError(400, "Для одного обзора доступны максимум два актуальных диалога каждого проекта. "
                        + "Удалите ненужный итог перед добавлением или пересмотром другого.");
            }
            if (old == null && saved.size() >= 4) {
                throw new TaskError(400, "Для одного обзора сохраняются максимум четыре итога каждого проекта, включая устаревшие. "
                        + "Пересмотрите или явно удалите ненужные итоги.");
            }
            String id = oldId == null ? UUID.randomUUID().toString() : oldId;
            saveDigest(connection, id, projectId, access.project(), reviewId, threadId, fresh, cached, summary, excerpts,
                    revision == null ? 1L : revision + 1, previewId, hash);
            return savedDigests.read(connection, id);
        });
    }

    private void saveDigest(Connection connection, String id, String projectId, ProjectRow project, String reviewId,
                            String threadId, JsonNode fresh, Cached cached, String summary, ArrayNode excerpts,
                            long newRevision, String previewId, String hash) throws SQLException {
        String time = now();
        change(connection, """
                INSERT INTO codex_digests(id,project_id,scope_project_id,project_name,review_key,period_json,thread_id,
                  thread_name,thread_updated_at,summary,excerpts_json,source_hash,access_stamp,grant_revision,captured_at,
                  checked_at,revision,preview_id,request_hash,policy_version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET summary=excluded.summary,excerpts_json=excluded.excerpts_json,
                  source_hash=excluded.source_hash,access_stamp=excluded.access_stamp,grant_revision=excluded.grant_revision,
                  thread_name=excluded.thread_name,thread_updated_at=excluded.thread_updated_at,
                  captured_at=excluded.captured_at,checked_at=excluded.checked_at,stale=0,revision=excluded.revision,
                  preview_id=excluded.preview_id,request_hash=excluded.request_hash,policy_version=excluded.policy_version
                """, id, projectId, projectId, value(projects.metadata(project), "name"), reviewId, privacy.json(cached.period()),
                threadId, value(fresh.path("thread"), "name"), fresh.path("thread").path("updatedAt").longValue(), summary,
                privacy.json(excerpts), value(cached.dto(), "sourceHash"), cached.stamp(), cached.revision(), time, time,
                newRevision, previewId, hash, POLICY);
    }

    private ObjectNode replay(Connection connection, String project, String previewId, String hash) throws SQLException {
        var previous = first(connection, "SELECT id,request_hash FROM codex_digests WHERE preview_id=?", previewId);
        if (previous == null) {
            return null;
        }
        if (!hash.equals(value(previous, "request_hash"))) {
            throw new TaskError(409, "Этот просмотр уже сохранён с другой редакцией. Откройте сохранённый итог.");
        }
        var saved = savedDigests.read(connection, value(previous, "id"));
        if (!project.equals(value(saved, "scopeProjectId"))) {
            throw new TaskError(403, "Другой проект.");
        }
        return saved;
    }

    private ArrayNode selectedExcerpts(ObjectNode cached, List<String> requested) {
        var byId = new HashMap<String, JsonNode>();
        cached.path("excerpts").forEach(item -> byId.put(value(item, "id"), item));
        if (requested.stream().distinct().count() != requested.size() || !byId.keySet().containsAll(requested)) {
            throw new TaskError(400, "Выдержка не входит в просмотренный пакет.");
        }
        var selection = mapper.createArrayNode();
        requested.forEach(id -> selection.add(byId.get(id)));
        return selection;
    }

    ObjectNode read(String id) throws SQLException {
        requireUuid(id);
        try (var connection = store.open()) {
            return savedDigests.read(connection, id);
        }
    }

    ObjectNode edit(String id, JsonNode input) throws SQLException {
        requireUuid(id);
        fields(input, Set.of("revision", "summary", "confirm"));
        confirm(input, "confirm");
        long revision = revision(input, "revision");
        String summary = ApiJson.text(input.get("summary"), "summary", 1, 6000, true);
        return store.write(connection -> {
            var prior = savedDigests.read(connection, id);
            if (prior.path("revision").longValue() != revision || change(connection, """
                    UPDATE codex_digests SET summary=?,revision=revision+1 WHERE id=? AND revision=?
                    """, summary, id, revision) == 0) {
                throw new TaskError(409, ALREADY_CHANGED);
            }
            return savedDigests.read(connection, id);
        });
    }

    ObjectNode list(JsonNode input) throws SQLException {
        fields(input, Set.of("reviewId", "projectId", "cursor"));
        var conditions = new ArrayList<String>();
        var values = new ArrayList<String>();
        for (var field : List.of("reviewId", "projectId", "cursor")) {
            if (input.has(field)) {
                conditions.add(switch (field) {
                    case "reviewId" -> "review_key=?";
                    case "projectId" -> "scope_project_id=?";
                    default -> "id>?";
                });
                values.add(uuid(input, field));
            }
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        try (var connection = store.open()) {
            var rows = rows(connection, "SELECT id FROM codex_digests" + where + " ORDER BY id LIMIT 21", values.toArray());
            var dto = mapper.createObjectNode();
            var items = dto.putArray("items");
            for (ObjectNode row : rows.subList(0, Math.min(20, rows.size()))) {
                items.add(savedDigests.read(connection, value(row, "id")));
            }
            return dto.put("nextCursor", rows.size() > 20 ? value(rows.get(19), "id") : null);
        }
    }

    ObjectNode check(String id, JsonNode input) throws SQLException, IOException {
        fields(input, Set.of("revision", "grantRevision"));
        long expected = revision(input, "revision");
        long revision = revision(input, "grantRevision");
        var saved = read(id);
        String project = value(saved, "projectId");
        if (project == null) {
            throw new TaskError(410, "Проект удалён. Сохранённые выдержки остаются доступны.");
        }
        if (saved.path("revision").longValue() != expected) {
            throw new TaskError(409, ALREADY_CHANGED);
        }
        try {
            access(project, revision);
            String thread = value(saved.path("thread"), "id");
            remember(project, thread, revision);
            var fresh = preview(project, previewParams(revision, value(saved, "reviewKey"), thread));
            discardPreview(value(fresh, "id"));
            if (saved.path("stale").booleanValue() || !sameSource(saved, fresh)) {
                markStale(saved);
                return read(id);
            }
            return store.write(connection -> {
                access(connection, project, revision);
                if (change(connection, """
                        UPDATE codex_digests SET stale=0,checked_at=?,revision=revision+1 WHERE id=? AND revision=?
                        """, now(), id, expected) == 0) {
                    throw new TaskError(409, "Итог изменён во время проверки. Обновите страницу.");
                }
                return savedDigests.read(connection, id);
            });
        } catch (TaskError | SQLException | IOException error) {
            try {
                store.write(connection -> change(connection, """
                        UPDATE codex_digests SET stale=1,revision=revision+1 WHERE id=? AND revision=? AND stale=0
                        """, id, expected));
            } catch (SQLException staleFailure) {
                error.addSuppressed(staleFailure);
            }
            throw error;
        }
    }

    void delete(String id, JsonNode input) throws SQLException {
        requireUuid(id);
        fields(input, Set.of("revision", "confirm"));
        confirm(input, "confirm");
        long revision = revision(input, "revision");
        store.write(connection -> {
            var row = savedDigests.read(connection, id);
            if (row.path("revision").longValue() != revision) {
                throw new TaskError(409, "Итог изменён.");
            }
            if (change(connection, "DELETE FROM codex_digests WHERE id=? AND revision=?", id, revision) == 0) {
                throw new TaskError(409, ALREADY_CHANGED);
            }
            return null;
        });
    }

    private void markStale(ObjectNode previous) throws SQLException {
        String id = value(previous, "id");
        long revision = previous.path("revision").longValue();
        store.write(connection -> {
            if (change(connection, """
                    UPDATE codex_digests SET stale=1,checked_at=?,revision=revision+1 WHERE id=? AND revision=? AND stale=0
                    """, now(), id, revision) == 0) {
                var current = first(connection, "SELECT revision,stale FROM codex_digests WHERE id=?", id);
                if (current == null || !sameRevision(previous, current) || !"1".equals(value(current, "stale"))) {
                    throw new TaskError(409, "Итог изменён во время подготовки. Повторите просмотр.");
                }
            }
            return null;
        });
    }

    private ObjectNode priorDigest(String review, String project, String thread) throws SQLException {
        try (var connection = store.open()) {
            var previous = first(connection, "SELECT id FROM codex_digests WHERE review_key=? AND scope_project_id=? AND thread_id=?",
                    review, project, thread);
            return previous == null ? null : savedDigests.read(connection, value(previous, "id"));
        }
    }

    private List<ObjectNode> savedForProject(Connection connection, String review, String project) throws SQLException {
        var output = new ArrayList<ObjectNode>();
        for (var row : rows(connection, "SELECT id FROM codex_digests WHERE review_key=? AND scope_project_id=?", review, project)) {
            output.add(savedDigests.read(connection, value(row, "id")));
        }
        return output;
    }

    private long savedCount(String review, String project) throws SQLException {
        try (var connection = store.open()) {
            return first(connection, "SELECT count(*) AS count FROM codex_digests WHERE review_key=? AND scope_project_id=?",
                    review, project).path("count").asLong();
        }
    }

    private ObjectNode previewParams(long revision, String review, String thread) {
        return mapper.createObjectNode().put("revision", revision).put("reviewId", review).put("threadId", thread);
    }

    private void discardPreview(String id) {
        synchronized (monitor) {
            previews.remove(id);
        }
    }

    private static boolean sameSource(JsonNode first, JsonNode second) {
        return Objects.equals(value(first, "sourceHash"), value(second, "sourceHash"))
                && first.path("thread").path("updatedAt").longValue() == second.path("thread").path("updatedAt").longValue();
    }

    private static boolean sameRevision(JsonNode previous, JsonNode current) {
        return previous.path("revision").asLong() == current.path("revision").asLong();
    }

    private static List<String> excerptIds(JsonNode input) {
        var values = input.path("excerptIds");
        if (!values.isArray() || values.size() > 8) {
            throw ApiJson.invalid("excerptIds");
        }
        var result = new ArrayList<String>();
        for (int index = 0; index < values.size(); index++) {
            result.add(ApiJson.text(values.get(index), "excerptIds." + index, 1, 220, false));
        }
        return result;
    }

    private ObjectNode first(Connection connection, String sql, Object... parameters) throws SQLException {
        var rows = rows(connection, sql, parameters);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private List<ObjectNode> rows(Connection connection, String sql, Object... parameters) throws SQLException {
        var result = new ArrayList<ObjectNode>();
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (var rows = statement.executeQuery()) {
                var columns = rows.getMetaData();
                while (rows.next()) {
                    var row = mapper.createObjectNode();
                    for (int index = 1; index <= columns.getColumnCount(); index++) {
                        row.put(columns.getColumnLabel(index), rows.getString(index));
                    }
                    result.add(row);
                }
            }
        }
        return result;
    }

    private static int change(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static void fields(JsonNode input, Set<String> allowed) {
        ApiJson.requireObject(input);
        if (!allowed.containsAll(input.propertyNames())) {
            throw ApiJson.invalid("");
        }
    }

    private static boolean bool(JsonNode input, String field, Boolean fallback) {
        JsonNode value = input == null ? null : input.get(field);
        if (value == null && fallback != null) {
            return fallback;
        }
        if (value == null || !value.isBoolean()) {
            throw ApiJson.invalid(field);
        }
        return value.booleanValue();
    }

    private static void confirm(JsonNode input, String field) {
        if (!bool(input, field, null)) {
            throw ApiJson.invalid(field);
        }
    }

    private static long revision(JsonNode node, String field) {
        return ApiJson.integer(node.get(field), field, 1, MAX_REVISION);
    }

    private static Long nullableRevision(JsonNode input) {
        return input.has("revision") && input.get("revision").isNull() ? null : revision(input, "revision");
    }

    private static String uuid(JsonNode input, String field) {
        return TaskPayload.uuid(input.get(field), field, false);
    }

    private static void requireUuid(String value) {
        TaskPayload.uuid(StringNode.valueOf(value), "", false);
    }

    private static String value(JsonNode node, String field) {
        return node.path(field).asString(null);
    }

    private static String now() {
        return JournalPeriods.utc(Instant.now());
    }

    private <T> T operation(String projectId, long revision, ReadAction<T> action) throws SQLException, IOException {
        var cancellation = new CodexCancellation();
        synchronized (monitor) {
            ensureRunning();
            if (active.containsKey(projectId) || active.size() >= 2) {
                throw new TaskError(429, "Дождитесь завершения предыдущего чтения Codex.");
            }
            active.put(projectId, new Active(revision, cancellation));
        }
        try (cancellation) {
            var authorized = access(projectId, revision);
            var roots = projectRoots.verified(projectId);
            var live = access(projectId, revision);
            if (!Objects.equals(authorized.rootsJson(), live.rootsJson())
                    || !privacy.digest(mapper.valueToTree(roots)).equals(privacy.digest(mapper.readTree(live.rootsJson())))) {
                throw new TaskError(410, "Набор каталогов проекта изменился. Подтвердите разрешение заново.");
            }
            T result = transport.read(cancellation, rpc -> action.read((method, parameters) -> {
                guardedAccess(projectId, revision);
                cancellation.check();
                JsonNode response = rpc.request(method, parameters);
                guardedAccess(projectId, revision);
                cancellation.check();
                return response;
            }, roots, live));
            access(projectId, revision);
            cancellation.check();
            return result;
        } catch (ReadDatabase error) {
            throw error.sql();
        } finally {
            synchronized (monitor) {
                active.remove(projectId);
                monitor.notifyAll();
            }
        }
    }

    private void guardedAccess(String projectId, long revision) {
        try {
            access(projectId, revision);
        } catch (SQLException error) {
            throw new ReadDatabase(error);
        }
    }

    private void remember(String projectId, String threadId, long revision) {
        synchronized (monitor) {
            ensureRunning();
            Instant now = Instant.now();
            selections.values().removeIf(selection -> !selection.expires().isAfter(now));
            String key = selectionKey(projectId, threadId);
            if (!selections.containsKey(key) && selections.size() >= 200) {
                throw new TaskError(429, "Слишком много открытых списков диалогов. Дождитесь истечения просмотров.");
            }
            selections.put(key, new Selection(revision, now.plusSeconds(TTL_SECONDS)));
        }
    }

    private static String selectionKey(String projectId, String threadId) {
        return projectId + ":" + threadId;
    }

    private static boolean belongs(List<String> roots, String cwd) {
        return roots.stream().anyMatch(root -> CodexProjectRoots.same(root, cwd));
    }

    private void abort(String projectId, long updatedRevision) {
        Active operation;
        synchronized (monitor) {
            selections.entrySet().removeIf(entry -> entry.getKey().startsWith(projectId + ":")
                    && entry.getValue().revision() < updatedRevision);
            previews.values().removeIf(preview -> projectId.equals(value(preview.dto(), "projectId"))
                    && preview.revision() < updatedRevision);
            operation = active.get(projectId);
        }
        if (operation != null && operation.revision() < updatedRevision) {
            operation.cancellation().cancel();
        }
    }

    @PreDestroy
    void stop() {
        List<CodexCancellation> operations;
        synchronized (monitor) {
            stopping = true;
            operations = active.values().stream().map(Active::cancellation).toList();
            previews.clear();
            selections.clear();
        }
        operations.forEach(CodexCancellation::cancel);
        synchronized (monitor) {
            while (!active.isEmpty()) {
                try {
                    monitor.wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void ensureRunning() {
        if (stopping) {
            throw new TaskError(503, "Чтение Codex остановлено.");
        }
    }

    private record Access(ProjectRow project, Long revision, boolean approved, boolean enabled, String stamp, String rootsJson) {
    }

    private record Selection(long revision, Instant expires) {
    }

    private record Cached(ObjectNode dto, Instant expires, long revision, String stamp, ObjectNode period) {
    }

    private record ProjectGrant(String id, long revision) {
    }

    private record Active(long revision, CodexCancellation cancellation) {
    }

    @FunctionalInterface
    private interface ReadAction<T> {
        T read(CodexExtract.Reader rpc, List<String> roots, Access authorized);
    }

    private static final class ReadDatabase extends RuntimeException {
        ReadDatabase(SQLException cause) {
            super(cause);
        }

        SQLException sql() {
            return (SQLException) getCause();
        }
    }
}
