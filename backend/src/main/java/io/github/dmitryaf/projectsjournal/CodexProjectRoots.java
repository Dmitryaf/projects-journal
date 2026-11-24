package io.github.dmitryaf.projectsjournal;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.TreeSet;

final class CodexProjectRoots {

    private final SqliteStore store;
    private final JournalSettings settings;
    private final ProjectResolver resolver;

    CodexProjectRoots(SqliteStore store, JournalSettings settings) {
        this.store = store;
        this.settings = settings;
        this.resolver = new ProjectResolver(store, settings);
    }

    List<String> verified(String projectId) throws SQLException, IOException {
        ProjectRow row;
        List<String> configured;
        try (var connection = store.open()) {
            row = ProjectRow.require(connection, projectId);
            configured = JournalSettings.strings(settings.read(connection).path("roots"));
        }
        Path primary = Path.of(row.path()).toAbsolutePath().normalize();
        ProjectFiles.assertProjectAccess(primary, configured);
        if (!primary.toRealPath().equals(primary)) {
            throw new TaskError(403, "Ссылки в пути проекта не поддерживаются для чтения диалогов.");
        }
        var roots = new TreeSet<String>();
        roots.add(primary.toString());
        try {
            ProjectFiles.assertProjectAccess(primary.resolve(".git"), List.of(primary.toString()));
            Path common = commonDirectory(primary);
            for (String record : GitProcess.worktreeList(primary).split("\u0000\u0000")) {
                var fields = GitProcess.nulFields(record);
                if (fields.isEmpty() || !fields.getFirst().startsWith("worktree ") || fields.contains("bare")
                        || fields.stream().anyMatch(field -> field.equals("prunable") || field.startsWith("prunable "))) {
                    continue;
                }
                addWorktree(roots, projectId, common, fields.getFirst().substring("worktree ".length()), primary);
            }
        } catch (IOException | TaskError unavailable) {
            // A registered non-Git project still owns its exact configured directory.
        }
        if (roots.size() > 32) {
            throw new TaskError(400, "Для разрешения чтения поддерживаются до 32 каталогов одного проекта.");
        }
        return List.copyOf(roots);
    }

    private void addWorktree(TreeSet<String> roots, String id, Path common, String value, Path primary)
            throws SQLException {
        try {
            Path path = Path.of(value).normalize();
            if (!path.isAbsolute() || path.equals(primary)) {
                return;
            }
            ProjectFiles.assertProjectAccess(path, List.of(path.toString()));
            ProjectFiles.assertProjectAccess(path.resolve(".git"), List.of(path.toString()));
            if (path.toRealPath().equals(path) && common.equals(commonDirectory(path))
                    && id.equals(resolver.resolve(id, path.toString()).id())) {
                roots.add(path.toString());
            }
        } catch (IOException | TaskError | InvalidPathException unavailable) {
            // Missing, replaced, ambiguous and linked paths cannot extend dialog access.
        }
    }

    private static Path commonDirectory(Path root) throws IOException {
        String value = GitProcess.run(root, "rev-parse", "--path-format=absolute", "--git-common-dir").strip();
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IOException("Не удалось проверить общий каталог Git.");
        }
        return path.toRealPath();
    }

    static boolean same(String root, String candidate) {
        try {
            Path requested = Path.of(candidate);
            return requested.isAbsolute() && Path.of(root).normalize().equals(requested.normalize());
        } catch (InvalidPathException invalid) {
            return false;
        }
    }
}
