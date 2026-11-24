package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api")
final class AnalysisController {

    private final ApiJson json;
    private final LegacyProjectAnalysis legacy;
    private final PeriodRuns runs;
    private final PeriodDispatch dispatch;
    private final ProposalAcceptanceService acceptances;
    private final CodexAnalysis codex;

    AnalysisController(ApiJson json, LegacyProjectAnalysis legacy, PeriodRuns runs,
                       PeriodDispatch dispatch, ProposalAcceptanceService acceptances, CodexAnalysis codex) {
        this.json = json;
        this.legacy = legacy;
        this.runs = runs;
        this.dispatch = dispatch;
        this.acceptances = acceptances;
        this.codex = codex;
    }

    @GetMapping("/analysis/status")
    ObjectNode legacyStatus() {
        return legacy.status();
    }

    @PostMapping("/analysis/prepare")
    ObjectNode legacyPrepare(HttpServletRequest request) throws IOException, SQLException {
        return legacy.prepare(json.read(request));
    }

    @PostMapping("/analysis/run")
    ObjectNode legacyRun(HttpServletRequest request) throws IOException, SQLException {
        return legacy.run(json.read(request));
    }

    @GetMapping("/period-analysis/status")
    ObjectNode status() {
        return dispatch.status();
    }

    @GetMapping("/period-analysis/recent")
    ObjectNode recent(@RequestParam MultiValueMap<String, String> query) throws SQLException {
        return runs.recent(json.query(query, null));
    }

    @GetMapping("/period-analysis/{id}")
    ObjectNode read(@PathVariable String id) throws SQLException {
        return runs.read(id);
    }

    @GetMapping("/reviews/{id}/analyses")
    ObjectNode list(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) throws SQLException {
        return runs.list(id, json.query(query, null));
    }

    @PostMapping("/period-analysis/import-codex")
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode importCodex(HttpServletRequest request) throws IOException, SQLException {
        return runs.importCodex(json.read(request));
    }

    @PostMapping("/period-analysis/prepare")
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode prepare(HttpServletRequest request) throws IOException, SQLException {
        return dispatch.prepare(json.read(request));
    }

    @PostMapping("/period-analysis/{id}/run")
    ObjectNode run(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return dispatch.run(id, json.read(request));
    }

    @PostMapping("/period-analysis/{id}/cancel")
    ObjectNode cancel(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        json.read(request);
        var run = dispatch.cancel(id);
        codex.abort(id);
        return run;
    }

    @DeleteMapping("/period-analysis/{id}")
    Map<String, Boolean> remove(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        dispatch.delete(id, json.read(request));
        return Map.of("ok", true);
    }

    @GetMapping("/proposal-acceptances")
    ObjectNode accepted(@RequestParam MultiValueMap<String, String> query) throws SQLException {
        return acceptances.list(json.query(query, null));
    }

    @GetMapping("/proposal-acceptances/{id}")
    ObjectNode accepted(@PathVariable String id) throws SQLException {
        return acceptances.read(id);
    }

    @GetMapping("/period-analysis/{id}/acceptances")
    JsonNode acceptances(@PathVariable String id) throws SQLException {
        return acceptances.forRun(id);
    }

    @PostMapping("/period-analysis/{id}/proposals/{index}/accept")
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode accept(@PathVariable String id, @PathVariable String index, HttpServletRequest request)
            throws IOException, SQLException {
        return acceptances.accept(id, index, json.read(request));
    }
}
