package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

final class ProjectFiles {

    private static final int MAX_FILE_BYTES = 256 * 1024;
    private static final String LIMIT_ERROR = "Достигнут предел обхода. Укажите более узкие каталоги.";
    private static final String GIT_ERROR = "Не удалось прочитать Git. Проверьте доступ к каталогу и установку Git.";
    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");
    private static final DateTimeFormatter INSTANT = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> MARKERS = Set.of(".git", "PROJECT.yaml", "package.json", "pyproject.toml",
            "Cargo.toml", "go.mod", "AGENTS.md");
    private static final Set<String> ALWAYS_EXCLUDED = Set.of(".git", "node_modules", ".venv", ".data", ".ai-rules");
    private static final Pattern MAP_NAME = Pattern.compile("^(PROJECT_MAP|PROJECT-MAP)(\\.local)?\\.md$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INTERESTING_FILE = Pattern.compile(
            "^(readme|agents|project|package|tsconfig|vite|docker|cargo|go\\.|pyproject)", Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOTE_CREDENTIALS = Pattern.compile("(https?://)[^/@]+@", Pattern.CASE_INSENSITIVE);
    private static final List<String> OPTIONAL_MAPS = List.of("docs/PROJECT_MAP.md", ".local/PROJECT_MAP.md",
            ".local/project-study/README.md");

    private final JsonMapper mapper;
    private final YamlMetadata yaml;

    ProjectFiles(JsonMapper mapper) {
        this.mapper = mapper;
        this.yaml = new YamlMetadata(mapper);
    }

    Discovery discover(JsonNode settings) {
        var scan = new Scan(settings);
        for (JsonNode root : settings.path("roots")) {
            try {
                scan.walk(Path.of(root.asString()).toAbsolutePath().normalize(), 0);
            } catch (IllegalArgumentException invalid) {
                scan.errors.add("Не удалось прочитать каталог: " + root.asString());
            }
        }
        return new Discovery(scan.paths.stream().sorted().toList(), List.copyOf(scan.errors));
    }

    record Discovery(List<String> paths, List<String> errors) {
    }

    private static final class Scan {
        private final Set<String> excludedProjects = new HashSet<>();
        private final Set<String> excludedNames = new HashSet<>(ALWAYS_EXCLUDED);
        private final Set<String> seen = new HashSet<>();
        private final List<String> paths = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        Scan(JsonNode settings) {
            for (JsonNode project : settings.path("excludedProjectPaths")) {
                excludedProjects.add(pathKey(Path.of(project.asString()).toAbsolutePath().normalize()));
            }
            for (JsonNode name : settings.path("exclusions")) {
                excludedNames.add(name.asString().toLowerCase(Locale.ROOT));
            }
        }

        void walk(Path directory, int depth) {
            if (seen.size() >= 20_000 || depth > 30) {
                if (!errors.contains(LIMIT_ERROR)) {
                    errors.add(LIMIT_ERROR);
                }
                return;
            }
            try {
                if (link(attributes(directory))) {
                    return;
                }
                Path canonical = directory.toRealPath();
                String key = pathKey(canonical);
                if (excludedProjects.contains(key) || !seen.add(key)) {
                    return;
                }
                var entries = children(canonical);
                if (entries.stream().anyMatch(entry -> MARKERS.contains(entry.name()))) {
                    paths.add(canonical.toString());
                    return;
                }
                for (Entry entry : entries) {
                    if (entry.attributes().isDirectory()
                            && !excludedNames.contains(entry.name().toLowerCase(Locale.ROOT))) {
                        walk(canonical.resolve(entry.name()), depth + 1);
                    }
                }
            } catch (IOException unavailable) {
                errors.add("Не удалось прочитать каталог: " + directory);
            }
        }
    }

    ObjectNode inspect(String directory) throws IOException {
        String scannedAt = INSTANT.format(Instant.now());
        Path root = Path.of(directory);
        String directoryName = root.getFileName() == null ? "" : root.getFileName().toString();
        var entries = children(root);
        var files = entries.stream().filter(entry -> entry.attributes().isRegularFile()).map(Entry::name).toList();
        var snapshot = mapper.createObjectNode().put("path", directory)
                .put("directoryName", directoryName).put("scannedAt", scannedAt);
        boolean gitPresent = entries.stream().anyMatch(entry -> entry.name().equals(".git"));
        snapshot.set("git", inspectGit(root, gitPresent));
        snapshot.set("yaml", yaml.read(root));
        var selected = snapshot.putArray("files");
        files.stream().filter(name -> INTERESTING_FILE.matcher(name).find()).limit(30).forEach(selected::add);
        snapshot.put("hasAgents", files.contains("AGENTS.md"));
        snapshot.put("mapFile", mapFile(root, files));
        snapshot.putNull("package");
        var errors = snapshot.putArray("errors");
        if (files.contains("package.json")) {
            try {
                snapshot.set("package", readPackage(root));
            } catch (IOException | RuntimeException invalid) {
                errors.add("Не удалось прочитать package.json.");
            }
        }
        return snapshot;
    }

    ObjectNode metadata(JsonNode input, String defaultName) {
        return yaml.validate(input, defaultName);
    }

    void exportMetadata(String directory, JsonNode metadata, String expectedHash) throws IOException {
        yaml.export(Path.of(directory), metadata, expectedHash);
    }

    List<String> trackedFiles(String directory) throws IOException {
        return GitProcess.tracked(Path.of(directory));
    }

    static Object loadYaml(String source) {
        return YamlMetadata.load(source);
    }

    private ObjectNode inspectGit(Path directory, boolean present) {
        var result = mapper.createObjectNode().put("present", present).putNull("branch").putNull("dirty")
                .putNull("changedFiles").putNull("remote").putNull("error");
        var commits = result.putArray("commits");
        if (!present) {
            return result;
        }
        try {
            var entries = GitProcess.nulFields(GitProcess.status(directory));
            int count = 0;
            for (int index = 0; index < entries.size(); index++) {
                count++;
                String entry = entries.get(index);
                if (entry.length() >= 2 && (renamed(entry.charAt(0)) || renamed(entry.charAt(1)))) {
                    index++;
                }
            }
            result.put("changedFiles", count).put("dirty", count > 0);
            try {
                result.put("branch", GitProcess.run(directory, "symbolic-ref", "--short", "-q", "HEAD").strip());
            } catch (IOException detached) {
                result.put("branch", "detached HEAD");
            }
            if (hasHead(directory)) {
                String log = GitProcess.run(directory, "log", "-8", "--format=%H%x00%cI%x00%s%x00");
                String[] fields = log.split("\u0000", -1);
                for (int index = 0; index + 2 < fields.length; index += 3) {
                    commits.addObject().put("hash", fields[index].strip()).put("date", fields[index + 1])
                            .put("subject", fields[index + 2]);
                }
            }
            var remotes = GitProcess.run(directory, "remote").lines().filter(name -> !name.isBlank()).toList();
            if (!remotes.isEmpty()) {
                String remote = remotes.contains("origin") ? "origin" : remotes.getFirst();
                String url = GitProcess.run(directory, "remote", "get-url", "--", remote).strip();
                result.put("remote", REMOTE_CREDENTIALS.matcher(url).replaceFirst("$1").replaceFirst("[?#].*$", ""));
            }
        } catch (IOException unavailable) {
            result.put("error", GIT_ERROR);
        }
        return result;
    }

    private static boolean hasHead(Path directory) {
        try {
            GitProcess.run(directory, "rev-parse", "--verify", "HEAD");
            return true;
        } catch (IOException unborn) {
            return false;
        }
    }

    private static boolean renamed(char status) {
        return status == 'R' || status == 'C';
    }

    private ObjectNode readPackage(Path directory) throws IOException {
        String content = readSmallFile(directory.resolve("package.json"));
        var input = mapper.readTree(content == null ? "{}" : content);
        if (input.isNull() || input.isMissingNode()) {
            throw new IOException("Некорректный package.json.");
        }
        var result = mapper.createObjectNode();
        if (input.path("name").isString()) {
            result.put("name", input.path("name").asString());
        }
        var scripts = result.putArray("scripts");
        JsonNode source = input.path("scripts");
        if (source.isObject()) {
            source.properties().forEach(property -> scripts.add(property.getKey()));
        } else if (source.isArray()) {
            for (int index = 0; index < source.size(); index++) {
                scripts.add(Integer.toString(index));
            }
        }
        return result;
    }

    private static String mapFile(Path directory, List<String> files) {
        for (String name : files) {
            if (MAP_NAME.matcher(name).matches()) {
                return name;
            }
        }
        for (String candidate : OPTIONAL_MAPS) {
            try {
                Path current = directory;
                BasicFileAttributes attributes = null;
                for (String segment : candidate.split("/")) {
                    current = current.resolve(segment);
                    attributes = attributes(current);
                    if (link(attributes)) {
                        break;
                    }
                }
                if (attributes != null && attributes.isRegularFile() && !link(attributes)) {
                    return candidate;
                }
            } catch (IOException absent) {
                // A project map is optional.
            }
        }
        return null;
    }

    static void assertProjectAccess(Path target, List<String> roots) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path root = roots.stream().map(value -> Path.of(value).toAbsolutePath().normalize())
                .filter(candidate -> within(candidate, absolute)).findFirst().orElseThrow(() ->
                        new TaskError(403, "Проект вне настроенных каталогов. Добавьте каталог в настройках."));
        if (!within(root.toRealPath(), absolute.toRealPath())) {
            throw new TaskError(403, "Путь проекта ведёт за пределы каталога.");
        }
        if (link(attributes(root))) {
            throw new TaskError(403, "Корень не должен быть символической ссылкой.");
        }
        Path current = root;
        for (Path segment : root.relativize(absolute)) {
            current = current.resolve(segment);
            if (link(attributes(current))) {
                throw new TaskError(403, "Символические ссылки не поддерживаются.");
            }
        }
    }

