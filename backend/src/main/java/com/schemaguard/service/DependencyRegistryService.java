package com.schemaguard.service;

import com.schemaguard.model.ApplicationDependency;
import com.schemaguard.model.ApplicationRegistrationRequest;
import com.schemaguard.model.DependencyRegistrationRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DependencyRegistryService {

    private final Map<String, ApplicationRegistrationRequest> applications =
            new LinkedHashMap<>();
    private final Map<String, ApplicationDependency> dependencies =
            new LinkedHashMap<>();

    public synchronized void registerApplication(
            ApplicationRegistrationRequest request) {
        if (request == null
                || isBlank(request.applicationId())
                || isBlank(request.name())
                || isBlank(request.databaseName())) {
            throw new IllegalArgumentException(
                    "applicationId, name, and databaseName are required."
            );
        }

        applications.put(request.applicationId(), request);
    }

    public synchronized List<ApplicationDependency> registerDependency(
            DependencyRegistrationRequest request) {
        if (request == null
                || isBlank(request.applicationId())
                || isBlank(request.tableName())
                || isBlank(request.dependencyType())) {
            throw new IllegalArgumentException(
                    "applicationId, tableName, and dependencyType are required."
            );
        }
        ApplicationRegistrationRequest application =
                applications.get(request.applicationId());
        if (application == null) {
            throw new IllegalArgumentException(
                    "Unknown applicationId: " + request.applicationId()
            );
        }

        String dependencyType = request.dependencyType()
                .trim().toUpperCase(Locale.ROOT);
        if (!List.of("READ", "WRITE", "READ_WRITE").contains(dependencyType)) {
            throw new IllegalArgumentException(
                    "dependencyType must be READ, WRITE, or READ_WRITE."
            );
        }
        String confidence = normalizeConfidence(request.confidence());
        String key = String.join("|",
                request.applicationId(),
                normalize(request.tableName()),
                normalize(request.columnName()),
                dependencyType,
                normalize(request.apiEndpoint()),
                normalize(request.source()),
                normalize(request.evidence()));
        dependencies.put(key, new ApplicationDependency(
                request.applicationId(),
                application.name(),
                application.databaseName(),
                request.tableName().trim(),
                blankToNull(request.columnName()),
                dependencyType,
                blankToDefault(request.source(), "manual-registry"),
                blankToDefault(request.evidence(), "Manually registered dependency"),
                blankToNull(request.apiEndpoint()),
                confidence,
                Instant.now()
        ));
        return listDependencies();
    }

    public synchronized List<ApplicationRegistrationRequest> listApplications() {
        return List.copyOf(applications.values());
    }

    public synchronized List<ApplicationDependency> listDependencies() {
        return List.copyOf(dependencies.values());
    }

    public synchronized List<ApplicationDependency> findDependencies(
            String tableName,
            String columnName,
            String databaseName) {
        String normalizedTable = normalize(tableName);
        String normalizedColumn = normalize(columnName);
        List<ApplicationDependency> matches = new ArrayList<>();
        for (ApplicationDependency dependency : dependencies.values()) {
            if (!normalize(dependency.tableName()).equals(normalizedTable)) {
                continue;
            }
            if (!isBlank(databaseName)
                    && !normalize(dependency.databaseName())
                    .equals(normalize(databaseName))) {
                continue;
            }
            String dependencyColumn = normalize(dependency.columnName());
            if (!normalizedColumn.isEmpty()
                    && !dependencyColumn.isEmpty()
                    && !dependencyColumn.equals(normalizedColumn)) {
                continue;
            }
            matches.add(dependency);
        }
        return List.copyOf(matches);
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
