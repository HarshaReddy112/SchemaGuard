package com.schemaguard.model;

import java.time.Instant;

public record ApplicationDependency(
        String applicationId,
        String applicationName,
        String databaseName,
        String tableName,
        String columnName,
        String dependencyType,
        String source,
        String evidence,
        String apiEndpoint,
        String confidence,
        Instant discoveredAt
) {
}
