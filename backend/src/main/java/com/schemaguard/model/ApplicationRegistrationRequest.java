package com.schemaguard.model;

public record ApplicationRegistrationRequest(
        String applicationId,
        String name,
        String databaseName
) {
}
