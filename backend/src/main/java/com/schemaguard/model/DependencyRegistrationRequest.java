package com.schemaguard.model;

public record DependencyRegistrationRequest(
        String applicationId,
        String tableName,
        String columnName,
        String dependencyType,
        String source,
        String evidence,
        String confidence,
        String apiEndpoint
) {
    public DependencyRegistrationRequest(
            String applicationId,
            String tableName,
            String columnName,
            String dependencyType,
            String source,
            String evidence,
            String confidence
    ) {
        this(applicationId, tableName, columnName, dependencyType,
                source, evidence, confidence, null);
    }
}
