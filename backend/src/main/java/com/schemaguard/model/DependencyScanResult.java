package com.schemaguard.model;

import java.util.List;

public record DependencyScanResult(
        List<DependencyEvidence> evidence,
        int scannedFiles,
        long scannedBytes,
        String repositoryPath,
        boolean truncated
) {
}
