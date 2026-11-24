package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class CodexRepositoryContext {

    private static final int MAX_FILE_BYTES = 8192;
    private static final int MAX_LISTED_FILES = 200;
    private static final Set<String> EXTENSIONS = Set.of(".md", ".txt", ".json", ".ts", ".tsx", ".js", ".jsx",
            ".vue", ".css", ".html", ".yml", ".yaml");
    private static final Pattern EXCLUDED = Pattern.compile(
            "(?:^|/)(?:node_modules|vendor|dist|build|coverage|package-lock\\.json|.*(?:secret|credential|password|\\.key|\\.pem).*)$",
            Pattern.CASE_INSENSITIVE);

    private final SqliteStore store;
    private final JournalSettings settings;
    private final ProjectFiles projectFiles;
    private final AnalysisPrivacy privacy;
    private final JsonMapper mapper;

    CodexRepositoryContext(SqliteStore store, JournalSettings settings, ProjectFiles projectFiles,
                           AnalysisPrivacy privacy, JsonMapper mapper) {
        this.store = store;
        this.settings = settings;
        this.projectFiles = projectFiles;
        this.privacy = privacy;
        this.mapper = mapper;
    }

    ObjectNode files(List<String> ids) throws SQLException, IOException {
        var result = mapper.createObjectNode();
        var projects = result.putArray("projects");
        try (var connection = store.open()) {
            var roots = JournalSettings.strings(settings.read(connection).path("roots"));
            for (String id : new LinkedHashSet<>(ids)) {
                var row = accessibleProject(connection, id, roots);
                var name = row.metadata() == null ? "" : mapper.readTree(row.metadata()).path("name").asString("");
                if (name.isEmpty()) {
                    name = mapper.readTree(row.snapshot()).path("directoryName").asString();
                }
                var tracked = projectFiles.trackedFiles(row.path()).stream()
                        .filter(CodexRepositoryContext::permittedFile).sorted().toList();
                var project = projects.addObject().put("id", id).put("name", name);
                var names = project.putArray("files");
                tracked.stream().limit(MAX_LISTED_FILES).forEach(names::add);
                project.put("truncated", tracked.size() > MAX_LISTED_FILES);
            }
        }
        return result;
    }

    Selected sources(Connection connection, List<String> ids, JsonNode files) throws SQLException, IOException {
        var sources = mapper.createArrayNode();
        var stamps = mapper.createArrayNode();
        if (files.isEmpty()) {
            return new Selected(sources, stamps);
        }
        var roots = JournalSettings.strings(settings.read(connection).path("roots"));
        var repositories = new HashMap<String, Repository>();
        var selectedIds = new HashSet<String>();
        for (var file : files) {
            var projectId = file.get("projectId").stringValue();
            var name = file.get("path").stringValue();
            if (!ids.contains(projectId) || !permittedFile(name)) {
                throw new TaskError(403, "Файл вне выбранных проектов или имеет запрещённый путь/тип.");
            }
            var repository = repositories.get(projectId);
            if (repository == null) {
                var row = accessibleProject(connection, projectId, roots);
                repository = new Repository(row, Set.copyOf(projectFiles.trackedFiles(row.path())));
                repositories.put(projectId, repository);
            }
            if (!repository.tracked().contains(name)) {
                throw new TaskError(403, "Можно включать только явно выбранные tracked-файлы проекта.");
            }
            var sourceId = "repository:" + projectId + ":" + AnalysisPrivacy.digestText(name).substring(0, 16);
            if (!selectedIds.add(sourceId)) {
                throw new TaskError(400, "Файл выбран повторно.");
            }
            var row = repository.row();
            var text = readFile(row.path(), name);
            sources.addObject().put("id", sourceId).put("kind", "repository_file").put("projectId", projectId)
                    .put("title", name).put("text", text).putNull("day")
                    .put("recordedAt", JournalPeriods.utc(Instant.now())).put("dateQuality", "current_file").put("partial", false);
            var access = mapper.createObjectNode().put("root", row.path()).put("visibility", row.visibility()).put("text", text);
            stamps.addObject().put("projectId", projectId).put("path", name).put("stamp", privacy.digest(access));
        }
        return new Selected(sources, stamps);
    }

    private static ProjectRow accessibleProject(Connection connection, String id, List<String> roots) throws SQLException, IOException {
        var row = ProjectRow.find(connection, id);
        if (row == null) {
            throw new TaskError(410, "Выбранный проект удалён.");
        }
        ProjectFiles.assertProjectAccess(Path.of(row.path()), roots);
        return row;
    }

    private static boolean permittedFile(String name) {
        if (name.length() > 240 || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0
                || name.chars().anyMatch(value -> value < 32) || EXCLUDED.matcher(name).find()) {
            return false;
        }
        for (var part : name.split("/", -1)) {
            if (part.isEmpty() || part.startsWith(".")) {
                return false;
            }
        }
        var extension = name.lastIndexOf('.');
        return extension > name.lastIndexOf('/') && EXTENSIONS.contains(name.substring(extension).toLowerCase(Locale.ROOT));
    }

    private static String readFile(String directory, String name) throws IOException {
        var root = Path.of(directory);
        var target = root.resolve(name);
        ProjectFiles.assertProjectAccess(target, List.of(directory));
        var canonical = target.toRealPath();
        var before = attributes(target);
        if (!before.isRegularFile() || before.size() > MAX_FILE_BYTES) {
            throw new TaskError(400, "Выберите текстовый файл до 8 КБ. Большие файлы не обрезаются.");
        }
        byte[] bytes;
        try (var stream = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            bytes = stream.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new TaskError(400, "Файл вырос во время чтения. Подготовьте пакет заново.");
        }
        if (bytes.length != before.size()) {
            throw new TaskError(409, "Файл изменился или прочитан не полностью. Подготовьте пакет заново.");
        }
        ProjectFiles.assertProjectAccess(target, List.of(directory));
        var after = attributes(target);
        if (!after.isRegularFile() || !canonical.equals(target.toRealPath()) || !Objects.equals(before.fileKey(), after.fileKey())
                || !before.lastModifiedTime().equals(after.lastModifiedTime()) || before.size() != after.size()) {
            throw new TaskError(409, "Файл изменился во время чтения. Подготовьте пакет заново.");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new TaskError(400, "Выберите текстовый UTF-8 файл без двоичных данных.");
        }
        if (text.startsWith("\ufeff")) {
            text = text.substring(1);
        }
        if (text.indexOf('\0') >= 0 || AnalysisPrivacy.sensitiveText(text)) {
            throw new TaskError(400, "В выбранном файле возможный секрет, локальный путь или двоичные данные. Исключите его из пакета.");
        }
        return text;
    }

    private static BasicFileAttributes attributes(Path file) throws IOException {
        return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    record Selected(ArrayNode sources, ArrayNode stamps) {
    }

    private record Repository(ProjectRow row, Set<String> tracked) {
    }
}
