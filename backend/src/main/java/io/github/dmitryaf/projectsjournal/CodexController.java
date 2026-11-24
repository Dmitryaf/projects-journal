package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api")
final class CodexController {

    private final ApiJson json;
    private final CodexContext context;
    private final CodexAnalysis analysis;

    CodexController(ApiJson json, CodexContext context, CodexAnalysis analysis) {
        this.json = json;
        this.context = context;
        this.analysis = analysis;
    }

    @GetMapping("/codex-context/digests")
    ObjectNode digests(@RequestParam MultiValueMap<String, String> query) throws SQLException {
        return context.list(json.query(query, null));
    }

    @PostMapping("/codex-context/prepare")
    ObjectNode prepareDigests(HttpServletRequest request) throws IOException, SQLException {
        return context.prepare(json.read(request));
    }

    @GetMapping("/codex-context/digests/{id}")
    ObjectNode digest(@PathVariable String id) throws SQLException {
        return context.read(id);
    }

    @PatchMapping("/codex-context/digests/{id}")
    ObjectNode edit(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return context.edit(id, json.read(request));
    }

    @PostMapping("/codex-context/digests/{id}/check")
    ObjectNode check(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return context.check(id, json.read(request));
    }

    @DeleteMapping("/codex-context/digests/{id}")
    Map<String, Boolean> delete(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        context.delete(id, json.read(request));
        return Map.of("ok", true);
    }

    @GetMapping("/projects/{id}/codex-context")
    ObjectNode grant(@PathVariable String id) throws SQLException {
        return context.grant(id);
    }

    @PutMapping("/projects/{id}/codex-context")
    ObjectNode configure(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return context.configure(id, json.read(request));
    }

    @PostMapping("/projects/{id}/codex-context/probe")
    ObjectNode probe(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        var input = json.read(request);
        ApiJson.requireObject(input);
        if (!input.propertyNames().equals(Set.of("revision"))) {
            throw ApiJson.invalid("revision");
        }
        var revision = ApiJson.revision(input.get("revision"), "revision");
        if (revision < 1) {
            throw ApiJson.invalid("revision");
        }
        return context.probe(id, revision);
    }

    @PostMapping("/projects/{id}/codex-context/threads")
    ObjectNode threads(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return context.threads(id, json.read(request));
    }

    @PostMapping("/projects/{id}/codex-context/preview")
    ObjectNode preview(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return context.preview(id, json.read(request));
    }

    @PostMapping("/projects/{id}/codex-context/digests")
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode save(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return context.save(id, json.read(request));
    }

    @GetMapping("/codex-analysis/status")
    ObjectNode status(@RequestParam(defaultValue = "false") boolean refresh) {
        return analysis.status(refresh);
    }

    @GetMapping("/codex-analysis/instructions")
    ObjectNode instructions() {
        return analysis.instructions();
    }

    @PostMapping("/codex-analysis/login")
    ObjectNode login(HttpServletRequest request) throws IOException {
        requireConfirm(request);
        return analysis.login();
    }

    @PostMapping("/codex-analysis/login/cancel")
    ObjectNode cancelLogin(HttpServletRequest request) throws IOException {
        requireConfirm(request);
        return analysis.cancelLogin();
    }

    @PostMapping("/codex-analysis/files")
    ObjectNode files(HttpServletRequest request) throws IOException, SQLException {
        return analysis.files(json.read(request));
    }

    @GetMapping("/codex-analysis/conversation")
    ObjectNode conversation(@RequestParam MultiValueMap<String, String> query) throws SQLException {
        return analysis.conversation(json.query(query, null));
    }

    @PostMapping("/codex-analysis/prepare")
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode prepareAnalysis(HttpServletRequest request) throws IOException, SQLException {
        return analysis.prepare(json.read(request));
    }

    @GetMapping("/codex-analysis/{id}")
    ObjectNode analysis(@PathVariable String id) throws SQLException {
        return analysis.read(id);
    }

    @PostMapping("/codex-analysis/{id}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ObjectNode start(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return analysis.start(id, json.read(request));
    }

    private void requireConfirm(HttpServletRequest request) throws IOException {
        var input = json.read(request);
        ApiJson.requireObject(input);
        var confirm = input.path("confirm");
        if (!input.propertyNames().equals(Set.of("confirm")) || !confirm.isBoolean() || !confirm.booleanValue()) {
            throw ApiJson.invalid("confirm");
        }
    }
}
