package com.schemaguard.model;

public record DependencyScanRequest(
        String applicationId,
        String repositoryPath
) {
}
