package com.schemaguard.diff;

import java.util.ArrayList;
import java.util.List;

public record SchemaDiffResult(
        List<SchemaChange> changes,
        long highRiskCount,
        long mediumRiskCount,
        long lowRiskCount,
        String overallRisk,
        List<String> recommendations
) {

    public SchemaDiffResult(List<SchemaChange> changes) {
        this(
                List.copyOf(changes),
                countRisk(changes, "HIGH"),
                countRisk(changes, "MEDIUM"),
                countRisk(changes, "LOW"),
                determineOverallRisk(changes),
                generateRecommendations(changes)
        );
    }

    public SchemaDiffResult {
        changes = List.copyOf(changes);
        recommendations = List.copyOf(recommendations);
    }

    private static long countRisk(
            List<SchemaChange> changes,
            String riskLevel) {

        return changes.stream()
                .filter(change -> riskLevel.equals(change.riskLevel()))
                .count();
    }

    private static String determineOverallRisk(
            List<SchemaChange> changes) {

        if (changes.stream().anyMatch(
                change -> "HIGH".equals(change.riskLevel()))) {
            return "HIGH";
        }

        if (changes.stream().anyMatch(
                change -> "MEDIUM".equals(change.riskLevel()))) {
            return "MEDIUM";
        }

        if (changes.stream().anyMatch(
                change -> "LOW".equals(change.riskLevel()))) {
            return "LOW";
        }

        return "NONE";
    }

    private static List<String> generateRecommendations(
            List<SchemaChange> changes) {

        List<String> recommendations = new ArrayList<>();

        if (changes.isEmpty()) {
            recommendations.add(
                    "No schema changes detected. No migration is needed for the compared definitions."
            );
            return recommendations;
        }

        if (changes.stream().anyMatch(
                change -> "HIGH".equals(change.riskLevel()))) {
            recommendations.add(
                    "Review all high-risk changes before deployment."
            );
            recommendations.add(
                    "Back up the database and test the migration on a staging copy."
            );
        }

        if (changes.stream().anyMatch(
                change -> "COLUMN".equals(change.objectType())
                        && "REMOVED".equals(change.changeType()))) {
            recommendations.add(
                    "Check application queries and data dependencies before removing columns."
            );
        }

        if (changes.stream().anyMatch(
                change -> "COLUMN".equals(change.objectType())
                        && "MODIFIED".equals(change.changeType())
                        && "Column data type changed".equals(change.message()))) {
            recommendations.add(
                    "Check existing values for compatibility with the new column types."
            );
        }

        if (changes.stream().anyMatch(
                change -> "CONSTRAINT".equals(change.objectType())
                        && "REMOVED".equals(change.changeType()))) {
            recommendations.add(
                    "Verify that removing constraints will not allow invalid or inconsistent data."
            );
        }

        if (changes.stream().anyMatch(
                change -> "INDEX".equals(change.objectType())
                        && "REMOVED".equals(change.changeType()))) {
            recommendations.add(
                    "Check query performance before removing indexes."
            );
        }

        if (changes.stream().anyMatch(
                change -> "COLUMN".equals(change.objectType())
                        && "ADDED".equals(change.changeType()))) {
            recommendations.add(
                    "Check whether the new column needs a default value or a backfill for existing rows."
            );
        }

        if (recommendations.isEmpty()) {
            recommendations.add(
                    "Review the detected changes and validate the migration in a test environment."
            );
        }

        return recommendations;
    }
}
