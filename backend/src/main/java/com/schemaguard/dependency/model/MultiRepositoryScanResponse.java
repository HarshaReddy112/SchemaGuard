package com.schemaguard.dependency.model;

import java.util.List;

public record MultiRepositoryScanResponse(
        int repositoriesScanned,
        int filesScanned,
        int referencesFound,
        List<RepositoryScanResult> results,
        List<String> warnings
) {
    public MultiRepositoryScanResponse {
        results = List.copyOf(results);
        warnings = List.copyOf(warnings);
    }
}
