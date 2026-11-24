package io.github.dmitryaf.projectsjournal;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reads saved excerpts and their authorization using SQLite only. */
final class SavedCodexDigest {

    static final String POLICY = "codex-dialog-v1";

    private final JsonMapper mapper;
    private final JournalSettings settings;
    private final AnalysisPrivacy privacy;

    SavedCodexDigest(JsonMapper mapper, JournalSettings settings, AnalysisPrivacy privacy) {
        this.mapper = mapper;
        this.settings = settings;
        this.privacy = privacy;
    }

    ObjectNode read(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT d.*,g.enabled AS live_enabled,g.revision AS live_revision,
                       g.policy_version AS live_policy,g.access_stamp AS live_stamp,g.roots_json AS live_roots
                FROM codex_digests d LEFT JOIN codex_grants g ON g.project_id=d.project_id WHERE d.id=?
                """)) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new TaskError(404, "Итог диалога не найден.");
                }
                return view(connection, rows);
            }
        }
    }

    private ObjectNode view(Connection connection, ResultSet row) throws SQLException {
        var saved = mapper.createObjectNode().put("id", row.getString("id"))
                .put("reviewKey", row.getString("review_key")).put("projectId", row.getString("project_id"))
                .put("scopeProjectId", row.getString("scope_project_id")).put("projectName", row.getString("project_name"));
        saved.set("period", mapper.readTree(row.getString("period_json")));
        saved.putObject("thread").put("id", row.getString("thread_id")).put("name", row.getString("thread_name"))
                .put("updatedAt", row.getLong("thread_updated_at"));
        saved.put("summary", row.getString("summary"));
        saved.set("excerpts", mapper.readTree(row.getString("excerpts_json")));
        return saved.put("sourceHash", row.getString("source_hash")).put("policyVersion", row.getString("policy_version"))
                .put("capturedAt", row.getString("captured_at")).put("checkedAt", row.getString("checked_at"))
                .put("stale", row.getBoolean("stale")).put("revision", row.getLong("revision"))
                .put("eligible", eligible(connection, row));
    }

    private boolean eligible(Connection connection, ResultSet row) throws SQLException {
        String id = row.getString("project_id");
        String stamp = row.getString("live_stamp");
        if (id == null || !id.equals(row.getString("scope_project_id")) || row.getBoolean("stale")
                || !POLICY.equals(row.getString("policy_version")) || row.getLong("grant_revision") != row.getLong("live_revision")
                || !Objects.equals(stamp, row.getString("access_stamp"))) {
            return false;
        }
        ProjectRow project = ProjectRow.find(connection, id);
        return permitted(connection, project, row.getBoolean("live_enabled"), row.getString("live_policy"),
                stamp, row.getString("live_roots"));
    }

    boolean permitted(Connection connection, ProjectRow project, boolean approved, String policy, String stamp,
                      String rootsJson) throws SQLException {
        if (!approved || !POLICY.equals(policy) || project == null || !project.available()
                || !Objects.equals(stamp, projectStamp(project))) {
            return false;
        }
        try {
            Path projectPath = Path.of(project.path()).toAbsolutePath().normalize();
            var configured = JournalSettings.strings(settings.read(connection).path("roots"));
            if (configured.stream().noneMatch(root -> ProjectFiles.within(Path.of(root), projectPath))) {
                return false;
            }
            JsonNode roots = mapper.readTree(rootsJson);
            if (roots.isArray()) {
                for (JsonNode root : roots) {
                    if (root.isString()) {
                        Path path = Path.of(root.asString());
                        if (path.isAbsolute() && path.normalize().equals(projectPath)) {
                            return true;
                        }
                    }
                }
            }
        } catch (JacksonException | InvalidPathException invalid) {
            // A stored grant whose scope cannot be read gives no authority.
        }
        return false;
    }

    String projectStamp(ProjectRow project) {
        return privacy.digest(mapper.createObjectNode().put("visibility", project.visibility())
                .put("privateContext", project.privateContextPath()).put("allowlist", project.publicationAllowlist()));
    }

    String stamp(JsonNode saved) {
        return privacy.digest(mapper.createObjectNode().set("revision", saved.get("revision"))
                .set("eligible", saved.get("eligible")).set("sourceHash", saved.get("sourceHash")));
    }
}
