package com.schemaguard.model;

public record DependencyEvidence(
        String tableName,
        String columnName,
        String sourceLocation,
        String snippet,
        String detector,
        String confidence
) {
}
