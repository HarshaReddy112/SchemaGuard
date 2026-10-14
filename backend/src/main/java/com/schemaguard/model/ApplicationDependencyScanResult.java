package com.schemaguard.model;

public record ApplicationDependencyScanResult(
        String applicationId,
        String applicationName,
        DependencyScanResult scanResult
) {
}
