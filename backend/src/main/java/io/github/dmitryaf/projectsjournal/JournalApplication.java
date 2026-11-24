package io.github.dmitryaf.projectsjournal;

import java.sql.SQLException;
import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootApplication
public class JournalApplication {

    public static void main(String[] args) throws Exception {
        if (Arrays.equals(args, new String[]{"--mcp"})) {
            System.setOut(System.err);
            var mapper = JsonMapper.builder().build();
            TaskMcpServer.run(SqliteStore.fromEnvironment(), mapper);
            return;
        }
        boolean restore = Arrays.equals(args, new String[]{"--restore-recovery"});
        if (args.length != 0 && !restore) {
            throw new IllegalArgumentException("Поддерживается запуск без аргументов, с --mcp или с --restore-recovery.");
        }
        SpringApplication.run(JournalApplication.class,
                "--server.address=127.0.0.1", "--server.port=" + port(), "--journal.restore-recovery=" + restore);
    }

    private static int port() {
        var configured = System.getenv().getOrDefault("PORT", "4310");
        try {
            var port = Integer.parseInt(configured.strip());
            if (port >= 1024 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // Report the same configuration error for malformed and out-of-range values.
        }
        throw new IllegalArgumentException("PORT должен быть от 1024 до 65535.");
    }

    @Bean
    SqliteStore sqliteStore(@Value("${journal.restore-recovery:false}") boolean restore) throws SQLException {
        var store = SqliteStore.fromEnvironment();
        if (restore) {
            store.revokeRestoredApprovals();
        }
        return store;
    }

    @Bean
    TaskService taskService(SqliteStore store, JsonMapper mapper) {
        return new TaskService(store, mapper);
    }

    @Bean
    TaskHistoryService taskHistory(SqliteStore store, JsonMapper mapper) {
        return new TaskHistoryService(store, mapper);
    }

    @Bean
    ProjectFiles projectFiles(JsonMapper mapper) {
        return new ProjectFiles(mapper);
    }

    @Bean
    ProjectContextService projectContexts(SqliteStore store, JsonMapper mapper, ProjectFiles files,
                                          JournalSettings settings) {
        return new ProjectContextService(store, mapper, files, settings);
    }

    @Bean
    ProjectService projectService(SqliteStore store, JsonMapper mapper, JournalSettings settings,
                                  ProjectFiles files, ProjectContextService contexts, TaskService tasks) {
        return new ProjectService(store, mapper, settings, files, contexts, tasks);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    ProjectRefresh projectRefresh(ProjectService projects, JournalSettings settings, JsonMapper mapper) {
        return new ProjectRefresh(projects, settings, mapper);
    }
}
