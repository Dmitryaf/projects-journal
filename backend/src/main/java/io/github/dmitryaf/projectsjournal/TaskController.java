package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api")
class TaskController {

    private final TaskService tasks;
    private final TaskHistoryService history;
    private final ProjectService projects;
    private final SqliteStore store;
    private final ApiJson json;
    private final String instance;

    TaskController(TaskService tasks, TaskHistoryService history, ProjectService projects,
                   SqliteStore store, ApiJson json)
            throws IOException, NoSuchAlgorithmException {
        this.tasks = tasks;
        this.history = history;
        this.projects = projects;
        this.store = store;
        this.json = json;
        var paths = Path.of("").toRealPath() + "\n" + store.path();
        if (System.getProperty("os.name").startsWith("Windows")) {
            paths = paths.toLowerCase(Locale.ROOT);
        }
        instance = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(paths.getBytes(StandardCharsets.UTF_8)));
    }

    @GetMapping("/health")
    Map<String, Object> health() {
        return Map.of("application", "control-center", "instance", instance,
                "pid", ProcessHandle.current().pid(), "runtime", "java");
    }

    @PostMapping("/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode create(HttpServletRequest request) throws IOException, SQLException {
        return tasks.create(json.read(request));
    }

    @GetMapping("/tasks/{identifier}")
    ObjectNode read(@PathVariable String identifier) throws SQLException {
        return tasks.read(identifier);
    }

    @PutMapping("/tasks/{identifier}")
    ObjectNode update(@PathVariable String identifier, HttpServletRequest request)
            throws IOException, SQLException {
        return tasks.update(identifier, json.read(request));
    }

    @PostMapping("/tasks/{identifier}/complete")
    ObjectNode complete(@PathVariable String identifier, HttpServletRequest request)
            throws IOException, SQLException {
        var input = json.read(request);
        ApiJson.requireObject(input);
        return tasks.complete(identifier, positiveRevision(input.get("revision")), input.get("result"));
    }

    @DeleteMapping("/tasks/{identifier}")
    Map<String, Boolean> delete(@PathVariable String identifier, HttpServletRequest request)
            throws IOException, SQLException {
        var input = json.read(request);
        ApiJson.requireObject(input);
        Long revision = input.has("revision") ? positiveRevision(input.get("revision")) : null;
        tasks.delete(identifier, revision);
        return Map.of("ok", true);
    }

    @PostMapping("/projects/{id}/import-plan")
    List<ObjectNode> importPlan(@PathVariable String id, HttpServletRequest request)
            throws IOException, SQLException {
        json.read(request);
        List<String> steps;
        try (var connection = store.open()) {
            steps = JournalSettings.strings(projects.metadata(ProjectRow.require(connection, id)).get("next"));
        }
        return tasks.importPlan(id, steps);
    }

    @GetMapping("/task-history")
    ObjectNode history(@RequestParam MultiValueMap<String, String> parameters) throws SQLException {
        var query = json.query(parameters, "__general__");
        for (var field : List.of("offset", "limit")) {
            if (query.has(field)) {
                query.put(field, ApiJson.numberText(query.get(field).stringValue(), field));
            }
        }
        return history.list(query);
    }

    private static long positiveRevision(JsonNode input) {
        var revision = ApiJson.revision(input, "revision");
        if (revision == 0) {
            throw ApiJson.invalid("revision");
        }
        return revision;
    }

    @PostMapping("/backup")
    Map<String, String> backup(HttpServletRequest request) throws IOException, SQLException {
        json.read(request);
        return Map.of("path", store.backup().toString());
    }

    @RequestMapping({"", "/{*route}"})
    ResponseEntity<Map<String, String>> routeNotFound() {
        return ApiErrors.error(404, "Маршрут не найден.");
    }
}
