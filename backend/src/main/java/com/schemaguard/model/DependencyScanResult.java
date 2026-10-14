package com.schemaguard.model;

import java.util.List;

public record DependencyScanResult(
        String applicationId,
        int scannedFiles,
        int dependenciesRegistered,
        boolean truncated,
        List<ApplicationDependency> dependencies
) {
    public DependencyScanResult {
        dependencies = List.copyOf(dependencies);
    }
}
