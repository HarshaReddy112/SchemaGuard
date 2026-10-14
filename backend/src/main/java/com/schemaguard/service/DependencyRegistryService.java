package com.schemaguard.service;

import com.schemaguard.model.ApplicationDependency;
import com.schemaguard.model.ApplicationRegistrationRequest;
import com.schemaguard.model.DependencyRegistrationRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DependencyRegistryService {

    private final Map<String, String> applications = new LinkedHashMap<>();
    private final Map<String, List<ApplicationDependency>> dependencies = new LinkedHashMap<>();

    public void registerApplication(ApplicationRegistrationRequest request) {
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

        applications.put(request.applicationId(), request.name());
    }

    public List<ApplicationDependency> listDependencies() {
        List<ApplicationDependency> all = new ArrayList<>();
        dependencies.values().forEach(all::addAll);
        return all;
    }

    public void registerDependency(DependencyRegistrationRequest request) {
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

        String key = request.applicationId() + ":" + request.tableName() + ":" + request.columnName();
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

    public Map<String, String> getApplicationNames() {
        return new LinkedHashMap<>(applications);
    }
}
