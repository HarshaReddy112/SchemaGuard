package com.schemaguard.model;

public record ApplicationRegistrationRequest(
        String applicationId,
        String name,
        String repository,
        String databaseName,
        String description
) {
}
