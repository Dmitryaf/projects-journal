package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Collator;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class FileDecisionIndex {

    static final String PRIVATE = "private_context";
    private static final String REPOSITORY = "repository";
    private static final int MAX_FILES = 200;
    private static final Pattern HEADING = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern FRONT_MATTER = Pattern.compile("^---\\r?\\n(.*?)\\r?\\n---(?:\\r?\\n|$)(.*)$", Pattern.DOTALL);
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern ID = Pattern.compile("D-[0-9]{4,}");
    private static final Pattern AREA = Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)*");
    private static final Set<String> STATUS = Set.of("proposed", "accepted", "rejected", "superseded");
    private static final Set<String> IMPLEMENTATION = Set.of("unknown", "not_implemented", "partial", "implemented");
    private static final Set<String> VISIBILITY = Set.of("repository", "private");

    private final JsonMapper mapper;
    FileDecisionIndex(JsonMapper mapper) {
        this.mapper = mapper;
    }

    record Snapshot(String privateStatus, boolean hasPrivateMap, boolean hasPrivateRecords, List<ObjectNode> entries) {
    }

    Snapshot scan(ProjectRow row, List<String> roots) {
        List<ObjectNode> records = new ArrayList<>();
        if (row.available()) {
            try {
                var root = Path.of(row.path());
                ProjectFiles.assertProjectAccess(root, roots);
                scanSource(row.id(), root, roots, REPOSITORY, records);
            } catch (IOException | RuntimeException exception) {
                records.add(error(row.id(), row.path(), REPOSITORY, "Не удалось прочитать решения проекта."));
            }
        } else {
            records.add(error(row.id(), row.path(), REPOSITORY, "Проект недоступен: канонические решения не прочитаны."));
        }
        String privateStatus = row.privateContextPath() == null ? "disconnected" : "unavailable";
        boolean hasPrivateMap = false;
        boolean hasPrivateRecords = false;
        if (row.privateContextPath() != null) {
            var privateRecords = new ArrayList<ObjectNode>();
            try {
                var root = Path.of(row.privateContextPath());
                var privateRoots = List.of(root.getRoot().toString());
                ProjectFiles.assertProjectAccess(root, privateRoots);
                // Opening the directory distinguishes an unreadable source from an empty one.
                try (var ignored = Files.newDirectoryStream(root)) {
                    scanSource(row.id(), root, privateRoots, PRIVATE, privateRecords);
                }
                hasPrivateMap = isPrivateMap(root, privateRoots);
                hasPrivateRecords = hasPrivateMap || privateRecords.stream().anyMatch(record -> record.path("error").isNull());
                records.addAll(privateRecords);
                privateStatus = "connected";
            } catch (IOException | RuntimeException ignored) {
                // A lost context contributes its status and persisted history flag only.
            }
        }
        markConflicts(records);
        return new Snapshot(privateStatus, hasPrivateMap, hasPrivateRecords, List.copyOf(records));
    }

    private boolean isPrivateMap(Path root, List<String> roots) throws IOException {
        var map = root.resolve("PROJECT_MAP.md");
        try {
            if (!Files.readAttributes(map, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                return false;
            }
            ProjectFiles.assertProjectAccess(map, roots);
            return true;
        } catch (NoSuchFileException ignored) {
            return false;
        }
    }

    private void scanSource(String projectId, Path root, List<String> roots, String source,
                            List<ObjectNode> records) throws IOException {
        int before = records.size();
        scanDirectory(projectId, root.resolve("decisions"), roots, source, records);
        var map = root.resolve("DECISIONS.md");
        var text = read(map, roots);
        if (text != null && (records.size() == before || withoutBom(text).startsWith("---"))) {
            records.add(parse(text, projectId, map, source).put("body", ""));
        }
    }

    private void scanDirectory(String projectId, Path directory, List<String> roots, String source,
                               List<ObjectNode> records) {
        try {
            ProjectFiles.assertProjectAccess(directory, roots);
            try (var children = Files.list(directory)) {
                var decisions = children.filter(file -> file.getFileName().toString().endsWith(".md"))
                        .filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        .sorted(Comparator.comparing(file -> file.getFileName().toString(), Collator.getInstance()))
                        .limit(MAX_FILES + 1L).toList();
                for (Path file : decisions.subList(0, Math.min(MAX_FILES, decisions.size()))) {
                    try {
                        var text = read(file, roots);
                        if (text != null) {
                            records.add(parse(text, projectId, file, source).put("body", ""));
                        }
                    } catch (IOException | RuntimeException exception) {
                        records.add(error(projectId, file.toString(), source,
                                "Не удалось прочитать решение: проверьте доступ, размер файла и ссылки."));
                    }
                }
                if (decisions.size() > MAX_FILES) {
                    records.add(error(projectId, directory.toString(), source,
                            "Больше 200 файлов решений. Укажите более узкий источник."));
                }
            }
        } catch (NoSuchFileException ignored) {
            // The conventional decisions directory is optional.
        } catch (IOException | RuntimeException exception) {
            records.add(error(projectId, directory.toString(), source, "Каталог решений недоступен или является ссылкой."));
        }
    }

    String body(ObjectNode entry, List<String> roots) throws IOException {
        var file = Path.of(entry.path("sourcePath").asString());
        var text = read(file, roots);
        return text == null ? null : parse(text, entry.path("projectId").asString(), file,
                entry.path("source").asString()).path("body").asString();
    }

    private String read(Path file, List<String> roots) throws IOException {
        if (Files.isSymbolicLink(file)) {
            throw new IOException("Ожидался обычный файл, не ссылка.");
        }
        try {
            ProjectFiles.assertProjectAccess(file, roots);
            return ProjectFiles.readSmallFile(file);
        } catch (NoSuchFileException ignored) {
            return null;
        }
    }

    private ObjectNode parse(String content, String projectId, Path file, String source) {
        var heading = HEADING.matcher(content);
        var record = record(projectId, file.toString(), source, heading.find() ? heading.group(1) : file.getFileName().toString());
        record.put("body", content).put("hasContent", true);
        var text = withoutBom(content);
        if (!text.startsWith("---")) {
            return record;
        }
        var frontMatter = FRONT_MATTER.matcher(text);
        if (!frontMatter.matches()) {
            return record.put("error", "Некорректный front matter.");
        }
        Object parsed;
        try {
            parsed = ProjectFiles.loadYaml(frontMatter.group(1));
        } catch (RuntimeException exception) {
            return record.put("error", "Некорректный YAML.");
        }
        try {
            var metadata = metadata(parsed);
            record.set("metadata", metadata);
            record.put("title", metadata.path("title").asString());
            return record.put("body", TaskPayload.trim(frontMatter.group(2)));
        } catch (TaskError error) {
            return record.put("error", error.getMessage());
        }
    }

    private ObjectNode metadata(Object parsed) {
        if (!(parsed instanceof Map<?, ?> data)) {
            throw field("");
        }
        if (!(data.get("schema_version") instanceof Number version) || version.doubleValue() != 1) {
            throw field("schema_version");
        }
        var id = string(data, "id");
        if (!ID.matcher(id).matches()) {
            throw field("id");
        }
        var title = TaskPayload.trim(string(data, "title"));
        if (title.isEmpty() || title.length() > 240) {
            throw field("title");
        }
        var metadata = mapper.createObjectNode().put("schema_version", 1).put("id", id).put("title", title)
                .put("date", date(data, "date")).put("status", choice(data, "status", STATUS));
        var area = string(data, "area");
        if (!AREA.matcher(area).matches()) {
            throw field("area");
        }
        metadata.put("area", area).put("implementation", choice(data, "implementation", IMPLEMENTATION));
        if (data.containsKey("review_after")) {
            metadata.put("review_after", date(data, "review_after"));
        }
        var supersedes = metadata.putArray("supersedes");
        if (data.containsKey("supersedes")) {
            if (!(data.get("supersedes") instanceof List<?> values)) {
                throw field("supersedes");
            }
            for (int index = 0; index < values.size(); index++) {
                if (!(values.get(index) instanceof String value)) {
                    throw field("supersedes." + index);
                }
                supersedes.add(value);
            }
        }
        if (data.get("superseded_by") == null) {
            metadata.putNull("superseded_by");
        } else {
            metadata.put("superseded_by", string(data, "superseded_by"));
        }
        return metadata.put("visibility", choice(data, "visibility", VISIBILITY));
    }

    private static String date(Map<?, ?> data, String field) {
        var value = string(data, field);
        if (DATE.matcher(value).matches()) {
            try {
                LocalDate.parse(value);
                return value;
            } catch (DateTimeParseException ignored) {
                // Invalid calendar dates are reported as field errors.
            }
        }
        throw field(field);
    }

    private static String choice(Map<?, ?> data, String field, Set<String> allowed) {
        var value = string(data, field);
        if (!allowed.contains(value)) {
            throw field(field);
        }
        return value;
    }

    private static String string(Map<?, ?> data, String field) {
        if (data.get(field) instanceof String value) {
            return value;
        }
        throw field(field);
    }

    private static TaskError field(String field) {
        return new TaskError(400, "Проверьте поля: " + field);
    }

    private static String withoutBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    private ObjectNode record(String projectId, String path, String source, String title) {
        var record = mapper.createObjectNode().put("key", projectId + ":" + path).put("projectId", projectId)
                .put("sourcePath", path).put("source", source).putNull("metadata").put("title", title)
                .put("body", "").putNull("error");
        record.putArray("signals");
        return record;
    }

    private ObjectNode error(String projectId, String path, String source, String message) {
        var name = Path.of(path).getFileName();
        return record(projectId, path, source, name == null ? "" : name.toString()).put("error", message);
    }

    private static void markConflicts(List<ObjectNode> records) {
        var counts = new HashMap<String, Integer>();
        for (var record : records) {
            var id = decisionId(record);
            if (id != null) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        for (var record : records) {
            var id = decisionId(record);
            if (id != null && counts.get(id) > 1) {
                record.put("error", "Конфликт ID решений: " + id
                        + ". ID повторяется в источниках. Выберите каноническую запись вне приложения.");
            }
        }
    }

    private static String decisionId(ObjectNode record) {
        return record.path("metadata").path("id").stringValue(null);
    }

    ObjectNode view(ObjectNode record, LocalDate today) {
        var copy = record.deepCopy();
        JsonNode data = copy.path("metadata");
        var status = data.path("status").stringValue(null);
        if (!copy.path("error").isNull() || !data.isObject() || "superseded".equals(status) || "rejected".equals(status)) {
            return copy;
        }
        var signals = copy.putArray("signals");
        var id = data.path("id").asString();
        var review = data.path("review_after").stringValue(null);
        if (review != null && review.compareTo(today.toString()) <= 0) {
            signals.addObject().put("code", "decision-review").put("level", "attention")
                    .put("message", "Пора пересмотреть решение " + id);
        }
        if ("accepted".equals(status) && "not_implemented".equals(data.path("implementation").asString())) {
            signals.addObject().put("code", "not-implemented").put("level", "info")
                    .put("message", id + ": принято, но не реализовано");
        }
        return copy;
    }
}