    static boolean within(Path root, Path target) {
        return target.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize());
    }

    static String readSmallFile(Path file) throws IOException {
        try {
            var attributes = attributes(file);
            if (!attributes.isRegularFile() || link(attributes)) {
                throw new IOException("Ожидался обычный файл, не ссылка.");
            }
            if (attributes.size() > MAX_FILE_BYTES) {
                throw new IOException("Файл больше 256 КБ.");
            }
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(MAX_FILE_BYTES + 1);
                if (bytes.length > MAX_FILE_BYTES) {
                    throw new IOException("Файл больше 256 КБ.");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (NoSuchFileException missing) {
            return null;
        }
    }

    private static List<Entry> children(Path directory) throws IOException {
        var entries = new ArrayList<Entry>();
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                try {
                    var attributes = attributes(child);
                    if (!link(attributes)) {
                        entries.add(new Entry(child.getFileName().toString(), attributes));
                    }
                } catch (NoSuchFileException removed) {
                    // The entry may have disappeared since listing the directory.
                }
            }
        } catch (DirectoryIteratorException unreadable) {
            throw unreadable.getCause();
        }
        return entries;
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean link(BasicFileAttributes attributes) {
        // Windows junctions are reparse points but Java does not mark them as symbolic links.
        return attributes.isSymbolicLink() || (WINDOWS && attributes.isOther());
    }

    private static String pathKey(Path path) {
        String value = path.toString();
        return WINDOWS ? value.toLowerCase(Locale.ROOT) : value;
    }

    private record Entry(String name, BasicFileAttributes attributes) {
    }
}
