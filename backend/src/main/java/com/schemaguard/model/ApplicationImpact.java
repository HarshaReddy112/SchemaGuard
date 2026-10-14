package com.schemaguard.model;

public record ApplicationImpact(
        String applicationId,
        String applicationName,
        String databaseName,
        String tableName,
        String columnName,
        String dependencyType,
        String apiEndpoint,
        String source,
        String evidence,
        String confidence,
        String schemaChangeType,
        String changeRisk
) {
}
