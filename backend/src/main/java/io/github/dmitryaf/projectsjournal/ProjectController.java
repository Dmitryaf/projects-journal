package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api")
final class ProjectController {

    private final ApiJson json;
    private final JournalSettings settings;
    private final ProjectService projects;
    private final ProjectRefresh refresh;
    private final ProjectContextService contexts;
    private final ProjectLifecycleService lifecycle;
    private final WorkspaceView workspace;

    ProjectController(ApiJson json, JournalSettings settings, ProjectService projects, ProjectRefresh refresh,
                      ProjectContextService contexts, ProjectLifecycleService lifecycle, WorkspaceView workspace) {
        this.json = json;
        this.settings = settings;
        this.projects = projects;
        this.refresh = refresh;
        this.contexts = contexts;
        this.lifecycle = lifecycle;
        this.workspace = workspace;
    }

    @GetMapping("/workspace")
    ObjectNode workspace() throws SQLException {
        return workspace.read();
    }

    @PostMapping("/scan")
    ObjectNode scan(HttpServletRequest request) throws IOException, SQLException {
        json.read(request);
        return projects.scan();
    }

    @PutMapping("/settings")
    ObjectNode settings(HttpServletRequest request) throws IOException, SQLException {
        if (projects.scanInfo().get("running").booleanValue()) {
            throw new TaskError(409, "Дождитесь завершения сканирования.");
        }
        var configuration = settings.update(json.read(request));
        refresh.reschedule();
        projects.scan();
        return configuration;
    }

    @GetMapping("/projects/{id}")
    ObjectNode project(@PathVariable String id) throws SQLException {
        return projects.refresh(id);
    }

    @PutMapping("/projects/{id}")
    ObjectNode edit(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return lifecycle.edit(id, json.read(request));
    }

    @PostMapping("/projects/{id}/use-yaml")
    ObjectNode useYaml(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return lifecycle.reconcile(id, json.read(request), true);
    }

    @PostMapping("/projects/{id}/keep-local")
    ObjectNode keepLocal(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return lifecycle.reconcile(id, json.read(request), false);
    }

    @PostMapping("/projects/{id}/export")
    ObjectNode export(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return lifecycle.export(id, json.read(request));
    }

    @PostMapping("/projects/{id}/rebind")
    ObjectNode rebind(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        var input = json.read(request);
        return projects.rebind(id, ProjectLifecycleService.uuid(input.get("targetId"), "targetId"));
    }

    @DeleteMapping("/projects/{id}")
    Map<String, Boolean> forget(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        json.read(request);
        projects.forget(id);
        return Map.of("ok", true);
    }

    @PutMapping("/projects/{id}/context")
    ObjectNode context(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        contexts.save(id, json.read(request));
        return projects.refreshAfterContextChange(id);
    }

    @PutMapping("/projects/{id}/publication-allowlist")
    ObjectNode allowlist(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        var input = json.read(request);
        var path = ApiJson.text(input.get("path"), "path", 1, 4000, false);
        var allowed = input.path("allowed");
        if (!allowed.isBoolean()) {
            throw ApiJson.invalid("allowed");
        }
        contexts.allow(id, path, allowed.booleanValue());
        return projects.refreshAfterContextChange(id);
    }

    @PostMapping("/projects/{id}/decision-content")
    ObjectNode decision(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        var input = json.read(request);
        return contexts.content(id, ApiJson.text(input.get("key"), "key", 1, 6000, false));
    }

    @GetMapping("/project-lifecycle/{id}")
    ObjectNode event(@PathVariable String id) throws SQLException {
        return lifecycle.event(id);
    }

    @PostMapping("/projects/{id}/close")
    ObjectNode closeProject(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return lifecycle.transition(id, json.read(request), true);
    }

    @PostMapping("/projects/{id}/reopen")
    ObjectNode reopenProject(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return lifecycle.transition(id, json.read(request), false);
    }
}
