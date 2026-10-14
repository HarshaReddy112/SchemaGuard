package com.schemaguard.dependency.model;

import java.time.Instant;
import java.util.List;

public record RepositoryScanResult(
        String repositoryId,
        String repositoryName,
        Instant scannedAt,
        int filesScanned,
        int referencesFound,
        List<DependencyEvidence> dependencies,
        List<String> warnings
) {
    public RepositoryScanResult {
        dependencies = List.copyOf(dependencies);
        warnings = List.copyOf(warnings);
    }
}
