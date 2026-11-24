package io.github.dmitryaf.projectsjournal;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
final class ScheduleGuard {

    private static final String STANDING_CHANGED = "Разрешение расписания отключено или изменилось.";
    private static final String ACCESS_CHANGED =
            "Доступ к выбранным проектам или Codex изменился. Подтвердите источники заново.";
    private static final String SELECTION_CHANGED =
            "Выбор источников не соответствует подтверждённому расписанию. Подтвердите источники заново.";

    private final JsonMapper mapper;
    private final PeriodContext context;
    private final CodexContext codex;
    private final SavedCodexDigest digests;

    ScheduleGuard(JsonMapper mapper, PeriodContext context, AnalysisPrivacy privacy, JournalSettings settings,
                  CodexContext codex) {
        this.mapper = mapper;
        this.context = context;
        this.codex = codex;
        this.digests = new SavedCodexDigest(mapper, settings, privacy);
    }

    Row row(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM analysis_schedules WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? readRow(rows) : null;
            }
        }
    }

    Row readRow(ResultSet row) throws SQLException {
        try {
            var selection = mapper.readTree(row.getString("selection_json"));
            var access = mapper.readTree(row.getString("access_json"));
            if (!selection.isObject() || !access.isArray()) {
                throw new TaskError(410, STANDING_CHANGED);
            }
            return new Row(row.getString("id"), row.getString("scope_key"), row.getString("project_name"),
                    row.getString("period_kind"), row.getString("timezone"), row.getString("model"),
                    row.getString("policy_version"), row.getLong("revision"), row.getBoolean("enabled"),
                    row.getString("provider"), selection.asObject(), access.asArray(),
                    row.getString("next_period_start"), row.getString("last_run_id"), row.getString("note"),
                    row.getString("updated_at"), row.getString("enabled_at"),
                    row.getString("lease_owner"), row.getString("lease_until"));
        } catch (JacksonException malformed) {
            throw new TaskError(410, STANDING_CHANGED);
        }
    }

    void verify(Connection connection, String id, long revision, String model, String provider, String policy)
            throws SQLException {
        Row row = row(connection, id);
        if (row == null || !row.enabled() || row.revision() != revision || !Objects.equals(row.model(), model)
                || !Objects.equals(row.provider(), provider) || !Objects.equals(row.policy(), policy)) {
            throw new TaskError(410, STANDING_CHANGED);
        }
        verifyAccess(connection, approvedSelection(row), row.access());
    }

    void verifyAccess(Connection connection, ObjectNode selection, ArrayNode access) throws SQLException {
        List<String> projectIds = JournalSettings.strings(selection.path("projectIds"));
        if (projectIds.size() != access.size()) {
            throw new TaskError(410, ACCESS_CHANGED);
        }
        var remaining = new HashSet<>(projectIds);
        for (JsonNode approved : access) {
            String id = approved.path("id").stringValue(null);
            if (!remaining.remove(id)) {
                throw new TaskError(410, ACCESS_CHANGED);
            }
            ProjectRow project = ProjectRow.find(connection, id);
            if (project == null || !digests.projectStamp(project).equals(approved.path("stamp").stringValue(null))) {
                throw new TaskError(410, ACCESS_CHANGED);
            }
            if (approved.has("codexRevision")) {
                var grant = codex.grant(connection, id);
                var required = approved.path("codexRevision");
                if (!required.isNumber() || !required.canConvertToExactIntegral() || !required.canConvertToLong()
                        || required.longValue() < 1 || !grant.path("enabled").booleanValue()
                        || grant.path("revision").longValue() != required.longValue()) {
                    throw new TaskError(410, ACCESS_CHANGED);
                }
            }
        }
    }

    ArrayNode captureAccess(Connection connection, ObjectNode selection, List<String> codexProjects) throws SQLException {
        var access = mapper.createArrayNode();
        for (String id : JournalSettings.strings(selection.path("projectIds"))) {
            var project = ProjectRow.find(connection, id);
            if (project == null) {
                throw new TaskError(410, "Выбранный проект удалён.");
            }
            var approved = access.addObject().put("id", id).put("stamp", digests.projectStamp(project));
            if (codexProjects.contains(id)) {
                var grant = codex.grant(connection, id);
                if (!grant.path("enabled").booleanValue()) {
                    throw new TaskError(410, "Сначала явно разрешите чтение Codex выбранного проекта.");
                }
                approved.set("codexRevision", grant.get("revision"));
            }
        }
        return access;
    }

    void verifySelection(Connection connection, String id, ObjectNode selection) throws SQLException {
        Row row = row(connection, id);
        if (row == null || !row.enabled()) {
            throw new TaskError(410, STANDING_CHANGED);
        }
        ObjectNode approved = approvedSelection(row);
        ObjectNode actual = normalizeSelection(selection);
        var included = actual.get("codexDigestIds").asArray();
        approved.putArray("codexDigestIds");
        actual.putArray("codexDigestIds");
        if (!approved.equals(actual)) {
            throw new TaskError(410, SELECTION_CHANGED);
        }
        if (!included.isEmpty()) {
            verifyDigestOrigins(connection, row.access(), included);
        }
    }

    private void verifyDigestOrigins(Connection connection, ArrayNode access, ArrayNode included) throws SQLException {
        var codexRevisions = new HashMap<String, Long>();
        for (JsonNode approval : access) {
            JsonNode revision = approval.path("codexRevision");
            if (revision.isNumber() && revision.canConvertToExactIntegral() && revision.canConvertToLong()) {
                codexRevisions.put(approval.path("id").stringValue(null), revision.longValue());
            }
        }
        try (var statement = connection.prepareStatement("""
                SELECT scope_project_id, grant_revision FROM codex_digests
                WHERE id=? AND project_id=scope_project_id AND stale=0 AND policy_version=?
                """)) {
            statement.setString(2, SavedCodexDigest.POLICY);
            for (JsonNode id : included) {
                statement.setString(1, id.stringValue());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !Objects.equals(codexRevisions.get(rows.getString("scope_project_id")),
                            rows.getLong("grant_revision"))) {
                        throw new TaskError(410, SELECTION_CHANGED);
                    }
                }
            }
        }
    }

    ObjectNode approvedSelection(Row row) {
        return normalizeSelection(row.selection());
    }

    private ObjectNode normalizeSelection(ObjectNode selection) {
        try {
            return context.normalizeSelection(selection);
        } catch (TaskError invalid) {
            throw new TaskError(410, SELECTION_CHANGED);
        }
    }

    record Row(String id, String scopeKey, String projectName, String kind, String timezone, String model,
               String policy, long revision, boolean enabled, String provider, ObjectNode selection,
               ArrayNode access, String nextPeriodStart, String lastRunId, String note, String updatedAt,
               String enabledAt, String leaseOwner, String leaseUntil) {
    }
}
