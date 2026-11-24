package io.github.dmitryaf.controlcenter;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api")
final class MvpApi {

    private static final Map<String, Boolean> OK = Map.of("ok", true);

    private final MvpStore store;
    private final ProjectRegistry projects;
    private final MvpRecords records;
    private final Input input;
    private final JsonMapper mapper;

    MvpApi(MvpStore store, ProjectRegistry projects, MvpRecords records, Input input, JsonMapper mapper) {
        this.store = store;
        this.projects = projects;
        this.records = records;
        this.input = input;
        this.mapper = mapper;
    }

    @GetMapping("/workspace")
    ObjectNode workspace() throws SQLException {
        try (var connection = store.open()) {
            connection.setAutoCommit(false);
            var settings = projects.settings(connection);
            var result = mapper.createObjectNode();
            projects.list(connection, settings).forEach(result.putArray("projects")::add);
            for (String kind : new String[]{"tasks", "ideas", "decisions"}) {
                records.list(connection, kind).forEach(result.putArray(kind)::add);
            }
            records.relations(connection).forEach(result.putArray("relations")::add);
            result.set("settings", settings).set("scan", projects.scanInfo());
            connection.commit();
            return result;
        }
    }

    @PostMapping("/scan")
    ObjectNode scan() throws SQLException {
        return projects.scan();
    }

    @PutMapping("/settings")
    ObjectNode settings(HttpServletRequest request) throws SQLException, IOException {
        if (projects.scanInfo().path("running").booleanValue()) {
            throw new ApiFailure(409, "Дождитесь завершения сканирования.");
        }
        return projects.saveSettings(input.read(request));
    }

    @GetMapping("/projects/{id}")
    ObjectNode project(@PathVariable String id) throws SQLException {
        return projects.refresh(id);
    }

    @PutMapping("/projects/{id}")
    ObjectNode editProject(@PathVariable String id, HttpServletRequest request) throws SQLException, IOException {
        return projects.edit(id, input.read(request));
    }

    @PostMapping("/projects/{id}/use-yaml")
    ObjectNode useYaml(@PathVariable String id) throws SQLException {
        return projects.useYaml(id);
    }

    @PostMapping("/projects/{id}/export")
    ObjectNode exportYaml(@PathVariable String id, HttpServletRequest request) throws SQLException, IOException {
        return projects.exportYaml(id, input.read(request));
    }

    @PostMapping("/{kind:tasks|ideas|decisions}")
    ResponseEntity<ObjectNode> createRecord(@PathVariable String kind, HttpServletRequest request) throws SQLException, IOException {
        return ResponseEntity.status(201).body(records.save(kind, null, input.read(request)));
    }

    @PutMapping("/{kind:tasks|ideas|decisions}/{id}")
    ObjectNode editRecord(@PathVariable String kind, @PathVariable String id, HttpServletRequest request)
            throws SQLException, IOException {
        return records.save(kind, id, input.read(request));
    }

    @DeleteMapping("/{kind:tasks|ideas|decisions}/{id}")
    Map<String, Boolean> deleteRecord(@PathVariable String kind, @PathVariable String id) throws SQLException {
        records.delete(kind, id);
        return OK;
    }

    @PostMapping("/relations")
    ResponseEntity<ObjectNode> relate(HttpServletRequest request) throws SQLException, IOException {
        return ResponseEntity.status(201).body(records.relate(input.read(request)));
    }

    @DeleteMapping("/relations/{id}")
    Map<String, Boolean> deleteRelation(@PathVariable String id) throws SQLException {
        records.removeRelation(id);
        return OK;
    }

    @RequestMapping({"", "/{*path}"})
    ResponseEntity<Map<String, String>> missingRoute() {
        return ApiErrors.error(404, "Маршрут не найден.");
    }
}
