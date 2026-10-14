package com.schemaguard.model;

public record MigrationRiskScore(
        int score,
        int schemaSeverityPoints,
        int dependencyExposurePoints,
        int dependencyConfidencePoints,
        int affectedApplicationCount,
        String explanation
) {
}
