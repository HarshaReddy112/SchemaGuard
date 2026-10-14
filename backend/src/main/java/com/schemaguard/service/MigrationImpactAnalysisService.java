package com.schemaguard.service;

import com.schemaguard.diff.SchemaChange;
import com.schemaguard.diff.SchemaDiffResult;
import com.schemaguard.model.ApplicationDependency;
import com.schemaguard.model.ApplicationImpact;
import com.schemaguard.model.ApplicationImpactSummary;
import com.schemaguard.model.MigrationRiskScore;
import com.schemaguard.model.ApplicationRegistrationRequest;
import com.schemaguard.model.ApplicationScanCoverage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

@Service
public class MigrationImpactAnalysisService {

    private final DependencyRegistryService registry;
    private final TargetDatabaseMetadataService targetMetadataService;

    public MigrationImpactAnalysisService(
            DependencyRegistryService registry,
            TargetDatabaseMetadataService targetMetadataService) {
        this.registry = registry;
        this.targetMetadataService = targetMetadataService;
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

    public List<ApplicationImpactSummary> summarizeApplications(
            String databaseName,
            boolean hasChanges,
            List<ApplicationImpact> impacts) {
        String normalizedDatabase = databaseName.trim().toLowerCase(Locale.ROOT);
        List<ApplicationImpactSummary> summaries = new ArrayList<>();
        for (ApplicationRegistrationRequest application
                : registry.listApplications()) {
            if (!application.databaseName().trim().toLowerCase(Locale.ROOT)
                    .equals(normalizedDatabase)) {
                continue;
            }
            List<ApplicationImpact> applicationImpacts = impacts.stream()
                    .filter(impact -> application.applicationId()
                            .equals(impact.applicationId()))
                    .toList();
            ApplicationScanCoverage coverage =
                    registry.getScanCoverage(application.applicationId());
            String status = !hasChanges
                    ? "NO_CHANGES_TO_EVALUATE"
                    : !applicationImpacts.isEmpty()
                            ? "POTENTIALLY_AFFECTED"
                            : coverage == null
                                    ? "NOT_SCANNED"
                                    : "NO_REFERENCE_FOUND";
            summaries.add(new ApplicationImpactSummary(
                    application.applicationId(),
                    application.name(),
                    application.databaseName(),
                    status,
                    coverage == null ? null : coverage.repository(),
                    coverage == null ? 0 : coverage.analyzedFileCount(),
                    coverage != null && coverage.truncated(),
                    coverage == null ? null : coverage.scannedAt(),
                    applicationImpacts
            ));
        }
        return List.copyOf(summaries);
    }

    public MigrationRiskScore calculateRiskScore(
            SchemaDiffResult diff,
            List<ApplicationImpact> impacts,
            String databaseName,
            String migrationSql) {
        TargetDatabaseMetadataService.Snapshot targetMetadata =
                targetMetadataService.read(
                        databaseName,
                        diff.changes().stream()
                                .map(SchemaChange::tableName)
                                .distinct()
                                .toList());
        long largestTableBytes = targetMetadata.tableSizesBytes().values()
                .stream().mapToLong(Long::longValue).max().orElse(0L);
        int tableSizeRiskPoints =
                TargetDatabaseMetadataService.tableSizeRiskPoints(
                        largestTableBytes);
        TargetDatabaseMetadataService.AlgorithmAssessment algorithmAssessment =
                TargetDatabaseMetadataService.assessDdlAlgorithm(migrationSql);
        int severityPoints = diff.changes().stream()
                .mapToInt(change -> severityPoints(change.riskLevel()))
                .max().orElse(0);
        Set<String> affectedApplications = new LinkedHashSet<>();
        Set<String> affectedComponents = new LinkedHashSet<>();
        Set<String> affectedApis = new LinkedHashSet<>();
        impacts.stream()
                .filter(impact -> impact.applicationId() != null)
                .forEach(impact -> {
                    affectedApplications.add(impact.applicationId());
                    if (impact.apiEndpoint() != null
                            && !impact.apiEndpoint().isBlank()) {
                        String apiKey = impact.applicationId() + "|"
                                + normalizeComponent(impact.apiEndpoint());
                        affectedApis.add(apiKey);
                        affectedComponents.add(apiKey + "|"
                                + normalizeComponent(impact.source()));
                    } else {
                        affectedComponents.add(impact.applicationId() + "|"
                                + normalizeComponent(impact.source()));
                    }
                });
        int applicationExposurePoints = Math.min(
                15, affectedApplications.size() * 3);
        int componentExposurePoints = Math.min(
                20, affectedComponents.size() * 2);
        int exposurePoints = applicationExposurePoints + componentExposurePoints;

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
                        + tableSizeRiskPoints
                        + algorithmAssessment.riskPoints()
        );
        String riskLevel = score >= 85 ? "CRITICAL"
                : score >= 60 ? "HIGH"
                : score >= 30 ? "MEDIUM"
                : score > 0 ? "LOW" : "NONE";

        return new MigrationRiskScore(
                score,
                riskLevel,
                severityPoints,
                applicationExposurePoints,
                componentExposurePoints,
                exposurePoints,
                confidencePoints,
                affectedApplications.size(),
                affectedApis.size(),
                affectedComponents.size(),
                "Score = highest schema-change severity (LOW 25, MEDIUM 55, HIGH 80)"
                        + " + 3 points per affected application (cap 15)"
                        + " + 2 points per distinct affected API/component (cap 20)"
                        + " + up to 10 points for average dependency confidence"
                        + " + up to 10 points based on the largest affected table"
                        + (targetMetadata.status().equals("AVAILABLE_READ_ONLY")
                                ? " size."
                                : " size when read-only target metadata is configured.")
                        + " + " + algorithmAssessment.riskPoints()
                        + " points for the explicit DDL algorithm hint.",
                targetMetadata.status(),
                targetMetadata.serverVersion(),
                targetMetadata.tableSizesBytes(),
                tableSizeRiskPoints,
                algorithmAssessment.riskPoints(),
                algorithmAssessment.explanation()
        );
    }

    private static String normalizeComponent(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public List<String> buildMigrationPlan(
            SchemaDiffResult diff,
            List<ApplicationImpact> impacts) {
        List<String> plan = new ArrayList<>();
        if (diff.changes().isEmpty()) {
            return List.of("No schema changes detected.");
        }
        plan.add("Back up the database and validate this migration against a staging copy.");
        plan.add("Review MySQL ALGORITHM=INSTANT/INPLACE/COPY support for the exact "
                + "migration on the target version; a missing explicit ALGORITHM "
                + "hint means the target server chooses the default.");
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
