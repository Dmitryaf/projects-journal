package io.github.dmitryaf.projectsjournal;

import java.sql.SQLException;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class WorkspaceView {

    private final SqliteStore store;
    private final JsonMapper mapper;
    private final JournalSettings settings;
    private final ProjectService projects;
    private final ProjectRefresh refresh;
    private final ProjectContextService contexts;
    private final TaskService tasks;
    private final JournalRecords records;
    private final CheckService checks;
    private final ProposalAcceptanceService acceptances;

    WorkspaceView(SqliteStore store, JsonMapper mapper, JournalSettings settings, ProjectService projects,
                  ProjectRefresh refresh, ProjectContextService contexts, TaskService tasks, JournalRecords records,
                  CheckService checks, ProposalAcceptanceService acceptances) {
        this.store = store;
        this.mapper = mapper;
        this.settings = settings;
        this.projects = projects;
        this.refresh = refresh;
        this.contexts = contexts;
        this.tasks = tasks;
        this.records = records;
        this.checks = checks;
        this.acceptances = acceptances;
    }

    ObjectNode read() throws SQLException {
        try (var connection = store.open()) {
            var result = mapper.createObjectNode();
            var configuration = settings.read(connection);
            var projectList = projects.list(connection);
            var entries = checks.entries(connection);
            result.set("projects", mapper.valueToTree(projectList));
            result.set("tasks", mapper.valueToTree(tasks.list(connection)));
            result.set("ideas", mapper.valueToTree(records.list(connection, "ideas")));
            result.set("decisions", mapper.valueToTree(records.list(connection, "decisions")));
            result.set("relations", mapper.valueToTree(records.relations(connection)));
            result.set("settings", configuration);
            result.set("scan", projects.scanInfo());
            result.set("refresh", refresh.info());
            result.set("checks", checks.views(connection, projectList, configuration, entries));
            result.set("checkEntries", entries);
            result.set("fileDecisions", mapper.valueToTree(contexts.decisions()));
            result.putObject("storage").put("databasePath", store.path().toString()).put("taskProjects", true);
            result.set("proposalLinks", acceptances.links(connection));
            return result;
        }
    }

}
