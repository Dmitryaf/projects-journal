package io.github.dmitryaf.projectsjournal;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class JournalSettings {

    private static final String DEFAULT_ZONE = "Asia/Yekaterinburg";
    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();

    private final SqliteStore store;
    private final JsonMapper mapper;

    JournalSettings(SqliteStore store, JsonMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    ObjectNode read() throws SQLException {
        try (var connection = store.open()) {
            return read(connection);
        }
    }

    ObjectNode read(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT data FROM settings WHERE id=1")) {
            return rows.next() ? parse(mapper.readTree(rows.getString(1))) : defaults();
        }
    }

    ObjectNode update(JsonNode input) throws SQLException {
        return store.write(connection -> {
            var next = parse(input);
            var current = read(connection);
            for (var name : List.of("excludedProjectPaths", "autoRefreshMinutes", "timezone")) {
                if (!input.has(name)) {
                    next.set(name, current.get(name));
                }
            }
            next.set("roots", absolutePaths(next.get("roots")));
            next.set("excludedProjectPaths", absolutePaths(next.get("excludedProjectPaths")));
            save(connection, next);
            return next;
        });
    }

    void save(Connection connection, JsonNode settings) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO settings VALUES(1,?) ON CONFLICT(id) DO UPDATE SET data=excluded.data
                """)) {
            statement.setString(1, mapper.writeValueAsString(settings));
            statement.executeUpdate();
        }
    }

    static List<String> strings(JsonNode array) {
        List<String> strings = new ArrayList<>();
        array.forEach(item -> strings.add(item.stringValue()));
        return strings;
    }

    private ObjectNode defaults() {
        var defaults = mapper.createObjectNode();
        defaults.putArray("roots");
        var exclusions = defaults.putArray("exclusions");
        List.of("node_modules", "dist", "build", "archive", ".git", ".venv", "vendor")
                .forEach(exclusions::add);
        defaults.put("inactivityDays", 14).put("reviewDays", 30);
        return parse(defaults);
    }

    private ObjectNode parse(JsonNode input) {
        ApiJson.requireObject(input);
        var result = mapper.createObjectNode();
        result.set("roots", stringArray(input, "roots", 30, 2000, false));
        result.set("exclusions", stringArray(input, "exclusions", 100, 100, false));
        result.set("excludedProjectPaths", stringArray(input, "excludedProjectPaths", Integer.MAX_VALUE, 2000, true));
        result.put("inactivityDays", ApiJson.integer(input.get("inactivityDays"), "inactivityDays", 1, 3650));
        result.put("reviewDays", ApiJson.integer(input.get("reviewDays"), "reviewDays", 1, 3650));
        result.put("autoRefreshMinutes", number(input, "autoRefreshMinutes", 0, 60, 1));
        var timezone = input.has("timezone") ? ApiJson.text(input.get("timezone"), "timezone", 1, 100, false) : DEFAULT_ZONE;
        if (!validZone(timezone)) {
            throw ApiJson.invalid("timezone");
        }
        result.put("timezone", timezone);
        long info = number(input, "movementInfoDays", 1, 3650, 3);
        long attention = number(input, "movementAttentionDays", 1, 3650, 7);
        long decision = number(input, "movementDecisionDays", 1, 3650, 14);
        if (info >= attention || attention >= decision) {
            throw ApiJson.invalid("movementAttentionDays");
        }
        return result.put("movementInfoDays", info).put("movementAttentionDays", attention)
                .put("movementDecisionDays", decision);
    }

    private static boolean validZone(String timezone) {
        return timezone.equalsIgnoreCase("UTC") || ZONES.stream().anyMatch(timezone::equalsIgnoreCase);
    }

    private static long number(JsonNode source, String field, int min, int max, int fallback) {
        return source.has(field) ? ApiJson.integer(source.get(field), field, min, max) : fallback;
    }

    private ArrayNode stringArray(JsonNode input, String field, int limit, int maxLength, boolean optional) {
        var result = mapper.createArrayNode();
        var value = input.get(field);
        if (value == null && optional) {
            return result;
        }
        if (value == null || !value.isArray() || value.size() > limit) {
            throw ApiJson.invalid(field);
        }
        for (int index = 0; index < value.size(); index++) {
            result.add(ApiJson.text(value.get(index), field + "." + index, 1, maxLength, true));
        }
        return result;
    }

    private ArrayNode absolutePaths(JsonNode array) {
        Set<String> paths = new LinkedHashSet<>();
        for (var item : array) {
            try {
                var path = Path.of(item.stringValue());
                if (!path.isAbsolute()) {
                    throw new TaskError(400, "Укажите абсолютные пути каталогов.");
                }
                paths.add(path.normalize().toString());
            } catch (InvalidPathException invalid) {
                throw new TaskError(400, "Укажите абсолютные пути каталогов.");
            }
        }
        var result = mapper.createArrayNode();
        paths.forEach(result::add);
        return result;
    }
}
