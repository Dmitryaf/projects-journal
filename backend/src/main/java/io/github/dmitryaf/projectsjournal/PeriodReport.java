package io.github.dmitryaf.projectsjournal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class PeriodReport {

    private static final String SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {
                "narrative": { "type": "string", "minLength": 1, "maxLength": 20000 },
                "findings": {
                  "type": "array", "maxItems": 20, "items": {
                    "type": "object",
                    "properties": {
                      "statement": { "type": "string", "minLength": 1, "maxLength": 2000 },
                      "sources": { "type": "array", "minItems": 1, "maxItems": 10,
                        "items": { "type": "string", "minLength": 1, "maxLength": 150 } }
                    },
                    "required": ["statement", "sources"], "additionalProperties": false
                  }
                },
                "uncertainties": { "type": "array", "maxItems": 12,
                  "items": { "type": "string", "minLength": 1, "maxLength": 2000 } },
                "proposals": {
                  "type": "array", "maxItems": 8, "items": {
                    "type": "object",
                    "properties": {
                      "kind": { "type": "string", "enum": ["improvement", "connection", "idea"] },
                      "projectIds": { "type": "array", "maxItems": 2, "items": {
                        "type": "string", "format": "uuid",
                        "pattern": "^([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}|00000000-0000-0000-0000-000000000000|ffffffff-ffff-ffff-ffff-ffffffffffff)$"
                      } },
                      "title": { "type": "string", "minLength": 1, "maxLength": 240 },
                      "description": { "type": "string", "maxLength": 5000 },
                      "reason": { "type": "string", "minLength": 1, "maxLength": 3000 },
                      "uncertainty": { "type": "string", "maxLength": 3000 },
                      "cheapCheck": { "type": "string", "minLength": 1, "maxLength": 3000 },
                      "sources": { "type": "array", "minItems": 1, "maxItems": 10,
                        "items": { "type": "string", "minLength": 1, "maxLength": 150 } }
                    },
                    "required": ["kind", "projectIds", "title", "description", "reason", "uncertainty", "cheapCheck", "sources"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["narrative", "findings", "uncertainties", "proposals"], "additionalProperties": false
            }
            """;

    private final JsonMapper mapper;
    private final AnalysisPrivacy privacy;
    private final ObjectNode schema;

    PeriodReport(JsonMapper mapper, AnalysisPrivacy privacy) {
        this.mapper = mapper;
        this.privacy = privacy;
        this.schema = mapper.readTree(SCHEMA).asObject();
    }

    ObjectNode schema() {
        return schema.deepCopy();
    }

    ObjectNode normalize(JsonNode report) {
        // Parse only this DTO's required objects, primitive strings and bounded arrays.
        // Iterate schema properties to preserve field order in stored hashes.
        return normalize(report, schema, "report").asObject();
    }

    private JsonNode normalize(JsonNode value, JsonNode rule, String field) {
        return switch (rule.path("type").asString()) {
            case "object" -> object(value, rule.path("properties"), field);
            case "array" -> array(value, rule, field);
            case "string" -> string(value, rule, field);
            default -> throw new IllegalStateException("Неизвестный тип поля отчёта");
        };
    }

    private ObjectNode object(JsonNode value, JsonNode properties, String field) {
        if (value == null || !value.isObject() || !properties.propertyNames().containsAll(value.propertyNames())) {
            throw ApiJson.invalid(field);
        }
        var result = mapper.createObjectNode();
        for (var property : properties.properties()) {
            String name = property.getKey();
            result.set(name, normalize(value.get(name), property.getValue(), field + "." + name));
        }
        return result;
    }

    private JsonNode array(JsonNode value, JsonNode rule, String field) {
        if (value == null || !value.isArray() || value.size() < rule.path("minItems").asInt(0)
                || value.size() > rule.path("maxItems").asInt()) {
            throw ApiJson.invalid(field);
        }
        var result = mapper.createArrayNode();
        for (int index = 0; index < value.size(); index++) {
            result.add(normalize(value.get(index), rule.path("items"), field + "." + index));
        }
        return result;
    }

    private static JsonNode string(JsonNode value, JsonNode rule, String field) {
        if (rule.has("enum")) {
            for (JsonNode candidate : rule.path("enum")) {
                if (candidate.equals(value)) {
                    return value;
                }
            }
            throw ApiJson.invalid(field);
        }
        if ("uuid".equals(rule.path("format").asString())) {
            TaskPayload.uuid(value, field, false);
        } else {
            ApiJson.text(value, field, rule.path("minLength").asInt(0), rule.path("maxLength").asInt(), false);
        }
        return value;
    }

    void validate(JsonNode payload, JsonNode report) {
        if (privacy.containsSensitive(report)) {
            throw new TaskError(400, "Ответ содержит возможный секрет или локальный путь и отклонён.");
        }
        Map<String, JsonNode> sources = new HashMap<>();
        payload.path("sources").forEach(source -> sources.put(source.path("id").asString(), source));
        for (JsonNode finding : report.path("findings")) {
            for (JsonNode id : finding.path("sources")) {
                if (!sources.containsKey(id.asString())) {
                    throw new TaskError(400, "Ответ ссылается на источник вне утверждённого пакета.");
                }
            }
        }
        Set<String> selected = new HashSet<>();
        payload.path("scope").path("projectIds").forEach(project -> selected.add(project.asString()));
        for (JsonNode proposal : report.path("proposals")) {
            if (!allowedProposal(proposal, selected, sources)) {
                throw new TaskError(400, "Предложение ссылается на чужой проект или источник.");
            }
        }
    }

    private static boolean allowedProposal(JsonNode proposal, Set<String> selected, Map<String, JsonNode> sources) {
        Set<String> projects = new HashSet<>();
        for (JsonNode id : proposal.path("projectIds")) {
            if (!selected.contains(id.asString()) || !projects.add(id.asString())) {
                return false;
            }
        }
        String kind = proposal.path("kind").asString();
        if ("connection".equals(kind) && projects.size() != 2 || "improvement".equals(kind) && projects.size() != 1) {
            return false;
        }
        for (JsonNode id : proposal.path("sources")) {
            JsonNode source = sources.get(id.asString());
            if (source == null || !"idea".equals(kind) && !sameScope(source, projects)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameScope(JsonNode source, Set<String> projects) {
        JsonNode ids = source.path("projectIds");
        if (ids.isArray()) {
            if (ids.isEmpty()) {
                return true;
            }
            for (JsonNode id : ids) {
                if (projects.contains(id.asString())) {
                    return true;
                }
            }
            return false;
        }
        String id = source.path("projectId").asString(null);
        return id == null || id.isEmpty() || projects.contains(id);
    }
}
