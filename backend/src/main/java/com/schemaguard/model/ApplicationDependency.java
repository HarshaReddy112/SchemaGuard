package com.schemaguard.model;

import java.time.Instant;

public record ApplicationDependency(
        String applicationId,
        String applicationName,
        String tableName,
        String columnName,
        String dependencyType,
        String source,
        String evidence,
        String confidence,
        String detector,
        Instant createdAt
) {
}
