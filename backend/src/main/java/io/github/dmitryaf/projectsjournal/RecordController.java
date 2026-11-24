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
@RequestMapping("/api")
final class RecordController {

    private final JournalRecords records;
    private final ApiJson json;

    RecordController(JournalRecords records, ApiJson json) {
        this.records = records;
        this.json = json;
    }

    @PostMapping("/{kind:ideas|decisions}")
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode create(@PathVariable String kind, HttpServletRequest request) throws IOException, SQLException {
        return records.save(kind, null, json.read(request));
    }

    @PutMapping("/{kind:ideas|decisions}/{id}")
    ObjectNode update(@PathVariable String kind, @PathVariable String id, HttpServletRequest request)
            throws IOException, SQLException {
        return records.save(kind, id, json.read(request));
    }

    @DeleteMapping("/{kind:ideas|decisions}/{id}")
    Map<String, Boolean> delete(@PathVariable String kind, @PathVariable String id, HttpServletRequest request)
            throws IOException, SQLException {
        json.read(request);
        records.delete(kind, id);
        return Map.of("ok", true);
    }

    @PostMapping("/relations")
    @ResponseStatus(HttpStatus.CREATED)
    ObjectNode relate(HttpServletRequest request) throws IOException, SQLException {
        return records.relate(json.read(request));
    }

    @DeleteMapping("/relations/{id}")
    Map<String, Boolean> removeRelation(@PathVariable String id, HttpServletRequest request)
            throws IOException, SQLException {
        json.read(request);
        records.removeRelation(id);
        return Map.of("ok", true);
    }
}
