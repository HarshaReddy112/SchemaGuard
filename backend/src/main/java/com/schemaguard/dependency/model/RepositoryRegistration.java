package com.schemaguard.dependency.model;

import java.time.Instant;

public record RepositoryRegistration(
        String repositoryId,
        String repositoryName,
        String repositoryUrl,
        String localPath,
        String primaryLanguage,
        Instant registeredAt
) {
}
