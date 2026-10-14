package com.schemaguard.service;

import com.schemaguard.model.ApplicationDependency;
import com.schemaguard.model.ApplicationRegistrationRequest;
import com.schemaguard.model.ApplicationScanCoverage;
import com.schemaguard.model.DependencyRegistrationRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DependencyRegistryService {

    private static final RowMapper<ApplicationDependency> DEPENDENCY_MAPPER =
            (resultSet, rowNumber) -> new ApplicationDependency(
                    resultSet.getString("application_id"),
                    resultSet.getString("application_name"),
                    resultSet.getString("database_name"),
                    resultSet.getString("table_name"),
                    resultSet.getString("column_name"),
                    resultSet.getString("dependency_type"),
                    resultSet.getString("source"),
                    resultSet.getString("evidence"),
                    resultSet.getString("api_endpoint"),
                    resultSet.getString("confidence"),
                    resultSet.getTimestamp("discovered_at").toInstant()
            );

    private final JdbcTemplate jdbcTemplate;

    public DependencyRegistryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void registerApplication(ApplicationRegistrationRequest request) {
        if (request == null
                || isBlank(request.applicationId())
                || isBlank(request.name())
                || isBlank(request.databaseName())) {
            throw new IllegalArgumentException(
                    "applicationId, name, and databaseName are required."
            );
        }

        int updated = jdbcTemplate.update("""
                UPDATE sg_applications
                SET name = ?, database_name = ?, repository = ?,
                    description = ?, updated_at = CURRENT_TIMESTAMP
                WHERE application_id = ?
                """,
                request.name().trim(),
                request.databaseName().trim(),
                blankToNull(request.repository()),
                blankToNull(request.description()),
                request.applicationId().trim());
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO sg_applications (
                        application_id, name, database_name,
                        repository, description
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    request.applicationId().trim(),
                    request.name().trim(),
                    request.databaseName().trim(),
                    blankToNull(request.repository()),
                    blankToNull(request.description()));
        }
    }

    @Transactional
    public List<ApplicationDependency> registerDependency(
            DependencyRegistrationRequest request) {
        validateDependency(request);
        persistDependency(request, false);
        return listDependencies();
    }

    @Transactional
    public void replaceScannedDependencies(
            String applicationId,
            List<DependencyRegistrationRequest> scannedDependencies,
            ApplicationScanCoverage coverage) {
        if (isBlank(applicationId)
                || scannedDependencies == null
                || coverage == null
                || !applicationExists(applicationId)
                || !applicationId.equals(coverage.applicationId())
                || isBlank(coverage.repository())
                || coverage.analyzedFileCount() < 0
                || scannedDependencies.stream().anyMatch(
                        dependency -> dependency == null
                                || !applicationId.equals(
                                        dependency.applicationId()))) {
            throw new IllegalArgumentException(
                    "Scanned dependencies and coverage must belong to a registered application."
            );
        }

        scannedDependencies.forEach(this::validateDependency);
        jdbcTemplate.update(
                "DELETE FROM sg_dependencies WHERE application_id = ? AND scan_generated = TRUE",
                applicationId);
        scannedDependencies.forEach(dependency -> persistDependency(dependency, true));
        jdbcTemplate.update("""
                INSERT INTO sg_scan_coverage (
                    application_id, repository, analyzed_file_count,
                    truncated, scanned_at
                ) VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    repository = VALUES(repository),
                    analyzed_file_count = VALUES(analyzed_file_count),
                    truncated = VALUES(truncated),
                    scanned_at = VALUES(scanned_at)
                """,
                applicationId,
                coverage.repository(),
                coverage.analyzedFileCount(),
                coverage.truncated(),
                Timestamp.from(coverage.scannedAt()));
    }

    public List<ApplicationRegistrationRequest> listApplications() {
        return jdbcTemplate.query("""
                SELECT application_id, name, database_name, repository, description
                FROM sg_applications
                ORDER BY application_id
                """,
                (resultSet, rowNumber) -> new ApplicationRegistrationRequest(
                        resultSet.getString("application_id"),
                        resultSet.getString("name"),
                        resultSet.getString("database_name"),
                        resultSet.getString("repository"),
                        resultSet.getString("description")));
    }

    public Map<String, String> getApplicationNames() {
        Map<String, String> names = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT application_id, name
                FROM sg_applications
                ORDER BY application_id
                """, (resultSet, rowNumber) -> Map.entry(
                        resultSet.getString("application_id"),
                        resultSet.getString("name")))
                .forEach(entry -> names.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(names);
    }

    public ApplicationScanCoverage getScanCoverage(String applicationId) {
        List<ApplicationScanCoverage> coverage = jdbcTemplate.query("""
                SELECT application_id, repository, analyzed_file_count,
                       truncated, scanned_at
                FROM sg_scan_coverage
                WHERE application_id = ?
                """,
                (resultSet, rowNumber) -> new ApplicationScanCoverage(
                        resultSet.getString("application_id"),
                        resultSet.getString("repository"),
                        resultSet.getInt("analyzed_file_count"),
                        resultSet.getBoolean("truncated"),
                        resultSet.getTimestamp("scanned_at").toInstant()),
                applicationId);
        return coverage.stream().findFirst().orElse(null);
    }

    public List<ApplicationDependency> listDependencies() {
        return jdbcTemplate.query("""
                SELECT d.application_id, a.name AS application_name,
                       a.database_name, d.table_name, d.column_name,
                       d.dependency_type, d.source, d.evidence,
                       d.api_endpoint, d.confidence, d.discovered_at
                FROM sg_dependencies d
                JOIN sg_applications a ON a.application_id = d.application_id
                ORDER BY d.id
                """, DEPENDENCY_MAPPER);
    }

    public List<ApplicationDependency> findDependencies(
            String tableName,
            String columnName,
            String databaseName) {
        if (isBlank(tableName)) {
            return List.of();
        }

        String columnFilter = """
                AND (d.column_name IS NULL OR d.column_name = ''
                     OR LOWER(d.column_name) = LOWER(?))
                """;
        if (isBlank(databaseName)) {
            return jdbcTemplate.query("""
                    SELECT d.application_id, a.name AS application_name,
                           a.database_name, d.table_name, d.column_name,
                           d.dependency_type, d.source, d.evidence,
                           d.api_endpoint, d.confidence, d.discovered_at
                    FROM sg_dependencies d
                    JOIN sg_applications a
                      ON a.application_id = d.application_id
                    WHERE LOWER(d.table_name) = LOWER(?)
                    """ + (isBlank(columnName) ? "" : columnFilter)
                    + " ORDER BY d.id",
                    DEPENDENCY_MAPPER,
                    queryParameters(tableName, columnName, databaseName, false));
        }
        return jdbcTemplate.query("""
                SELECT d.application_id, a.name AS application_name,
                       a.database_name, d.table_name, d.column_name,
                       d.dependency_type, d.source, d.evidence,
                       d.api_endpoint, d.confidence, d.discovered_at
                FROM sg_dependencies d
                JOIN sg_applications a ON a.application_id = d.application_id
                WHERE LOWER(d.table_name) = LOWER(?)
                  AND LOWER(a.database_name) = LOWER(?)
                """ + (isBlank(columnName) ? "" : columnFilter)
                + " ORDER BY d.id",
                DEPENDENCY_MAPPER,
                queryParameters(tableName, columnName, databaseName, true));
    }

    private void persistDependency(
            DependencyRegistrationRequest request,
            boolean scanGenerated) {
        String dependencyType = request.dependencyType()
                .trim().toUpperCase(Locale.ROOT);
        String confidence = normalizeConfidence(request.confidence());
        String source = blankToDefault(request.source(), "manual-registry");
        String evidence = blankToDefault(
                request.evidence(), "Manually registered dependency");
        String key = dependencyKey(
                request, dependencyType, source, evidence, scanGenerated);

        int updated = jdbcTemplate.update("""
                UPDATE sg_dependencies
                SET dependency_type = ?, source = ?, evidence = ?,
                    api_endpoint = ?, confidence = ?,
                    discovered_at = CURRENT_TIMESTAMP
                WHERE dependency_key = ?
                """,
                dependencyType,
                source,
                evidence,
                blankToNull(request.apiEndpoint()),
                confidence,
                key);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO sg_dependencies (
                        dependency_key, application_id, table_name,
                        column_name, dependency_type, source, evidence,
                        api_endpoint, confidence, scan_generated
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    key,
                    request.applicationId().trim(),
                    request.tableName().trim(),
                    blankToNull(request.columnName()),
                    dependencyType,
                    source,
                    evidence,
                    blankToNull(request.apiEndpoint()),
                    confidence,
                    scanGenerated);
        }
    }

    private boolean applicationExists(String applicationId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sg_applications WHERE application_id = ?",
                Integer.class,
                applicationId);
        return count != null && count > 0;
    }

    private void validateDependency(DependencyRegistrationRequest request) {
        if (request == null
                || isBlank(request.applicationId())
                || isBlank(request.tableName())
                || isBlank(request.dependencyType())) {
            throw new IllegalArgumentException(
                    "applicationId, tableName, and dependencyType are required."
            );
        }
        if (!applicationExists(request.applicationId())) {
            throw new IllegalArgumentException(
                    "Unknown applicationId: " + request.applicationId()
            );
        }

        String type = request.dependencyType().trim().toUpperCase(Locale.ROOT);
        if (!List.of("READ", "WRITE", "READ_WRITE").contains(type)) {
            throw new IllegalArgumentException(
                    "dependencyType must be READ, WRITE, or READ_WRITE."
            );
        }
        normalizeConfidence(request.confidence());
    }

    private static String normalizeConfidence(String confidence) {
        if (isBlank(confidence)) {
            return "MEDIUM";
        }
        String normalized = confidence.trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "MEDIUM", "HIGH").contains(normalized)) {
            throw new IllegalArgumentException(
                    "confidence must be LOW, MEDIUM, or HIGH."
            );
        }
        return normalized;
    }

    private static String dependencyKey(
            DependencyRegistrationRequest request,
            String dependencyType,
            String source,
            String evidence,
            boolean scanGenerated) {
        String value = String.join("\u0000",
                request.applicationId().trim(),
                normalize(request.tableName()),
                normalize(request.columnName()),
                dependencyType,
                normalize(request.apiEndpoint()),
                normalize(source),
                normalize(evidence),
                Boolean.toString(scanGenerated));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable for dependency identity.", exception);
        }
    }

    private static Object[] queryParameters(
            String tableName,
            String columnName,
            String databaseName,
            boolean includeDatabase) {
        List<Object> parameters = new ArrayList<>();
        parameters.add(tableName);
        if (includeDatabase) {
            parameters.add(databaseName);
        }
        if (!isBlank(columnName)) {
            parameters.add(columnName);
        }
        return parameters.toArray();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }
}
