package com.schemaguard.dependency.model;

public record RepositoryRegistrationRequest(
        String repositoryId,
        String repositoryName,
        String repositoryUrl,
        String localPath,
        String primaryLanguage
) {
}
