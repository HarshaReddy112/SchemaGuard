package com.schemaguard.service;

import com.schemaguard.diff.SchemaChange;
import com.schemaguard.diff.SchemaDiffResult;
import com.schemaguard.model.ApplicationImpact;
import com.schemaguard.model.DependencyGraphEdge;
import com.schemaguard.model.DependencyGraphNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ApplicationImpactAnalysisService {

    public List<ApplicationImpact> analyzeImpact(SchemaDiffResult diffResult, DependencyGraphService graphService) {
        if (diffResult == null || diffResult.changes() == null || graphService == null) {
            return List.of();
        }

        List<ApplicationImpact> impacts = new ArrayList<>();
        for (SchemaChange change : diffResult.changes()) {
            String tableName = change.tableName();
            String objectName = change.objectName();
            List<String> dependentApplications = new ArrayList<>();

            if ("COLUMN".equals(change.objectType())
                    && objectName != null && !objectName.isBlank()) {
                dependentApplications.addAll(graphService.findDependentApplications(tableName, objectName));
            }
            if (dependentApplications.isEmpty()) {
                dependentApplications.addAll(graphService.findDependentApplications(tableName, null));
            }

            if (dependentApplications.isEmpty()) {
                impacts.add(new ApplicationImpact(
                        null,
                        "Manual verification required",
                        tableName,
                        objectName,
                        null,
                        null,
                        "UNKNOWN",
                        change.riskLevel(),
                        "manual verification required: no direct application dependency was discovered for this schema change; this does not prove that no dependency exists.",
                        "Review all registered repositories, dynamic SQL, ORM mappings, stored procedures, and integration tests before deployment."
                ));
                continue;
            }

            Set<String> seen = new LinkedHashSet<>();
            for (String applicationId : dependentApplications) {
                if (!seen.add(applicationId)) {
                    continue;
                }
                DependencyGraphNode applicationNode = graphService.getNodes().get(applicationId);
                String applicationName = applicationNode == null ? applicationId : applicationNode.name();
                DependencyGraphEdge edge = graphService.findEdgeFromApplicationToObject(applicationId, tableName, objectName);
                if (edge == null && "COLUMN".equals(change.objectType())) {
                    edge = graphService.findEdgeFromApplicationToObject(applicationId, tableName, null);
                }

                impacts.add(new ApplicationImpact(
                        applicationId,
                        applicationName,
                        tableName,
                        objectName,
                        edge == null ? "USES" : edge.type(),
                        edge == null ? null : edge.evidence(),
                        edge == null ? "UNKNOWN" : edge.confidence(),
                        change.riskLevel(),
                        "Static or registered dependency evidence connects this application to the affected table or column.",
                        "Review the cited source reference, update the dependent component if needed, and run migration validation before rollout."
                ));
            }
        }

        return impacts;
    }
}
