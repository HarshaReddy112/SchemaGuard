package com.schemaguard.model;

import java.util.List;

public record MultiRepositoryDependencyScanResult(
        int repositoriesScanned,
        int scannedFiles,
        long scannedBytes,
        int referencesFound,
        List<ApplicationDependencyScanResult> results,
        List<String> warnings
) {
    public MultiRepositoryDependencyScanResult {
        results = List.copyOf(results);
        warnings = List.copyOf(warnings);
    }
}
