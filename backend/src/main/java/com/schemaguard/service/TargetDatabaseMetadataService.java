package com.schemaguard.service;

import com.schemaguard.parser.MySqlSchemaParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class TargetDatabaseMetadataService {

    private static final MySqlSchemaParser SQL_PARSER =
            new MySqlSchemaParser();

    private final String url;
    private final String username;
    private final String password;

    public TargetDatabaseMetadataService(
            @Value("${schemaguard.target-database.url:}") String url,
            @Value("${schemaguard.target-database.username:}") String username,
            @Value("${schemaguard.target-database.password:}") String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    public Snapshot read(String databaseName, List<String> tableNames) {
        if (isBlank(url)) {
            return new Snapshot("NOT_CONFIGURED", null, Map.of());
        }
        if (isBlank(databaseName)) {
            throw new IllegalArgumentException(
                    "databaseName is required to read target metadata.");
        }

        Map<String, Long> tableSizes = new LinkedHashMap<>();
        try (Connection connection = openReadOnlyConnection()) {
            String serverVersion;
            try (PreparedStatement statement =
                         connection.prepareStatement("SELECT VERSION()");
                 ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException(
                            "Target database did not return its server version.");
                }
                serverVersion = resultSet.getString(1);
            }

            for (String tableName : tableNames.stream().distinct().toList()) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT COUNT(*),
                               COALESCE(SUM(
                                   COALESCE(data_length, 0)
                                   + COALESCE(index_length, 0)
                               ), 0)
                        FROM information_schema.tables
                        WHERE table_schema = ? AND table_name = ?
                        """)) {
                    statement.setString(1, databaseName);
                    statement.setString(2, tableName);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new SQLException(
                                    "Target database did not return table metadata.");
                        }
                        if (resultSet.getLong(1) > 0) {
                            tableSizes.put(tableName, resultSet.getLong(2));
                        }
                    }
                }
            }
            return new Snapshot("AVAILABLE_READ_ONLY", serverVersion, tableSizes);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Unable to read target MySQL metadata. Verify that "
                            + "TARGET_DB_URL points to the target database and "
                            + "the configured account has read-only metadata access.",
                    exception
            );
        }
    }

    static int tableSizeRiskPoints(long largestTableBytes) {
        long megabyte = 1024L * 1024;
        long gigabyte = 1024L * megabyte;
        if (largestTableBytes >= 100 * gigabyte) {
            return 10;
        }
        if (largestTableBytes >= 10 * gigabyte) {
            return 7;
        }
        if (largestTableBytes >= gigabyte) {
            return 4;
        }
        if (largestTableBytes >= 100 * megabyte) {
            return 2;
        }
        return 0;
    }

    static AlgorithmAssessment assessDdlAlgorithm(String migrationSql) {
        if (isBlank(migrationSql)) {
            return new AlgorithmAssessment(
                    "NOT_PROVIDED",
                    0,
                    "Migration SQL was not provided; the target MySQL server "
                            + "selects the default DDL algorithm."
            );
        }

        Set<String> algorithms = new LinkedHashSet<>();
        algorithms.addAll(SQL_PARSER.explicitAlterAlgorithmHints(migrationSql));
        if (algorithms.isEmpty()) {
            return new AlgorithmAssessment(
                    "NOT_SPECIFIED",
                    0,
                    "No explicit ALGORITHM hint was found; the target MySQL "
                            + "server selects the default DDL algorithm."
            );
        }

        int riskPoints = algorithms.stream()
                .mapToInt(algorithm -> switch (algorithm) {
                    case "COPY" -> 10;
                    case "INPLACE" -> 4;
                    default -> 0;
                })
                .max().orElse(0);
        return new AlgorithmAssessment(
                String.join(", ", algorithms),
                riskPoints,
                "Explicit MySQL ALGORITHM hint(s) found in migration SQL; "
                        + "risk uses the highest-cost hinted algorithm."
        );
    }

    private Connection openReadOnlyConnection() throws SQLException {
        Connection connection;
        if (isBlank(username) && isBlank(password)) {
            connection = DriverManager.getConnection(url);
        } else {
            connection = DriverManager.getConnection(
                    url, username, password);
        }
        try {
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException exception) {
            connection.close();
            throw exception;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record Snapshot(
            String status,
            String serverVersion,
            Map<String, Long> tableSizesBytes) {
        public Snapshot {
            tableSizesBytes = Map.copyOf(tableSizesBytes);
        }
    }

    record AlgorithmAssessment(String value, int riskPoints, String explanation) {
    }
}
