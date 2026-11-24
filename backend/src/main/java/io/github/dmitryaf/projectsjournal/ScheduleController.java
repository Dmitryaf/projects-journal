package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api")
final class ScheduleController {

    private final ApiJson json;
    private final JsonMapper mapper;
    private final AnalysisScheduleSettings settings;

    ScheduleController(ApiJson json, JsonMapper mapper, AnalysisScheduleSettings settings) {
        this.json = json;
        this.mapper = mapper;
        this.settings = settings;
    }

    @GetMapping("/analysis-schedules")
    ObjectNode list(@RequestParam MultiValueMap<String, String> query) throws SQLException {
        return settings.list(json.query(query, null));
    }

    @GetMapping("/reviews/{id}/schedule")
    JsonNode forReview(@PathVariable String id) throws SQLException {
        var schedule = settings.forReview(id);
        return schedule == null ? mapper.getNodeFactory().nullNode() : schedule;
    }

    @PostMapping("/analysis-schedules")
    ObjectNode enable(HttpServletRequest request) throws IOException, SQLException {
        return settings.enable(json.read(request));
    }

    @PostMapping("/analysis-schedules/{id}/disable")
    ObjectNode disable(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return settings.disable(id, json.read(request));
    }

    @PostMapping("/analysis-schedules/{id}/resume")
    ObjectNode resume(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return settings.resume(id, json.read(request));
    }

    @DeleteMapping("/analysis-schedules/{id}")
    Map<String, Boolean> delete(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        settings.delete(id, json.read(request));
        return Map.of("ok", true);
    }
}
