package com.schemaguard.service;

import com.schemaguard.model.ApplicationDependency;
import com.schemaguard.model.ApplicationRegistrationRequest;
import com.schemaguard.model.DependencyEvidence;
import com.schemaguard.model.DependencyRegistrationRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DependencyRegistryService {

    private final Map<String, String> applications = new LinkedHashMap<>();
    private final Map<String, List<ApplicationDependency>> dependencies = new LinkedHashMap<>();

    public synchronized void registerApplication(ApplicationRegistrationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Application registration request is required.");
        }
        if (request.applicationId() == null || request.applicationId().isBlank()) {
            throw new IllegalArgumentException("applicationId is required.");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("name is required.");
        }
        if (request.databaseName() == null || request.databaseName().isBlank()) {
            throw new IllegalArgumentException("databaseName is required.");
        }

        applications.put(request.applicationId().trim(), request.name().trim());
    }

    public synchronized List<ApplicationDependency> listDependencies() {
        List<ApplicationDependency> all = new ArrayList<>();
        dependencies.values().forEach(all::addAll);
        return List.copyOf(all);
    }

    public synchronized void registerDependency(DependencyRegistrationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Dependency registration request is required.");
        }
        if (request.applicationId() == null || request.applicationId().isBlank()) {
            throw new IllegalArgumentException("applicationId is required.");
        }
        if (!applications.containsKey(request.applicationId())) {
            throw new IllegalArgumentException("Unknown applicationId: " + request.applicationId());
        }
        if (request.tableName() == null || request.tableName().isBlank()) {
            throw new IllegalArgumentException("tableName is required.");
        }
        if (request.dependencyType() == null || request.dependencyType().isBlank()) {
            throw new IllegalArgumentException("dependencyType is required.");
        }

        String key = dependencyKey(request.applicationId(), request.tableName(), request.columnName());
        ApplicationDependency dependency = new ApplicationDependency(
                request.applicationId(),
                applications.get(request.applicationId()),
                request.tableName(),
                request.columnName(),
                request.dependencyType(),
                request.source(),
                request.evidence(),
                request.confidence() == null || request.confidence().isBlank()
                        ? "MEDIUM"
                        : request.confidence(),
                "manual-registry",
                Instant.now()
        );

        dependencies.computeIfAbsent(key, ignored -> new ArrayList<>()).add(dependency);
    }

    public synchronized void replaceScannedDependencies(
            String applicationId,
            List<DependencyEvidence> evidenceList) {
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("applicationId is required.");
        }
        if (!applications.containsKey(applicationId)) {
            throw new IllegalArgumentException("Unknown applicationId: " + applicationId);
        }

        dependencies.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(dependency ->
                    applicationId.equals(dependency.applicationId())
                            && !"manual-registry".equals(dependency.detector()));
            return entry.getValue().isEmpty();
        });

        if (evidenceList == null) {
            return;
        }

        for (DependencyEvidence evidence : evidenceList) {
            if (evidence == null || evidence.tableName() == null || evidence.tableName().isBlank()) {
                continue;
            }

            String key = dependencyKey(applicationId, evidence.tableName(), evidence.columnName());
            ApplicationDependency dependency = new ApplicationDependency(
                    applicationId,
                    applications.get(applicationId),
                    evidence.tableName(),
                    evidence.columnName(),
                    "USES",
                    evidence.sourceLocation(),
                    evidence.snippet(),
                    evidence.confidence() == null || evidence.confidence().isBlank()
                            ? "MEDIUM"
                            : evidence.confidence(),
                    evidence.detector(),
                    Instant.now()
            );
            dependencies.computeIfAbsent(key, ignored -> new ArrayList<>()).add(dependency);
        }
    }

    public synchronized Map<String, String> getApplicationNames() {
        return new LinkedHashMap<>(applications);
    }

    private String dependencyKey(String applicationId, String tableName, String columnName) {
        return applicationId + ":" + tableName + ":" + (columnName == null ? "" : columnName);
    }
}
