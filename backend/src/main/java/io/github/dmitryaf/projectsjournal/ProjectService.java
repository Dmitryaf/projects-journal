package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class ProjectService {

    private static final DateTimeFormatter INSTANT = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final long DAY_MILLIS = 86_400_000;
    private static final Pattern LONE_SURROGATE = Pattern.compile("[\\uD800-\\uDFFF]");
    private static final String USED_TARGET = """
            SELECT EXISTS(SELECT 1 FROM records WHERE (kind!='tasks' AND project_id=?1)
              OR (kind='tasks' AND EXISTS(SELECT 1 FROM json_each(%s) WHERE value=?1)))
            OR EXISTS(SELECT 1 FROM checks WHERE project_id=?1)
            OR EXISTS(SELECT 1 FROM relations WHERE sourceId=?1 OR targetId=?1)
            OR EXISTS(SELECT 1 FROM task_history WHERE EXISTS(SELECT 1 FROM json_each(%s) WHERE value=?1))
            OR EXISTS(SELECT 1 FROM observations WHERE project_id=?1)
            OR EXISTS(SELECT 1 FROM reviews WHERE project_id=?1)
            OR EXISTS(SELECT 1 FROM analysis_runs WHERE scope_key='project:'||?1
                OR EXISTS(SELECT 1 FROM json_each(json_extract(selection_json,'$.projectIds')) WHERE value=?1))
            OR EXISTS(SELECT 1 FROM analysis_schedules WHERE scope_key='project:'||?1
                OR EXISTS(SELECT 1 FROM json_each(json_extract(selection_json,'$.projectIds')) WHERE value=?1))
            OR EXISTS(SELECT 1 FROM analysis_acceptances WHERE json_extract(approved_json,'$.projectId')=?1
                OR EXISTS(SELECT 1 FROM json_each(json_extract(original_json,'$.proposal.projectIds')) WHERE value=?1))
            OR EXISTS(SELECT 1 FROM codex_digests WHERE scope_project_id=?1)
            OR EXISTS(SELECT 1 FROM codex_grants WHERE project_id=?1)
            OR EXISTS(SELECT 1 FROM project_lifecycle_events WHERE scope_project_id=?1)
            """.formatted(TaskService.projectIdsSql("data"), TaskService.projectIdsSql("snapshot"));

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final ObjectWriter writer;
    private final JournalSettings settings;
    private final ProjectFiles files;
    private final ProjectContextService contexts;
    private final TaskService tasks;
    private final ReentrantReadWriteLock inspectionLock = new ReentrantReadWriteLock(true);
    private final Object scanMonitor = new Object();
    private final ConcurrentHashMap<String, CompletableFuture<ObjectNode>> refreshing = new ConcurrentHashMap<>();
    private CompletableFuture<ObjectNode> pendingScan;
    private volatile ScanState scanState = new ScanState(null, List.of(), false);

    ProjectService(SqliteStore store, JsonMapper mapper, JournalSettings settings, ProjectFiles files,
                   ProjectContextService contexts, TaskService tasks) {
        this.store = store;
        this.mapper = mapper;
        this.writer = mapper.writer().without(JsonWriteFeature.WRITE_HEX_UPPER_CASE);
        this.settings = settings;
        this.files = files;
        this.contexts = contexts;
        this.tasks = tasks;
    }

    ObjectNode scanInfo() {
        ScanState state = scanState;
        ObjectNode result = mapper.createObjectNode().put("scannedAt", state.scannedAt()).put("running", state.running());
        ArrayNode errors = result.putArray("errors");
        state.errors().forEach(errors::add);
        return result;
    }

    ObjectNode scan() throws SQLException {
        CompletableFuture<ObjectNode> started = new CompletableFuture<>();
        CompletableFuture<ObjectNode> existing;
        synchronized (scanMonitor) {
            existing = pendingScan;
            if (existing == null) {
                pendingScan = started;
                scanState = scanState.running(true);
            }
        }
        if (existing != null) {
            return await(existing);
        }
        Lock write = inspectionLock.writeLock();
        write.lock();
        try {
            performScan();
            return finishScan(started, null);
        } catch (SQLException | RuntimeException | Error error) {
            finishScan(started, error);
            throw error;
        } finally {
            write.unlock();
        }
    }

    private void performScan() throws SQLException {
        ObjectNode configuration = settings.read();
        var discovery = files.discover(configuration);
        List<String> errors = new ArrayList<>(discovery.errors());
        List<ObjectNode> snapshots = new ArrayList<>();
        List<String> roots = JournalSettings.strings(configuration.path("roots"));
        for (String directory : discovery.paths()) {
            try {
                ProjectFiles.assertProjectAccess(Path.of(directory), roots);
                snapshots.add(files.inspect(directory));
            } catch (IOException | TaskError ignored) {
                errors.add("Не удалось обновить проект: " + directory);
            }
        }
        store.write(connection -> {
            ObjectNode current = settings.read(connection);
            for (String field : List.of("roots", "exclusions", "excludedProjectPaths")) {
                if (!configuration.path(field).equals(current.path(field))) {
                    throw new TaskError(409, "Настройки каталогов изменились. Повторите сканирование.");
                }
            }
            Set<String> found = new HashSet<>();
            snapshots.forEach(snapshot -> found.add(snapshot.path("path").asString()));
            for (ProjectRow row : ProjectRow.list(connection)) {
                if (!found.contains(row.path())) {
                    unavailable(connection, row);
                }
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO projects(id,path,snapshot) VALUES(?,?,?) ON CONFLICT(path)
                    DO UPDATE SET snapshot=excluded.snapshot, available=1
                    WHERE julianday(json_extract(excluded.snapshot,'$.scannedAt')) >=
                        COALESCE(julianday(json_extract(projects.snapshot,'$.scannedAt')),0)
                    """)) {
                for (ObjectNode snapshot : snapshots) {
                    statement.setString(1, UUID.randomUUID().toString());
                    statement.setString(2, snapshot.path("path").asString());
                    statement.setString(3, json(snapshot));
                    statement.executeUpdate();
                }
            }
            return null;
        });
        scanState = new ScanState(now(), List.copyOf(errors), true);
        for (ProjectRow row : rows()) {
            contexts.refresh(row);
        }
    }

    private ObjectNode finishScan(CompletableFuture<ObjectNode> started, Throwable error) {
        synchronized (scanMonitor) {
            pendingScan = null;
            scanState = scanState.running(false);
            ObjectNode result = scanInfo();
            if (error == null) {
                started.complete(result);
            } else {
                started.completeExceptionally(error);
            }
            return result;
        }
    }

    ObjectNode refresh(String id) throws SQLException {
        Lock read = inspectionLock.readLock();
        read.lock();
        try {
            CompletableFuture<ObjectNode> started = new CompletableFuture<>();
            CompletableFuture<ObjectNode> existing = refreshing.putIfAbsent(id, started);
            if (existing != null) {
                return await(existing);
            }
            try {
                ObjectNode project = performRefresh(id);
                started.complete(project);
                return project;
            } catch (SQLException | RuntimeException | Error error) {
                started.completeExceptionally(error);
                throw error;
            } finally {
                refreshing.remove(id, started);
            }
        } finally {
            read.unlock();
        }
    }

    ObjectNode refreshAfterContextChange(String id) throws SQLException {
        var write = inspectionLock.writeLock();
        write.lock();
        try {
            return performRefresh(id);
        } finally {
            write.unlock();
        }
    }

    private ObjectNode performRefresh(String id) throws SQLException {
        ProjectRow row = require(id);
        var location = Path.of(row.path());
        List<String> roots = JournalSettings.strings(settings.read().path("roots"));
        ObjectNode snapshot;
        try {
            ProjectFiles.assertProjectAccess(location, roots);
            snapshot = files.inspect(row.path());
            ProjectFiles.assertProjectAccess(location, roots);
        } catch (IOException | TaskError ignored) {
            snapshot = null;
        }
        ObjectNode inspected = snapshot;
        ProjectRow current = store.write(connection -> {
            ProjectRow locked = ProjectRow.require(connection, id);
            if (!locked.path().equals(row.path())) {
                throw new TaskError(409, "Каталог проекта изменился. Повторите обновление.");
            }
            if (inspected == null || !roots.equals(JournalSettings.strings(settings.read(connection).path("roots")))) {
                unavailable(connection, row);
            } else {
                try (var statement = connection.prepareStatement("""
                        UPDATE projects SET snapshot=?,available=1 WHERE id=? AND julianday(?) >=
                            COALESCE(julianday(json_extract(snapshot,'$.scannedAt')),0)
                        """)) {
                    statement.setString(1, json(inspected));
                    statement.setString(2, id);
                    statement.setString(3, inspected.path("scannedAt").asString());
                    statement.executeUpdate();
                }
            }
            return ProjectRow.require(connection, id);
        });
        contexts.refresh(current);
        return latestView(id);
    }

    List<String> refreshKnown(BooleanSupplier cancelled) throws SQLException {
        List<String> errors = new ArrayList<>();
        for (ProjectRow row : rows()) {
            if (cancelled.getAsBoolean() || scanState.running()) {
                break;
            }
            if (!shouldRefresh(row.id())) {
                continue;
            }
            try {
                ObjectNode project = refresh(row.id());
                JsonNode snapshot = project.path("snapshot");
                if (!project.path("available").asBoolean()
                        || snapshot.path("git").hasNonNull("error") || snapshot.path("yaml").hasNonNull("error")
                        || !snapshot.path("errors").isEmpty()) {
                    errors.add(project.path("metadata").path("name").asString()
                            + ": не все данные удалось прочитать. Показаны доступные данные.");
                }
            } catch (SQLException | RuntimeException ignored) {
                try (var connection = store.open()) {
                    ProjectRow current = ProjectRow.find(connection, row.id());
                    if (current != null) {
                        errors.add(metadata(current).path("name").asString() + ": не удалось обновить состояние.");
                    }
                }
            }
        }
        return errors;
    }

    private boolean shouldRefresh(String id) throws SQLException {
        try (var connection = store.open()) {
            ProjectRow current = ProjectRow.find(connection, id);
            if (current == null || inScope(current.path(), settings.read(connection))) {
                return current != null;
            }
        }
        return store.write(connection -> {
            ProjectRow current = ProjectRow.find(connection, id);
            if (current == null) {
                return false;
            }
            if (inScope(current.path(), settings.read(connection))) {
                return true;
            }
            unavailable(connection, current);
            return false;
        });
    }

    List<ObjectNode> list() throws SQLException {
        try (var connection = store.open()) {
            return list(connection);
        }
    }

    List<ObjectNode> list(Connection connection) throws SQLException {
        ObjectNode configuration = settings.read(connection);
        List<ObjectNode> decisions = contexts.decisions();
        List<ObjectNode> projects = new ArrayList<>();
        for (ProjectRow row : ProjectRow.list(connection)) {
            projects.add(view(connection, row, configuration, decisions));
        }
        return projects;
    }

    ObjectNode view(Connection connection, ProjectRow row) throws SQLException {
        return view(connection, row, settings.read(connection), contexts.decisions());
    }

    private ObjectNode view(Connection connection, ProjectRow row, JsonNode configuration, List<ObjectNode> decisions)
            throws SQLException {
        JsonNode snapshot = mapper.readTree(row.snapshot());
        JsonNode yaml = snapshot.path("yaml");
        ObjectNode metadata = metadata(row, snapshot);
        ObjectNode result = mapper.createObjectNode().put("id", row.id()).put("path", row.path())
                .set("snapshot", snapshot).set("metadata", metadata)
                .put("metadataSource", row.metadata() != null ? "local" : yaml.hasNonNull("metadata") ? "yaml" : "defaults")
                .put("metadataVersion", metadataVersion(row, metadata)).put("notes", row.notes())
                .put("available", row.available());
        ArrayNode signals = result.putArray("signals");
        if (!row.available()) {
            signal(signals, "unavailable", "Не найден при последнем сканировании. Показан сохранённый снимок.", null);
        }
        attention(signals, metadata, snapshot, configuration);
        ObjectNode context = contexts.view(row);
        if ("public".equals(row.visibility())) {
            publicationSignals(signals, context);
        }
        boolean yamlConflict = row.metadata() != null
                && (!Objects.equals(row.yamlBaseHash(), yaml.path("hash").asString(null)) || yaml.hasNonNull("error"));
        if (yamlConflict) {
            signal(signals, "yaml-conflict", "PROJECT.yaml изменился после локального редактирования", "attention");
        }
        for (ObjectNode decision : decisions) {
            if (!row.id().equals(decision.path("projectId").asString(null))) {
                continue;
            }
            if (decision.hasNonNull("error")) {
                signal(signals, "decision-file-" + decision.path("key").asString(),
                        "Решения: " + decision.path("error").asString(), "attention");
            } else {
                decision.path("signals").forEach(signals::add);
            }
        }
        pendingDecisionSignal(connection, row.id(), signals);
        return result.put("yamlConflict", yamlConflict).set("context", context)
                .set("lifecycle", lifecycle(connection, row.id(), metadata.path("status").asString()));
    }

    ObjectNode metadata(ProjectRow row) {
        return metadata(row, mapper.readTree(row.snapshot()));
    }

    private ObjectNode metadata(ProjectRow row, JsonNode snapshot) {
        JsonNode yaml = snapshot.path("yaml").path("metadata");
        JsonNode input = row.metadata() == null ? yaml : mapper.readTree(row.metadata());
        if (row.metadata() == null && !yaml.isObject()) {
            return files.metadata(mapper.createObjectNode(), snapshot.path("directoryName").asString("Проект"));
        }
        return files.metadata(input, "Проект");
    }

    String metadataVersion(ProjectRow row, ObjectNode metadata) {
        ObjectNode editable = metadata.deepCopy().without(List.of("current_focus", "next"));
        ArrayNode scope = mapper.createArrayNode().add(row.path()).add(editable).add(row.notes());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(scope).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 недоступен", error);
        }
    }

    private ObjectNode lifecycle(Connection connection, String id, String status) throws SQLException {
        ObjectNode state = mapper.createObjectNode().put("revision", 0).putNull("currentClosure");
        try (var statement = connection.prepareStatement("""
                SELECT * FROM project_lifecycle_events WHERE scope_project_id=? ORDER BY revision DESC LIMIT 1
                """)) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    state.put("revision", rows.getLong("revision"));
                    if (("completed".equals(status) || "stopped".equals(status))
                            && "closed".equals(rows.getString("action")) && status.equals(rows.getString("outcome"))) {
                        state.putObject("currentClosure").put("id", rows.getString("id"))
                                .put("revision", rows.getLong("revision")).put("action", rows.getString("action"))
                                .put("outcome", rows.getString("outcome")).put("reason", rows.getString("reason"))
                                .put("recordedAt", rows.getString("recorded_at"));
                    }
                }
            }
        }
        return state;
    }

    private static void attention(ArrayNode signals, JsonNode metadata, JsonNode snapshot, JsonNode configuration) {
        JsonNode git = snapshot.path("git");
        if (git.hasNonNull("error")) {
            signal(signals, "git-error", git.path("error").asString(), null);
        }
        JsonNode yaml = snapshot.path("yaml");
        if (yaml.hasNonNull("error")) {
            signal(signals, "yaml-error", yaml.path("error").asString(), null);
        }
        snapshot.path("errors").forEach(error -> signal(signals, "read-error", error.asString(), null));
        if (git.path("dirty").asBoolean(false)) {
            signal(signals, "dirty", "Есть незакоммиченные изменения", null);
        }
        if (!metadata.path("blocked_by").isEmpty()) {
            signal(signals, "blocked", "Блокировки: " + String.join("; ", JournalSettings.strings(metadata.path("blocked_by"))), null);
        }
        if (!"active".equals(metadata.path("status").asString())) {
            return;
        }
        Instant now = Instant.now();
        String lastCommit = git.path("commits").path(0).path("date").asString(null);
        if (lastCommit != null) {
            try {
                long days = Math.floorDiv(now.toEpochMilli() - OffsetDateTime.parse(lastCommit).toInstant().toEpochMilli(),
                        DAY_MILLIS);
                if (days >= configuration.path("inactivityDays").asInt()) {
                    signal(signals, "inactive", "Нет новых commits " + days + " дн.", "info");
                }
            } catch (DateTimeParseException ignored) {
                // An invalid saved date has no age.
            }
        }
        String review = metadata.path("last_reviewed").asString(null);
        if (review == null) {
            signal(signals, "review",
                    "Проверьте описание проекта: цель, этап и ограничения. Дата последней проверки не указана.", null);
        } else if (now.toEpochMilli() - LocalDate.parse(review).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                >= configuration.path("reviewDays").asInt() * DAY_MILLIS) {
            signal(signals, "review",
                    "Проверьте описание проекта: цель, этап и ограничения. Последняя проверка — " + review + ".", null);
        }
    }

    private static void publicationSignals(ArrayNode signals, ObjectNode context) {
        JsonNode audit = context.path("audit");
        if ("unavailable".equals(audit.path("status").asString())) {
            signal(signals, "publication-unavailable", "Не удалось проверить отслеживаемые Git файлы", "attention");
        }
        Set<String> infrastructure = new LinkedHashSet<>();
        int documents = 0;
        for (JsonNode finding : audit.path("findings")) {
            switch (finding.path("kind").asString()) {
                case "infrastructure" -> {
                    String path = finding.path("path").asString();
                    if (path.startsWith(".ai-rules/")) {
                        path = ".ai-rules/";
                    } else if (path.startsWith(".local/")) {
                        path = ".local/";
                    }
                    infrastructure.add(path);
                }
                case "document" -> documents++;
                default -> { }
            }
        }
        if (!infrastructure.isEmpty()) {
            signal(signals, "publication-infrastructure", "В публичном Git отслеживается внутренняя инфраструктура: "
                    + String.join(", ", infrastructure), "attention");
        }
        if (documents > 0) {
            signal(signals, "publication-documents", "Документы требуют проверки публикации: " + documents, "attention");
        }
        if ("unavailable".equals(context.path("privateStatus").asString()) && context.path("hadPrivateRecords").asBoolean()) {
            signal(signals, "private-context-unavailable", "Не найден ранее подключённый приватный контекст", "attention");
        }
    }

    private static void pendingDecisionSignal(Connection connection, String id, ArrayNode signals) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT count(*) FROM records WHERE kind='decisions' AND project_id=? AND json_extract(data,'$.status')='pending'
                """)) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (rows.next() && rows.getInt(1) > 0) {
                    signal(signals, "decision", "Ожидают решения: " + rows.getInt(1), "decision");
                }
            }
        }
    }

    private static void signal(ArrayNode signals, String code, String message, String level) {
        ObjectNode signal = signals.addObject().put("code", code).put("message", message);
        if (level != null) {
            signal.put("level", level);
        }
    }

    ObjectNode rebind(String id, String targetId) throws SQLException {
        Lock read = beginProjectChange();
        try {
            if (id.equals(targetId)) {
                throw new TaskError(400, "Выберите другой найденный каталог.");
            }
            ObjectNode previous = refresh(id);
            if (previous.path("available").asBoolean()) {
                throw new TaskError(409, "Перепривязка доступна только недоступному проекту.");
            }
            ObjectNode candidate = refresh(targetId);
            ProjectRow rebound = store.write(connection -> {
                ProjectRow old = ProjectRow.require(connection, id);
                ProjectRow target = ProjectRow.require(connection, targetId);
                if (!old.path().equals(previous.path("path").asString())
                        || !target.path().equals(candidate.path("path").asString())) {
                    throw new TaskError(409, "Каталог проекта изменился. Повторите обновление.");
                }
                if (old.available()) {
                    throw new TaskError(409, "Перепривязка доступна только недоступному проекту.");
                }
                if (!target.available() || !inScope(target.path(), settings.read(connection))) {
                    throw new TaskError(409, "Новый каталог недоступен.");
                }
                if (old.privateContextPath() != null && overlap(target.path(), old.privateContextPath())) {
                    throw new TaskError(409,
                            "Новый репозиторий пересекается с приватным контекстом. Сначала измените связь.");
                }
                if (target.metadata() != null || !target.notes().isEmpty() || !"unknown".equals(target.visibility())
                        || target.privateContextPath() != null || !mapper.readTree(target.publicationAllowlist()).isEmpty()
                        || targetUsed(connection, targetId)) {
                    throw new TaskError(409, "У выбранного проекта уже есть свои данные. Автоматическое слияние "
                            + "запрещено. Выберите новый, ещё не настроенный каталог.");
                }
                execute(connection, "DELETE FROM projects WHERE id=?", targetId);
                execute(connection, "UPDATE projects SET path=?,snapshot=?,available=1 WHERE id=?",
                        target.path(), target.snapshot(), id);
                return ProjectRow.require(connection, id);
            });
            contexts.forget(targetId);
            contexts.refresh(rebound);
            return latestView(id);
        } finally {
            read.unlock();
        }
    }

    void forget(String id) throws SQLException {
        Lock read = beginProjectChange();
        try {
            store.write(connection -> {
                ProjectRow row = ProjectRow.require(connection, id);
                ObjectNode configuration = settings.read(connection);
                List<String> roots = JournalSettings.strings(configuration.path("roots"));
                Set<String> excluded = new LinkedHashSet<>(JournalSettings.strings(configuration.path("excludedProjectPaths")));
                excluded.add(row.path());
                ArrayNode newRoots = configuration.putArray("roots");
                roots.stream().filter(root -> !samePath(root, row.path())).forEach(newRoots::add);
                ArrayNode newExcluded = configuration.putArray("excludedProjectPaths");
                excluded.forEach(newExcluded::add);
                settings.save(connection, configuration);

                tasks.detachProject(connection, id);
                String now = now();
                execute(connection, """
                        UPDATE records SET project_id=NULL,data=json_set(data,'$.projectId',NULL),updated_at=?
                        WHERE project_id=? AND kind!='tasks'
                        """, now, id);
                execute(connection, "UPDATE checks SET project_id=NULL WHERE project_id=?", id);
                execute(connection, "UPDATE observations SET revision=revision+1,updated_at=? WHERE project_id=?", now, id);
                execute(connection, "UPDATE reviews SET revision=revision+1,updated_at=? WHERE project_id=?", now, id);
                execute(connection, "DELETE FROM relations WHERE sourceId=? OR targetId=?", id, id);
                execute(connection, """
                        UPDATE analysis_schedules SET enabled=0,revision=revision+1,
                            note='Состав проектов изменился; подтвердите источники заново.'
                        WHERE scope_key='project:'||? OR json_extract(selection_json,'$.includeGeneral')=1
                            OR EXISTS(SELECT 1 FROM json_each(json_extract(selection_json,'$.projectIds')) WHERE value=?)
                        """, id, id);
                execute(connection, "DELETE FROM projects WHERE id=?", id);
                return null;
            });
            contexts.forget(id);
        } finally {
            read.unlock();
        }
    }

    private Lock beginProjectChange() {
        Lock read = inspectionLock.readLock();
        if (scanState.running() || !read.tryLock()) {
            throw new TaskError(409, "Дождитесь сканирования.");
        }
        if (scanState.running()) {
            read.unlock();
            throw new TaskError(409, "Дождитесь сканирования.");
        }
        return read;
    }

    private boolean targetUsed(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement(USED_TARGET)) {
            statement.setString(1, id);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private static boolean inScope(String path, JsonNode configuration) {
        Path directory = Path.of(path);
        return JournalSettings.strings(configuration.path("roots")).stream()
                .anyMatch(root -> ProjectFiles.within(Path.of(root), directory))
                && JournalSettings.strings(configuration.path("excludedProjectPaths")).stream()
                .noneMatch(excluded -> samePath(excluded, path));
    }

    private static boolean samePath(String first, String second) {
        return ProjectFiles.within(Path.of(first), Path.of(second)) && ProjectFiles.within(Path.of(second), Path.of(first));
    }

    private static boolean overlap(String first, String second) {
        return ProjectFiles.within(Path.of(first), Path.of(second)) || ProjectFiles.within(Path.of(second), Path.of(first));
    }

    private static void unavailable(Connection connection, ProjectRow row) throws SQLException {
        execute(connection, "UPDATE projects SET available=0 WHERE id=? AND snapshot=?", row.id(), row.snapshot());
    }

    private static void execute(Connection connection, String sql, String... args) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                statement.setString(index + 1, args[index]);
            }
            statement.executeUpdate();
        }
    }

    private ProjectRow require(String id) throws SQLException {
        try (var connection = store.open()) {
            return ProjectRow.require(connection, id);
        }
    }

    private List<ProjectRow> rows() throws SQLException {
        try (var connection = store.open()) {
            return ProjectRow.list(connection);
        }
    }

    private ObjectNode latestView(String id) throws SQLException {
        try (var connection = store.open()) {
            return view(connection, ProjectRow.require(connection, id));
        }
    }

    private String json(JsonNode value) {
        String encoded = writer.writeValueAsString(value);
        return LONE_SURROGATE.matcher(encoded).replaceAll(match ->
                Matcher.quoteReplacement("\\u%04x".formatted((int) match.group().charAt(0))));
    }

    private static String now() {
        return INSTANT.format(Instant.now());
    }

    private static <T> T await(CompletableFuture<T> operation) throws SQLException {
        try {
            return operation.join();
        } catch (CompletionException error) {
            switch (error.getCause()) {
                case SQLException sql -> throw sql;
                case RuntimeException runtime -> throw runtime;
                case Error failure -> throw failure;
                default -> throw error;
            }
        }
    }

    private record ScanState(String scannedAt, List<String> errors, boolean running) {
        private ScanState running(boolean value) {
            return new ScanState(scannedAt, errors, value);
        }
    }
}
