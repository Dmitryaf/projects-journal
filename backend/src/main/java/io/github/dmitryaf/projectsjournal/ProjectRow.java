package io.github.dmitryaf.projectsjournal;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

record ProjectRow(String id, String path, String snapshot, String metadata, String notes,
                  boolean available, String yamlBaseHash, String visibility, String privateContextPath,
                  boolean hadPrivateRecords, String publicationAllowlist) {

    static ProjectRow require(Connection connection, String id) throws SQLException {
        ProjectRow row = find(connection, id);
        if (row == null) {
            throw new TaskError(404, "Проект не найден.");
        }
        return row;
    }

    static ProjectRow find(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM projects WHERE id=?")) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? from(rows) : null;
            }
        }
    }

    static List<ProjectRow> list(Connection connection) throws SQLException {
        List<ProjectRow> projects = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT * FROM projects ORDER BY path");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                projects.add(from(rows));
            }
        }
        return projects;
    }

    private static ProjectRow from(ResultSet row) throws SQLException {
        return new ProjectRow(row.getString("id"), row.getString("path"), row.getString("snapshot"),
                row.getString("metadata"), row.getString("notes"), row.getBoolean("available"),
                row.getString("yaml_base_hash"), row.getString("visibility"),
                row.getString("private_context_path"), row.getBoolean("private_context_had_records"),
                row.getString("publication_allowlist"));
    }
}
