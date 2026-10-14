package com.schemaguard.model;

import java.util.List;

public record DependencyScanResult(
        String applicationId,
        int scannedFiles,
        long scannedBytes,
        int dependenciesRegistered,
        boolean truncated,
        List<ApplicationDependency> dependencies
) {
    public DependencyScanResult(
            String applicationId,
            int scannedFiles,
            int dependenciesRegistered,
            boolean truncated,
            List<ApplicationDependency> dependencies) {
        this(applicationId, scannedFiles, 0, dependenciesRegistered,
                truncated, dependencies);
    }

    public DependencyScanResult {
        dependencies = List.copyOf(dependencies);
    }
}
