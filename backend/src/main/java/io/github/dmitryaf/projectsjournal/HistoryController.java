package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api")
final class HistoryController {

    private final SqliteStore store;
    private final ApiJson json;
    private final HistoryService history;
    private final ObservationService observations;
    private final ReviewService reviews;

    HistoryController(SqliteStore store, ApiJson json, HistoryService history, ObservationService observations,
                      ReviewService reviews) {
        this.store = store;
        this.json = json;
        this.history = history;
        this.observations = observations;
        this.reviews = reviews;
    }

    @GetMapping("/history")
    ObjectNode history(@RequestParam MultiValueMap<String, String> query) throws SQLException {
        return history.list(json.query(query, "__unlinked__"));
    }

    @GetMapping("/history/latest-projects")
    ObjectNode latestProjects(@RequestParam MultiValueMap<String, String> query) throws SQLException {
        return history.latestProjects(json.query(query, null));
    }

    @GetMapping("/observations/{id}")
    ObjectNode observation(@PathVariable String id) throws SQLException {
        return observations.read(id);
    }

    @PostMapping("/observations")
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode createObservation(HttpServletRequest request) throws SQLException, IOException {
        return observations.create(json.read(request));
    }

    @PatchMapping("/observations/{id}")
    ObjectNode editObservation(@PathVariable String id, HttpServletRequest request) throws SQLException, IOException {
        return observations.patch(id, json.read(request));
    }

    @DeleteMapping("/observations/{id}")
    Map<String, Boolean> deleteObservation(@PathVariable String id, HttpServletRequest request) throws SQLException, IOException {
        observations.delete(id, json.read(request));
        return Map.of("ok", true);
    }

    @GetMapping("/reviews/{id}")
    ObjectNode review(@PathVariable String id) throws SQLException {
        return reviews.read(id);
    }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode createReview(HttpServletRequest request) throws SQLException, IOException {
        return reviews.create(json.read(request));
    }

    @PatchMapping("/reviews/{id}")
    ObjectNode editReview(@PathVariable String id, HttpServletRequest request) throws SQLException, IOException {
        return reviews.patch(id, json.read(request));
    }

    @DeleteMapping("/reviews/{id}")
    Map<String, Boolean> deleteReview(@PathVariable String id, HttpServletRequest request) throws SQLException, IOException {
        reviews.delete(id, json.read(request));
        return Map.of("ok", true);
    }

    @GetMapping("/projects/{id}/latest-review")
    JsonNode latestReview(@PathVariable String id) throws SQLException {
        try (var connection = store.open()) {
            ProjectRow.require(connection, id);
        }
        var review = reviews.latestProject(id);
        return review == null ? NullNode.getInstance() : review;
    }

    @GetMapping("/reviews/{id}/related")
    ObjectNode related(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) throws SQLException {
        return reviews.related(id, json.query(query, null));
    }

    @GetMapping("/reviews/{id}/history")
    ObjectNode reviewHistory(@PathVariable String id, @RequestParam MultiValueMap<String, String> query) throws SQLException {
        return history.forReview(reviews.read(id), json.query(query, null));
    }
}
