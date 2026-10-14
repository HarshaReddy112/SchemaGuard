package com.schemaguard.model;

public record ApplicationImpact(
        String applicationId,
        String applicationName,
        String changedTable,
        String changedColumn,
        String dependencyType,
        String sourceEvidence,
        String confidence,
        String riskInherited,
        String reason,
        String recommendedAction
) {
}
