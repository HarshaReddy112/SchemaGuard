package com.schemaguard.service;

import com.schemaguard.diff.SchemaChange;
import com.schemaguard.diff.SchemaDiffResult;
import com.schemaguard.model.ApplicationDependency;
import com.schemaguard.model.ApplicationImpact;
import com.schemaguard.model.MigrationRiskScore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MigrationImpactAnalysisService {

    private final DependencyRegistryService registry;

    public MigrationImpactAnalysisService(DependencyRegistryService registry) {
        this.registry = registry;
    }

    public List<ApplicationImpact> analyze(
            SchemaDiffResult diff,
            String databaseName) {
        List<ApplicationImpact> impacts = new ArrayList<>();
        for (SchemaChange change : diff.changes()) {
            List<ApplicationDependency> dependencies =
                    registry.findDependencies(
                            change.tableName(),
                            "COLUMN".equals(change.objectType())
                                    ? change.objectName() : null,
                            databaseName
                    );
            if (dependencies.isEmpty()) {
                impacts.add(new ApplicationImpact(
                        null,
                        "Manual verification required",
                        databaseName,
                        change.tableName(),
                        "COLUMN".equals(change.objectType())
                                ? change.objectName() : null,
                        null,
                        null,
                        null,
                        "No registered or scanned dependency matched this schema object.",
                        "NONE",
                        change.changeType(),
                        change.riskLevel()
                ));
                continue;
            }
            for (ApplicationDependency dependency : dependencies) {
                impacts.add(new ApplicationImpact(
                        dependency.applicationId(),
                        dependency.applicationName(),
                        dependency.databaseName(),
                        change.tableName(),
                        "COLUMN".equals(change.objectType())
                                ? change.objectName() : null,
                        dependency.dependencyType(),
                        dependency.apiEndpoint(),
                        dependency.source(),
                        dependency.evidence(),
                        dependency.confidence(),
                        change.changeType(),
                        change.riskLevel()
                ));
            }
        }
        return List.copyOf(impacts);
    }

    public MigrationRiskScore calculateRiskScore(
            SchemaDiffResult diff,
            List<ApplicationImpact> impacts) {
        int severityPoints = diff.changes().stream()
                .mapToInt(change -> severityPoints(change.riskLevel()))
                .max().orElse(0);
        Set<String> affectedApplications = new LinkedHashSet<>();
        impacts.stream()
                .filter(impact -> impact.applicationId() != null)
                .map(ApplicationImpact::applicationId)
                .forEach(affectedApplications::add);
        int exposurePoints = Math.min(15, affectedApplications.size() * 3);

        Map<String, List<Double>> confidenceByApplication = new HashMap<>();
        impacts.stream()
                .filter(impact -> impact.applicationId() != null)
                .forEach(impact -> confidenceByApplication
                        .computeIfAbsent(impact.applicationId(),
                                ignored -> new ArrayList<>())
                        .add(confidenceValue(impact.confidence())));
        double averageConfidence = confidenceByApplication.values().stream()
                .mapToDouble(values -> values.stream()
                        .mapToDouble(Double::doubleValue)
                        .average().orElse(0.0))
                .average().orElse(0.0);
        int confidencePoints = (int) Math.round(averageConfidence * 10);
        int score = Math.min(
                100,
                severityPoints + exposurePoints + confidencePoints
        );

        return new MigrationRiskScore(
                score,
                severityPoints,
                exposurePoints,
                confidencePoints,
                affectedApplications.size(),
                "Score = highest schema-change severity (LOW 25, MEDIUM 55, HIGH 80)"
                        + " + up to 15 points for affected applications (3 each)"
                        + " + up to 10 points for average dependency confidence."
        );
    }

    public List<String> buildMigrationPlan(
            SchemaDiffResult diff,
            List<ApplicationImpact> impacts) {
        List<String> plan = new ArrayList<>();
        if (diff.changes().isEmpty()) {
            return List.of("No schema changes detected.");
        }
        plan.add("Back up the database and validate this migration against a staging copy.");
        long appCount = impacts.stream()
                .filter(impact -> impact.applicationId() != null)
                .map(ApplicationImpact::applicationId)
                .distinct()
                .count();
        if (appCount > 0) {
            plan.add("Coordinate validation with " + appCount
                    + " affected application(s) listed in the impact report.");
        } else {
            plan.add("No dependency evidence was found; manually check all application and API consumers.");
        }
        if (diff.changes().stream()
                .anyMatch(change -> "HIGH".equals(change.riskLevel()))) {
            plan.add("Review high-risk destructive or data-altering changes and prepare a rollback plan.");
        }
        return List.copyOf(plan);
    }

    private static int severityPoints(String risk) {
        return switch (risk == null ? "" : risk.toUpperCase()) {
            case "HIGH" -> 80;
            case "MEDIUM" -> 55;
            case "LOW" -> 25;
            default -> 0;
        };
    }

    private static double confidenceValue(String confidence) {
        return switch (confidence == null ? "" : confidence.toUpperCase()) {
            case "HIGH" -> 1.0;
            case "MEDIUM" -> 0.6;
            case "LOW" -> 0.25;
            default -> 0.0;
        };
    }
}
