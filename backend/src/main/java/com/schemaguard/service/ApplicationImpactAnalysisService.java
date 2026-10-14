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
        if (diffResult == null || diffResult.changes() == null) {
            return List.of();
        }
        if (graphService == null) {
            return List.of();
        }

        List<ApplicationImpact> impacts = new ArrayList<>();
        for (SchemaChange change : diffResult.changes()) {
            String tableName = change.tableName();
            String objectName = change.objectName();
            List<String> dependentApplications = new ArrayList<>();

            if (change.objectType() != null && "COLUMN".equals(change.objectType())
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
                        "LOW",
                        change.riskLevel(),
                        "No direct application dependency was discovered for this schema change; review manually.",
                        "Validate the change against application SQL, ORM mappings, and integration tests before deployment."
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
                impacts.add(new ApplicationImpact(
                        applicationId,
                        applicationName,
                        tableName,
                        objectName,
                        edge == null ? "READ" : edge.type(),
                        edge == null ? "manual registry" : edge.evidence(),
                        edge == null ? "MEDIUM" : edge.confidence(),
                        change.riskLevel(),
                        "The application is connected to the affected table or column through dependency evidence.",
                        "Review impacted application queries and run migration validation before rollout."
                ));
            }
        }

        return impacts;
    }
}
