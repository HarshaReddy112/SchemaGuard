package com.schemaguard.diff;

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
                changes,
                changes.stream().filter(c -> "HIGH".equals(c.riskLevel())).count(),
                changes.stream().filter(c -> "MEDIUM".equals(c.riskLevel())).count(),
                changes.stream().filter(c -> "LOW".equals(c.riskLevel())).count(),
                calculateOverallRisk(changes),
                generateRecommendations(changes)
        );
    }

    private static String calculateOverallRisk(List<SchemaChange> changes) {
        if (changes.stream().anyMatch(c -> "HIGH".equals(c.riskLevel()))) {
            return "HIGH";
        }

        if (changes.stream().anyMatch(c -> "MEDIUM".equals(c.riskLevel()))) {
            return "MEDIUM";
        }

        if (changes.stream().anyMatch(c -> "LOW".equals(c.riskLevel()))) {
            return "LOW";
        }

        return "NONE";
    }

    private static List<String> generateRecommendations(List<SchemaChange> changes) {
        List<String> recommendations = new java.util.ArrayList<>();

        if (changes.isEmpty()) {
            recommendations.add("No schema changes detected. No migration action is required.");
            return recommendations;
        }

        if (changes.stream().anyMatch(c -> "HIGH".equals(c.riskLevel()))) {
            recommendations.add("Review all high-risk changes before deployment.");
            recommendations.add("Back up the database and test the migration on a staging copy.");
        }

        if (changes.stream().anyMatch(c -> "REMOVED".equals(c.changeType()))) {
            recommendations.add("Check application queries and data dependencies before removing database objects.");
        }

        if (changes.stream().anyMatch(c -> "MODIFIED".equals(c.changeType()))) {
            recommendations.add("Check existing data and application compatibility before applying modifications.");
        }

        if (recommendations.isEmpty()) {
            recommendations.add("Review the proposed schema changes and test the migration before deployment.");
        }

        return recommendations;
    }
}
