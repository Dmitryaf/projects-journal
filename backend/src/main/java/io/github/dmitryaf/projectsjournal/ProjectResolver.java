package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

final class ProjectResolver {

    private static final String CHANGED = "Регистрация проекта изменилась. Получите список проектов заново.";

    private final SqliteStore store;
    private final JournalSettings settings;

    ProjectResolver(SqliteStore store, JournalSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    ProjectRow resolve(String projectId, String workingDirectory) throws SQLException, IOException {
        List<ProjectRow> rows;
        List<String> roots;
        try (var connection = store.open()) {
            ProjectRow requested = projectId == null ? null : ProjectRow.find(connection, projectId);
            if (projectId != null && requested == null) {
                throw new TaskError(404, "Проект не найден. Получите список проектов.");
            }
            if (workingDirectory == null || workingDirectory.isEmpty()) {
                if (requested != null) {
                    return requested;
                }
                throw new TaskError(400, "Передайте projectId или абсолютный workingDirectory текущего проекта.");
            }
            rows = ProjectRow.list(connection);
            roots = JournalSettings.strings(settings.read(connection).path("roots"));
        }

        Path directory = absoluteDirectory(workingDirectory);
        ProjectRow direct = rows.stream()
                .filter(row -> ProjectFiles.within(Path.of(row.path()), directory))
                .max(Comparator.comparingInt(row -> row.path().length())).orElse(null);
        Match match;
        if (direct == null) {
            match = findWorktree(rows, roots, directory);
        } else {
            Path root = Path.of(direct.path());
            ProjectFiles.assertProjectAccess(root, roots);
            match = new Match(direct, root);
        }
        ProjectFiles.assertProjectAccess(directory, List.of(match.root().toString()));
        if (!Files.isDirectory(directory)) {
            throw new TaskError(400, "workingDirectory должен указывать на каталог.");
        }
        if (projectId != null && !projectId.equals(match.row().id())) {
            throw new TaskError(409, "projectId не соответствует рабочему каталогу. Задача не изменена.");
        }

        // A scan or a rebind may have changed registration during Git and filesystem reads.
        try (var connection = store.open()) {
            ProjectRow current = ProjectRow.find(connection, match.row().id());
            if (current == null || !Path.of(current.path()).equals(Path.of(match.row().path()))) {
                throw new TaskError(409, CHANGED);
            }
            ProjectFiles.assertProjectAccess(Path.of(current.path()),
                    JournalSettings.strings(settings.read(connection).path("roots")));
            return current;
        }
    }

    private static Match findWorktree(List<ProjectRow> rows, List<String> roots, Path directory) {
        var matches = new LinkedHashMap<String, Match>();
        for (ProjectRow row : rows) {
            Path registered = Path.of(row.path());
            try {
                ProjectFiles.assertProjectAccess(registered, roots);
                // Without this marker Git may find an enclosing repository outside this registration.
                ProjectFiles.assertProjectAccess(registered.resolve(".git"), List.of(row.path()));
                for (String record : GitProcess.worktreeList(registered).split("\u0000\u0000")) {
                    var fields = GitProcess.nulFields(record);
                    if (fields.isEmpty() || !fields.getFirst().startsWith("worktree ")
                            || fields.contains("bare") || fields.stream().anyMatch(field ->
                                    field.equals("prunable") || field.startsWith("prunable "))) {
                        continue;
                    }
                    Path root = Path.of(fields.getFirst().substring("worktree ".length()));
                    if (root.isAbsolute() && ProjectFiles.within(root, directory) && Files.isDirectory(root)) {
                        matches.putIfAbsent(row.id(), new Match(row, root.normalize()));
                    }
                }
            } catch (IOException | TaskError | InvalidPathException unavailable) {
                // An unavailable registration cannot establish worktree identity.
            }
        }
        if (matches.size() != 1) {
            throw new TaskError(404, matches.isEmpty()
                    ? "Проект для этого каталога не найден. Добавьте основной репозиторий в дневнике."
                    : "Каталог соответствует нескольким проектам. Уточните регистрацию проектов в дневнике.");
        }
        return matches.sequencedValues().getFirst();
    }

    private static Path absoluteDirectory(String value) {
        try {
            Path parsed = Path.of(value);
            if (parsed.isAbsolute()) {
                return parsed.normalize();
            }
        } catch (InvalidPathException invalid) {
            // Use the same validation error as a relative selector.
        }
        throw new TaskError(400, "workingDirectory должен быть абсолютным.");
    }

    private record Match(ProjectRow row, Path root) {
    }
}
