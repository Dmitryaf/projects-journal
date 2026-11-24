package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

@RestController
@RequestMapping("/api/checks")
final class CheckController {

    private final CheckService checks;
    private final ApiJson json;

    CheckController(CheckService checks, ApiJson json) {
        this.checks = checks;
        this.json = json;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode create(HttpServletRequest request) throws IOException, SQLException {
        return save(null, request);
    }

    @PutMapping("/{id}")
    ObjectNode update(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return save(id, request);
    }

    @PostMapping("/{id}/complete")
    ObjectNode complete(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return checks.complete(id, json.read(request));
    }

    @PostMapping("/{id}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode createEntry(@PathVariable String id, HttpServletRequest request) throws IOException, SQLException {
        return checks.saveEntry(id, null, json.read(request));
    }

    @PutMapping("/{id}/entries/{entryId}")
    ObjectNode updateEntry(@PathVariable String id, @PathVariable String entryId, HttpServletRequest request)
            throws IOException, SQLException {
        return checks.saveEntry(id, entryId, json.read(request));
    }

    @DeleteMapping("/{id}/entries/{entryId}")
    Map<String, Boolean> deleteEntry(@PathVariable String id, @PathVariable String entryId, HttpServletRequest request)
            throws IOException, SQLException {
        json.read(request);
        checks.deleteEntry(id, entryId);
        return Map.of("ok", true);
    }

    private ObjectNode save(String id, HttpServletRequest request) throws IOException, SQLException {
        var input = json.read(request);
        ApiJson.requireObject(input);
        var parallel = input.get("allowParallel");
        if (parallel != null && !parallel.isBoolean()) {
            throw ApiJson.invalid("allowParallel");
        }
        return checks.save(id, input.get("check"), parallel != null && parallel.booleanValue());
    }
}
