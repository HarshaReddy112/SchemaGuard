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
            recommendations.add("Review all high-risk changes before deployment and confirm the migration is staged safely.");
            recommendations.add("Back up the database and validate the migration on a staging copy before production rollout.");
        }

        if (changes.stream().anyMatch(c -> "COLUMN".equals(c.objectType())
                && "REMOVED".equals(c.changeType()))) {
            recommendations.add("Check application queries, stored procedures, and reporting logic before dropping columns because existing data may be lost.");
        }

        if (changes.stream().anyMatch(c -> "COLUMN".equals(c.objectType())
                && "MODIFIED".equals(c.changeType())
                && "nullable=false".equalsIgnoreCase(c.newValue()))) {
            recommendations.add("Audit existing rows before enforcing NOT NULL; backfill or default missing values as needed to avoid failed writes.");
        }

        if (changes.stream().anyMatch(c -> "COLUMN".equals(c.objectType())
                && "MODIFIED".equals(c.changeType())
                && c.oldValue() != null
                && c.newValue() != null
                && !c.oldValue().equalsIgnoreCase(c.newValue()))) {
            recommendations.add("Validate application compatibility and data conversion requirements before changing column definitions or lengths.");
        }

        if (changes.stream().anyMatch(c -> "PRIMARY_KEY".equals(c.objectType())
                || "CONSTRAINT".equals(c.objectType()))) {
            recommendations.add("Review foreign-key and primary-key dependencies before applying structural changes that affect joins or referential integrity.");
        }

        if (changes.stream().anyMatch(c -> "INDEX".equals(c.objectType()))) {
            recommendations.add("Evaluate index build time and lock impact for large tables before adding or removing indexes in production.");
        }

        if (recommendations.isEmpty()) {
            recommendations.add("Review the proposed schema changes and test the migration before deployment.");
        }

        return recommendations;
    }
}
