package io.github.dmitryaf.projectsjournal;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

record ScheduledRun(String id, long revision, String key, List<String> limitations) {

    String existing(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT id FROM analysis_runs WHERE automatic_key=?")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    return rows.getString(1);
                }
            }
        }
        try (var statement = connection.prepareStatement("SELECT run_key FROM analysis_schedule_periods WHERE automatic_key=?")) {
            statement.setString(1, key);
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw new TaskError(409, "Запуск этого периода уже был создан и удалён. Автоматического повтора нет.");
                }
            }
        }
        return null;
    }

    void record(Connection connection, String runId) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO analysis_schedule_periods(automatic_key,run_key) VALUES(?,?)")) {
            statement.setString(1, key);
            statement.setString(2, runId);
            statement.executeUpdate();
        }
    }
}
