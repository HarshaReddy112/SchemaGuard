package com.schemaguard.model;

public record ApplicationRegistrationRequest(
        String applicationId,
        String name,
        String databaseName,
        String repository,
        String description
) {
    public ApplicationRegistrationRequest(
            String applicationId,
            String name,
            String databaseName
    ) {
        this(applicationId, name, databaseName, null, null);
    }
}
