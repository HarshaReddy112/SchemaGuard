package com.schemaguard.diff;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SchemaRiskClassifier {

    private SchemaRiskClassifier() {
    }

    public static String tableAddedRisk() {
        return "LOW";
    }

    public static String tableRemovedRisk() {
        return "HIGH";
    }

    public static String columnAddedRisk(boolean nullable) {
        return nullable ? "LOW" : "HIGH";
    }

    public static String columnRemovedRisk() {
        return "HIGH";
    }

    public static String typeRisk(String oldType, String newType) {
        String oldUpper = oldType.trim().toUpperCase(Locale.ROOT);
        String newUpper = newType.trim().toUpperCase(Locale.ROOT);

        if (oldUpper.contains("UNSIGNED")
                != newUpper.contains("UNSIGNED")) {
            return "HIGH";
        }

        String oldBase = baseType(oldUpper);
        String newBase = baseType(newUpper);

        if (oldBase.equals(newBase)) {
            Integer oldSize = extractSize(oldUpper);
            Integer newSize = extractSize(newUpper);

            if (oldSize != null && newSize != null) {
                return newSize < oldSize ? "HIGH" : "LOW";
            }

            return "MEDIUM";
        }

        int oldRank = integerRank(oldBase);
        int newRank = integerRank(newBase);

        if (oldRank > 0 && newRank > 0) {
            return newRank > oldRank ? "LOW" : "HIGH";
        }

        return "MEDIUM";
    }

    public static String nullabilityRisk(boolean oldNullable, boolean newNullable) {
        boolean risky = !newNullable;
        return risky ? "HIGH" : "LOW";
    }

    public static String defaultValueRisk() {
        return "MEDIUM";
    }

    public static String autoIncrementRisk() {
        return "MEDIUM";
    }

    public static String primaryKeyRisk() {
        return "HIGH";
    }

    public static String indexAddedRisk() {
        return "LOW";
    }

    public static String indexRemovedRisk() {
        return "MEDIUM";
    }

    public static String indexModifiedRisk() {
        return "MEDIUM";
    }

    public static String constraintAddedRisk() {
        return "MEDIUM";
    }

    public static String constraintRemovedRisk() {
        return "HIGH";
    }

    public static String constraintModifiedRisk() {
        return "HIGH";
    }

    private static int integerRank(String type) {
        return switch (type) {
            case "TINYINT" -> 1;
            case "SMALLINT" -> 2;
            case "MEDIUMINT" -> 3;
            case "INT", "INTEGER" -> 4;
            case "BIGINT" -> 5;
            default -> 0;
        };
    }

    private static Integer extractSize(String type) {
        Matcher matcher = Pattern.compile("\\((\\d+)\\)").matcher(type);

        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        return null;
    }

    private static String baseType(String type) {
        return type.replaceAll("\\s*\\(.*", "")
                .replaceAll("\\s+UNSIGNED\\b", "")
                .trim();
    }
}
