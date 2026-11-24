package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class McpReadTools {

    static final List<String> TASK_OUTCOMES = List.of("open", "done", "cancelled", "all");
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final TaskService tasks;
    private final ProjectService projects;
    private final ProjectContextService contexts;
    private final ProjectResolver resolver;
    private final JournalRecords records;

    McpReadTools(SqliteStore store, JsonMapper mapper, TaskService tasks) {
        this.store = store;
        this.mapper = mapper;
        this.tasks = tasks;
        var settings = new JournalSettings(store, mapper);
        var files = new ProjectFiles(mapper);
        contexts = new ProjectContextService(store, mapper, files, settings);
        projects = new ProjectService(store, mapper, settings, files, contexts, tasks);
        resolver = new ProjectResolver(store, settings);
        records = new JournalRecords(store, mapper);
    }

    ObjectNode projects(JsonNode input) throws SQLException {
        String query = query(input);
        long offset = offset(input);
        int limit = limit(input);
        List<ObjectNode> items = new ArrayList<>();
        try (var connection = store.open()) {
            for (var row : ProjectRow.list(connection)) {
                String name = projects.metadata(row).path("name").asString();
                if (contains(query, name, row.id())) {
                    items.add(mapper.createObjectNode().put("id", row.id()).put("name", name)
                            .put("path", row.path()).put("available", row.available()));
                }
            }
        }
        return page(items, offset, limit);
    }

    ObjectNode context(JsonNode input) throws SQLException, IOException {
        String id = input.has("projectId") ? ProjectLifecycleService.uuid(input.get("projectId"), "projectId") : null;
        String directory = input.has("workingDirectory")
                ? ApiJson.text(input.get("workingDirectory"), "workingDirectory", 1, 2000, true) : null;
        String decisionKey = input.has("decisionKey")
                ? ApiJson.text(input.get("decisionKey"), "decisionKey", 1, 6000, false) : null;

        var row = resolver.resolve(id, directory);
        var project = projects.refresh(row.id());
        requireSamePath(row, project.path("path").asString());
        var snapshot = project.path("snapshot");
        var metadata = project.path("metadata");
        var summary = pick(project, "id", "path", "metadata", "notes", "available", "yamlConflict");
        summary.set("name", metadata.path("name"));
        summary.set("snapshotAt", snapshot.path("scannedAt"));
        summary.set("git", snapshot.path("git"));
        var errors = summary.putArray("readErrors");
        snapshot.path("errors").forEach(errors::add);
        if (snapshot.path("yaml").hasNonNull("error")) {
            errors.add(snapshot.path("yaml").get("error"));
        }

        var result = mapper.createObjectNode().put("taskSource", "tasks");
        result.set("project", summary);
        result.set("legacyPlan", pick(metadata, "current_focus", "next")
                .put("note", "Сохранённая сводка. Текущая работа определяется задачами; перенос шагов явный."));
        var relatedTasks = result.putArray("tasks");
        var localDecisions = result.putArray("localDecisions");
        try (var connection = store.open()) {
            for (var task : tasks.list(connection)) {
                if ("open".equals(outcome(task)) && TaskService.projectIds(task).contains(row.id())) {
                    relatedTasks.add(pick(task, "code", "title", "priority", "revision"));
                }
            }
            for (var record : records.list(connection, "decisions")) {
                if (row.id().equals(record.path("projectId").stringValue(null))) {
                    localDecisions.add(record);
                }
            }
        }
        var indexedDecisions = result.putArray("decisions");
        for (var decision : contexts.decisions()) {
            if (row.id().equals(decision.path("projectId").stringValue(null))) {
                indexedDecisions.add(decision);
            }
        }
        if (decisionKey != null) {
            result.set("decision", contexts.content(row.id(), decisionKey));
        }
        try (var connection = store.open()) {
            requireSamePath(row, ProjectRow.require(connection, row.id()).path());
        }
        return result;
    }

    ObjectNode tasks(JsonNode input) throws SQLException {
        boolean scoped = input.has("projectId");
        String projectId = scoped && !input.path("projectId").isNull()
                ? ProjectLifecycleService.uuid(input.get("projectId"), "projectId") : null;
        String query = query(input);
        String completed = input.has("completed") ? ApiJson.text(input.get("completed"), "completed", 1, 10, false) : "open";
        if (!TASK_OUTCOMES.contains(completed)) {
            throw ApiJson.invalid("completed");
        }
        long offset = offset(input);
        int limit = limit(input);
        List<ObjectNode> items = new ArrayList<>();
        try (var connection = store.open()) {
            if (projectId != null) {
                ProjectRow.require(connection, projectId);
            }
            for (var task : tasks.list(connection)) {
                var projectIds = TaskService.projectIds(task);
                boolean inScope = !scoped || (projectId == null ? projectIds.isEmpty() : projectIds.contains(projectId));
                if (inScope && ("all".equals(completed) || completed.equals(outcome(task)))
                        && contains(query, task.path("code").asString(), task.path("id").asString(),
                                task.path("title").asString(), task.path("description").asString())) {
                    var item = pick(task, "id", "code", "title", "projectId", "priority", "completedAt", "cancellation", "revision");
                    projectIds.forEach(item.putArray("projectIds")::add);
                    items.add(item);
                }
            }
        }
        return page(items, offset, limit);
    }

    private ObjectNode page(List<ObjectNode> items, long offset, int limit) {
        var result = mapper.createObjectNode().put("total", items.size());
        int start = (int) Math.min(offset, items.size());
        int size = Math.min(limit, items.size() - start);
        var array = result.putArray("items");
        items.subList(start, start + size).forEach(array::add);
        return result;
    }

    private ObjectNode pick(JsonNode source, String... fields) {
        var result = mapper.createObjectNode();
        for (var field : fields) {
            if (source.has(field)) {
                result.set(field, source.get(field));
            }
        }
        return result;
    }

    private static void requireSamePath(ProjectRow selected, String currentPath) {
        if (!selected.path().equals(currentPath)) {
            throw new TaskError(409, "Каталог проекта изменился. Повторите чтение контекста.");
        }
    }

    private static String outcome(JsonNode task) {
        if (task.hasNonNull("cancellation")) {
            return "cancelled";
        }
        return task.hasNonNull("completedAt") ? "done" : "open";
    }

    private static boolean contains(String query, String... fields) {
        return String.join(" ", fields).toLowerCase(RUSSIAN).contains(query);
    }

    private static String query(JsonNode input) {
        return input.has("query") ? ApiJson.text(input.get("query"), "query", 0, 240, false).toLowerCase(RUSSIAN) : "";
    }

    private static long offset(JsonNode input) {
        return input.has("offset") ? ApiJson.integer(input.get("offset"), "offset", 0, MAX_SAFE_INTEGER) : 0;
    }

    private static int limit(JsonNode input) {
        return input.has("limit") ? (int) ApiJson.integer(input.get("limit"), "limit", 1, 100) : 50;
    }
}
