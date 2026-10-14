package com.schemaguard.model;

public record RepositoryScanTarget(
        String applicationId,
        String repositoryPath
) {
}
