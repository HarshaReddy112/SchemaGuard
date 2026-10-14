package com.schemaguard.dependency.model;

public record DependencyEvidence(
        String repositoryId,
        String repositoryName,
        String filePath,
        int lineNumber,
        String databaseObjectType,
        String tableName,
        String columnName,
        String detectionMethod,
        String confidence,
        String matchedText
) {
}
