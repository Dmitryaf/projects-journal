package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class ProjectContextService {

    private static final Set<String> VISIBILITY = Set.of("unknown", "private", "public");
    private static final Set<String> DOCUMENTS = Set.of("DECISIONS.md", "PROJECT_MAP.md", "PROJECT-MAP.md", "docs/PROJECT_MAP.md");

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final ProjectFiles files;
    private final JournalSettings settings;
    private final FileDecisionIndex decisions;
    private final ConcurrentHashMap<String, CachedContext> cache = new ConcurrentHashMap<>();

    ProjectContextService(SqliteStore store, JsonMapper mapper, ProjectFiles files, JournalSettings settings) {
        this.store = store;
        this.mapper = mapper;
        this.files = files;
        this.settings = settings;
        this.decisions = new FileDecisionIndex(mapper);
    }

    private record CachedContext(ProjectRow row, List<String> roots, FileDecisionIndex.Snapshot decisions, ObjectNode audit) {
    }

    void refresh(ProjectRow original) throws SQLException {
        var row = original;
        while (row != null) {
            var roots = roots();
            var indexed = decisions.scan(row, roots);
            var audit = audit(row, roots);
            var current = find(row.id());
            if (current == null) {
                forget(row.id());
                return;
            }
            if (!sameAuditSource(row, current) || !samePrivateSource(row, current) || !roots.equals(roots())) {
                row = current;
                continue;
            }
            if (indexed.hasPrivateRecords() && !current.hadPrivateRecords()) {
                rememberPrivateRecords(row);
            }
            cache.put(row.id(), new CachedContext(row, roots, indexed, audit));
            return;
        }
    }

    private void rememberPrivateRecords(ProjectRow row) throws SQLException {
        // The path condition keeps a concurrent disconnect or rebind from reviving the old flag.
        try (var connection = store.open(); var update = connection.prepareStatement("""
                UPDATE projects SET private_context_had_records=1
                WHERE id=? AND path=? AND private_context_path=? AND private_context_had_records=0
                """)) {
            update.setString(1, row.id());
            update.setString(2, row.path());
            update.setString(3, row.privateContextPath());
            update.executeUpdate();
        }
    }

    ObjectNode view(ProjectRow row) {
        var cached = cache.get(row.id());
        String privateStatus = row.privateContextPath() == null ? "disconnected" : "unavailable";
        boolean hasPrivateMap = false;
        if (cached != null && samePrivateSource(cached.row(), row)) {
            privateStatus = cached.decisions().privateStatus();
            hasPrivateMap = cached.decisions().hasPrivateMap();
        }
        var result = mapper.createObjectNode().put("visibility", row.visibility())
                .put("privateContextPath", row.privateContextPath()).put("privateStatus", privateStatus)
                .put("hasPrivateMap", hasPrivateMap).put("hadPrivateRecords", row.hadPrivateRecords());
        result.set("allowlist", mapper.readTree(row.publicationAllowlist()));
        result.set("audit", cached != null && sameAuditSource(cached.row(), row) ? cached.audit().deepCopy() : uncheckedAudit());
        return result;
    }

    List<ObjectNode> decisions() throws SQLException {
        var roots = roots();
        var today = LocalDate.now();
        List<ObjectNode> output = new ArrayList<>();
        try (var connection = store.open()) {
            for (var row : ProjectRow.list(connection)) {
                var cached = cache.get(row.id());
                if (cached == null) {
                    continue;
                }
                for (var record : cached.decisions().entries()) {
                    if (currentSource(cached, row, roots, record)) {
                        output.add(decisions.view(record, today));
                    }
                }
            }
        }
        return output;
    }

    void forget(String id) {
        cache.remove(id);
    }

    boolean hasSnapshot(ProjectRow row) {
        var cached = cache.get(row.id());
        return cached != null && sameRepositorySource(cached.row(), row) && samePrivateSource(cached.row(), row);
    }

    void save(String id, JsonNode input) throws SQLException, IOException {
        ApiJson.requireObject(input);
        var visibility = input.path("visibility").stringValue(null);
        if (visibility == null || !VISIBILITY.contains(visibility)) {
            throw ApiJson.invalid("visibility");
        }
        var privatePath = input.path("privateContextPath");
        if (!privatePath.isNull() && !privatePath.isString()) {
            throw ApiJson.invalid("privateContextPath");
        }
        var row = require(id);
        String path = privatePath.isNull() ? null : validatePrivatePath(row, privatePath.stringValue());
        store.write(connection -> {
            var current = ProjectRow.require(connection, id);
            if (!samePrivateSource(row, current)) {
                throw new TaskError(409, "Каталог проекта или приватный контекст изменился. Обновите проект.");
            }
            try (var update = connection.prepareStatement("""
                    UPDATE projects SET visibility=?, private_context_path=?,
                    private_context_had_records=CASE WHEN private_context_path IS ? THEN private_context_had_records ELSE 0 END
                    WHERE id=?
                    """)) {
                update.setString(1, visibility);
                update.setString(2, path);
                update.setString(3, path);
                update.setString(4, id);
                update.executeUpdate();
            }
            return null;
        });
    }

    private String validatePrivatePath(ProjectRow row, String input) throws IOException {
        String text = TaskPayload.trim(input);
        if (text.isEmpty() || text.length() > 2000) {
            throw ApiJson.invalid("privateContextPath");
        }
        Path target;
        try {
            target = Path.of(text);
        } catch (InvalidPathException exception) {
            throw ApiJson.invalid("privateContextPath");
        }
        if (!target.isAbsolute()) {
            throw new TaskError(400, "Нужен абсолютный путь приватного контекста.");
        }
        target = target.normalize();
        var repo = Path.of(row.path());
        if (ProjectFiles.within(repo, target) || ProjectFiles.within(target, repo)) {
            throw new TaskError(400, "Приватный контекст должен находиться отдельно от репозитория проекта.");
        }
        if (!target.toString().equals(row.privateContextPath())) {
            try {
                ProjectFiles.assertProjectAccess(target, List.of(target.getRoot().toString()));
            } catch (NoSuchFileException exception) {
                throw new TaskError(400, "Укажите каталог приватного контекста.");
            }
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new TaskError(400, "Укажите каталог приватного контекста.");
            }
        }
        return target.toString();
    }

    void allow(String id, String file, boolean allowed) throws SQLException {
        if (file.isEmpty() || file.length() > 4000) {
            throw ApiJson.invalid("path");
        }
        var row = require(id);
        var roots = roots();
        var allowedBefore = allowlist(row).contains(file);
        if (allowed && !allowedBefore) {
            var findings = audit(row, roots).path("findings");
            boolean found = false;
            for (var finding : findings) {
                if (file.equals(finding.path("path").asString())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw invalidAuditPath();
            }
        }
        store.write(connection -> {
            var current = ProjectRow.require(connection, id);
            var entries = new TreeSet<>(allowlist(current));
            if (allowed) {
                var stillAllowed = allowedBefore ? entries.contains(file)
                        : roots.equals(JournalSettings.strings(settings.read(connection).path("roots")));
                if (!stillAllowed || !sameRepositorySource(row, current) || !row.visibility().equals(current.visibility())) {
                    throw invalidAuditPath();
                }
                entries.add(file);
            } else {
                entries.remove(file);
            }
            try (var update = connection.prepareStatement("UPDATE projects SET publication_allowlist=? WHERE id=?")) {
                update.setString(1, mapper.writeValueAsString(entries));
                update.setString(2, id);
                update.executeUpdate();
            }
            return null;
        });
    }

    ObjectNode content(String id, String key) throws SQLException {
        if (key.isEmpty() || key.length() > 6000) {
            throw ApiJson.invalid("key");
        }
        refresh(require(id));
        var cached = cache.get(id);
        var row = require(id);
        var roots = roots();
        if (cached == null) {
            throw missingDecision();
        }
        for (var entry : cached.decisions().entries()) {
            if (key.equals(entry.path("key").asString()) && entry.path("hasContent").asBoolean(false)
                    && currentSource(cached, row, roots, entry)) {
                return readContent(cached, row, roots, entry);
            }
        }
        throw missingDecision();
    }

    private ObjectNode readContent(CachedContext cached, ProjectRow row, List<String> roots, ObjectNode entry) throws SQLException {
        var privateSource = FileDecisionIndex.PRIVATE.equals(entry.path("source").asString());
        var sourceRoot = Path.of(privateSource ? row.privateContextPath() : row.path());
        var sourcePath = Path.of(entry.path("sourcePath").asString());
        if (!ProjectFiles.within(sourceRoot, sourcePath)) {
            throw missingDecision();
        }
        String body;
        try {
            body = decisions.body(entry, privateSource ? List.of(sourceRoot.getRoot().toString()) : roots);
        } catch (IOException | RuntimeException exception) {
            throw missingDecision();
        }
        var current = find(row.id());
        if (body == null || current == null || !currentSource(cached, current, roots(), entry)) {
            throw missingDecision();
        }
        return mapper.createObjectNode().put("body", body);
    }

    private ObjectNode audit(ProjectRow row, List<String> roots) {
        var audit = uncheckedAudit();
        if (!"public".equals(row.visibility())) {
            return audit;
        }
        try {
            if (!row.available() || !mapper.readTree(row.snapshot()).path("git").path("present").asBoolean()) {
                return audit.put("status", "unavailable");
            }
            var root = Path.of(row.path());
            ProjectFiles.assertProjectAccess(root, roots);
            var tracked = files.trackedFiles(row.path());
            var allowlist = Set.copyOf(allowlist(row));
            var findings = audit.putArray("findings");
            for (var file : tracked) {
                var kind = candidate(file);
                if (kind != null && !allowlist.contains(file)) {
                    findings.addObject().put("path", file).put("kind", kind);
                }
            }
            return audit.put("status", "ok").put("kit", kit(root, tracked));
        } catch (IOException | RuntimeException exception) {
            return audit.put("status", "unavailable");
        }
    }

    private static String kit(Path root, List<String> tracked) {
        if (tracked.stream().anyMatch(file -> file.equals("AGENTS.md") || file.startsWith(".ai-rules/"))) {
            return "tracked";
        }
        var agents = Files.isRegularFile(root.resolve("AGENTS.md"), LinkOption.NOFOLLOW_LINKS);
        var rules = Files.isDirectory(root.resolve(".ai-rules"), LinkOption.NOFOLLOW_LINKS);
        if (agents && rules) {
            return "local";
        }
        return agents || rules ? "partial" : "missing";
    }

    private static String candidate(String file) {
        if (file.equals("AGENTS.md") || file.startsWith(".ai-rules/") || file.startsWith(".local/")) {
            return "infrastructure";
        }
        return DOCUMENTS.contains(file) || file.startsWith("decisions/") ? "document" : null;
    }

    private ObjectNode uncheckedAudit() {
        var audit = mapper.createObjectNode().put("status", "unchecked").put("kit", "unknown");
        audit.putArray("findings");
        return audit;
    }

    private List<String> allowlist(ProjectRow row) {
        return JournalSettings.strings(mapper.readTree(row.publicationAllowlist()));
    }

    private List<String> roots() throws SQLException {
        return JournalSettings.strings(settings.read().path("roots"));
    }

    private ProjectRow find(String id) throws SQLException {
        try (var connection = store.open()) {
            return ProjectRow.find(connection, id);
        }
    }

    private ProjectRow require(String id) throws SQLException {
        try (var connection = store.open()) {
            return ProjectRow.require(connection, id);
        }
    }

    private static boolean currentSource(CachedContext cached, ProjectRow row, List<String> roots, ObjectNode entry) {
        if (FileDecisionIndex.PRIVATE.equals(entry.path("source").asString())) {
            return samePrivateSource(cached.row(), row);
        }
        return sameRepositorySource(cached.row(), row) && cached.roots().equals(roots);
    }

    private static boolean samePrivateSource(ProjectRow a, ProjectRow b) {
        return a.path().equals(b.path()) && Objects.equals(a.privateContextPath(), b.privateContextPath());
    }

    private static boolean sameRepositorySource(ProjectRow a, ProjectRow b) {
        return a.path().equals(b.path()) && a.available() == b.available();
    }

    private static boolean sameAuditSource(ProjectRow a, ProjectRow b) {
        return sameRepositorySource(a, b) && a.visibility().equals(b.visibility())
                && a.snapshot().equals(b.snapshot()) && a.publicationAllowlist().equals(b.publicationAllowlist());
    }

    private static TaskError missingDecision() {
        return new TaskError(404, "Исходный файл решения недоступен.");
    }

    private static TaskError invalidAuditPath() {
        return new TaskError(409, "Путь отсутствует в текущем аудите публикации. Обновите проект.");
    }
}
