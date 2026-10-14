package com.schemaguard.model;

import java.util.Map;

public record MigrationRiskScore(
        int score,
        String riskLevel,
        int schemaSeverityPoints,
        int applicationExposurePoints,
        int componentExposurePoints,
        int dependencyExposurePoints,
        int dependencyConfidencePoints,
        int affectedApplicationCount,
        int affectedApiCount,
        int affectedComponentCount,
        String explanation,
        String targetMetadataStatus,
        String targetDatabaseVersion,
        Map<String, Long> affectedTableSizesBytes,
        int tableSizeRiskPoints,
        int ddlAlgorithmRiskPoints,
        String ddlAlgorithmAssessment
) {
    public MigrationRiskScore {
        affectedTableSizesBytes = Map.copyOf(affectedTableSizesBytes);
    }
}
