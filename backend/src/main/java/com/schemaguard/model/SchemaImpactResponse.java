package com.schemaguard.model;

import com.schemaguard.diff.SchemaDiffResult;

import java.util.List;

public record SchemaImpactResponse(
        SchemaDiffResult diffResult,
        List<ApplicationImpact> impacts,
        MigrationRiskScore riskScore,
        List<String> migrationPlan
) {
    public SchemaImpactResponse {
        impacts = List.copyOf(impacts);
        migrationPlan = List.copyOf(migrationPlan);
    }
}
