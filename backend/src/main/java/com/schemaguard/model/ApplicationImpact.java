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
    public ApplicationImpact(
            String applicationId,
            String applicationName,
            String tableName,
            String columnName,
            String dependencyType,
            String source,
            String confidence,
            String schemaChangeType,
            String evidence,
            String changeRisk
    ) {
        this(
                applicationId,
                applicationName,
                null,
                tableName,
                columnName,
                dependencyType,
                null,
                source,
                evidence,
                confidence,
                schemaChangeType,
                changeRisk
        );
    }

    public String changedTable() {
        return tableName;
    }

    public String reason() {
        return evidence;
    }
}
