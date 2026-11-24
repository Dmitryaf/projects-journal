package io.github.dmitryaf.controlcenter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.api.lowlevel.Present;
import org.snakeyaml.engine.v2.api.lowlevel.Serialize;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class YamlMetadata {

    private static final int MAX_SIZE = 256 * 1024;
    private static final LoadSettings LOAD = LoadSettings.builder()
            .setSchema(new CoreSchema()).setAllowDuplicateKeys(false).setParseComments(true)
            .setMaxAliasesForCollections(20).setCodePointLimit(MAX_SIZE).build();
    private static final DumpSettings DUMP = DumpSettings.builder()
            .setSchema(new CoreSchema()).setDumpComments(true).setBestLineBreak("\n").build();
    private static final List<String> FIELDS = List.of("name", "type", "status", "stage", "priority", "goal",
            "current_focus", "next", "blocked_by", "related", "last_reviewed");
    private static final Set<String> TYPES = Set.of("product", "tool", "experiment", "other");
    private static final Set<String> STATUSES = Set.of("active", "paused", "completed", "unknown");
    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high");

    private final JsonMapper mapper;

    YamlMetadata(JsonMapper mapper) {
        this.mapper = mapper;
    }

    static Object load(String content) {
        return new Load(LOAD).loadFromString(content);
    }

    ObjectNode read(Path directory) {
        var snapshot = mapper.createObjectNode().put("exists", false)
                .putNull("hash").putNull("metadata").putNull("error");
        try {
            String content = ProjectFiles.readSmallFile(directory.resolve("PROJECT.yaml"));
            if (content == null) {
                return snapshot;
            }
            snapshot.put("exists", true).put("hash", hash(content));
            Object data = load(content);
            if (!(data instanceof Map<?, ?> fields)) {
                throw new ApiFailure(400, "Проверьте поля PROJECT.yaml: ");
            }
            var input = mapper.createObjectNode();
            for (String field : FIELDS) {
                if (fields.containsKey(field)) {
                    copyField(input, field, fields.get(field));
                }
            }
            snapshot.set("metadata", validate(input, directory.getFileName().toString()));
        } catch (ApiFailure invalid) {
            snapshot.put("exists", true).put("error", invalid.getMessage().replace("Проверьте поля:",
                    "Проверьте поля PROJECT.yaml:"));
        } catch (IOException unreadable) {
            snapshot.put("exists", true).put("error", unreadable.getMessage());
        } catch (RuntimeException invalid) {
            snapshot.put("exists", true).put("error", "Некорректный YAML.");
        }
        return snapshot;
    }

    private static void copyField(ObjectNode input, String field, Object value) {
        // Inspect only metadata fields so unknown YAML values can stay opaque and round-trip untouched.
        switch (value) {
            case null -> input.putNull(field);
            case String text -> input.put(field, text);
            case List<?> items -> {
                var list = input.putArray(field);
                for (Object item : items.subList(0, Math.min(items.size(), 101))) {
                    if (item instanceof String text) {
                        list.add(text);
                    } else {
                        list.addNull();
                    }
                }
            }
            default -> input.put(field, false);
        }
    }

    ObjectNode validate(JsonNode input, String defaultName) {
        if (input == null || !input.isObject()) {
            throw new ApiFailure(400, "Проверьте поля: ");
        }
        var result = mapper.createObjectNode();
        var invalid = new ArrayList<String>();
        result.put("name", text(input, "name", defaultName == null ? "Проект" : defaultName, 1, 240, invalid));
        result.put("type", choice(input, "type", "other", TYPES, invalid));
        result.put("status", choice(input, "status", "unknown", STATUSES, invalid));
        result.put("stage", text(input, "stage", "", 0, 120, invalid));
        result.put("priority", choice(input, "priority", "normal", PRIORITIES, invalid));
        result.put("goal", text(input, "goal", "", 0, 10_000, invalid));
        result.put("current_focus", text(input, "current_focus", "", 0, 10_000, invalid));
        for (String field : List.of("next", "blocked_by", "related")) {
            var out = result.putArray(field);
            var values = input.get(field);
            if (values == null) {
                continue;
            }
            if (!values.isArray() || values.size() > 100) {
                invalid.add(field);
                continue;
            }
            for (int index = 0; index < values.size(); index++) {
                String value = values.get(index).isString() ? Input.trim(values.get(index).asString()) : "";
                if (value.isEmpty() || value.length() > 240) {
                    invalid.add(field + "." + index);
                }
                out.add(value);
            }
        }
        var date = input.get("last_reviewed");
        if (date == null || date.isNull()) {
            result.putNull("last_reviewed");
        } else if (date.isString() && validDate(date.asString())) {
            result.put("last_reviewed", date.asString());
        } else {
            invalid.add("last_reviewed");
        }
        if (!invalid.isEmpty()) {
            throw new ApiFailure(400, "Проверьте поля: " + String.join(", ", invalid));
        }
        return result;
    }

    private static boolean validDate(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }

    private static String text(JsonNode input, String field, String fallback, int minimum, int maximum,
            List<String> invalid) {
        var value = input.get(field);
        String text = value == null ? fallback : value.isString() ? Input.trim(value.asString()) : null;
        if (text == null || text.length() < minimum || text.length() > maximum) {
            invalid.add(field);
        }
        return text;
    }

    private static String choice(JsonNode input, String field, String fallback, Set<String> accepted,
            List<String> invalid) {
        var value = input.get(field);
        if (value == null) {
            return fallback;
        }
        if (!value.isString() || !accepted.contains(value.asString())) {
            invalid.add(field);
            return fallback;
        }
        return value.asString();
    }

    void export(Path directory, JsonNode metadata, String expectedHash) throws IOException {
        Path file = directory.resolve("PROJECT.yaml");
        String contents = ProjectFiles.readSmallFile(file);
        if (!Objects.equals(contents == null ? null : hash(contents), expectedHash)) {
            throw new ApiFailure(409, "PROJECT.yaml изменился. Обновите страницу перед экспортом.");
        }
        MappingNode document = parseDocument(contents);
        var updates = validate(metadata, null);
        for (String field : FIELDS) {
            replace(document, field, yamlValue(updates.get(field)));
        }
        var events = new Serialize(DUMP).serializeOne(document);
        String exported = new Present(DUMP).emitToString(events.iterator());
        Path temp = Files.createTempFile(directory, ".PROJECT.", ".tmp");
        try {
            Files.writeString(temp, exported, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            if (!Objects.equals(contents, ProjectFiles.readSmallFile(file))) {
                throw new ApiFailure(409, "PROJECT.yaml изменился во время экспорта. Повторите после обновления.");
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static MappingNode parseDocument(String contents) {
        if (contents == null) {
            return new MappingNode(Tag.MAP, new ArrayList<>(), FlowStyle.BLOCK);
        }
        try {
            if (load(contents) instanceof Map<?, ?>
                    && new Compose(LOAD).composeString(contents).orElse(null) instanceof MappingNode document) {
                return document;
            }
        } catch (RuntimeException invalid) {
            // The original file stays in place when it cannot be safely edited.
        }
        throw new ApiFailure(409, "Исправьте PROJECT.yaml перед экспортом.");
    }

    private static void replace(MappingNode document, String field, Node value) {
        var entries = document.getValue();
        for (int i = 0; i < entries.size(); i++) {
            var tuple = entries.get(i);
            if (tuple.getKeyNode() instanceof ScalarNode key && Tag.STR.equals(key.getTag())
                    && field.equals(key.getValue())) {
                Node original = tuple.getValueNode();
                value.setInLineComments(original.getInLineComments());
                value.setBlockComments(original.getBlockComments());
                value.setEndComments(original.getEndComments());
                entries.set(i, new NodeTuple(key, value));
                return;
            }
        }
        entries.add(new NodeTuple(yamlString(field), value));
    }

    private static Node yamlValue(JsonNode value) {
        if (value.isArray()) {
            var list = new ArrayList<Node>();
            for (JsonNode item : value) {
                list.add(yamlString(item.asString()));
            }
            return new SequenceNode(Tag.SEQ, list, FlowStyle.BLOCK);
        }
        return value.isNull() ? new ScalarNode(Tag.NULL, "null", ScalarStyle.PLAIN) : yamlString(value.asString());
    }

    private static ScalarNode yamlString(String value) {
        return new ScalarNode(Tag.STR, value, ScalarStyle.PLAIN);
    }

    private static String hash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
