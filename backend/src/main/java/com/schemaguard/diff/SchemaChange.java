package com.schemaguard.diff;

public record SchemaChange(
        String objectType,
        String changeType,
        String tableName,
        String objectName,
        String oldValue,
        String newValue,
        String riskLevel,
        String message
) {
}
