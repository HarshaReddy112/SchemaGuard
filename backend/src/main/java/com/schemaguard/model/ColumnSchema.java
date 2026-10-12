package com.schemaguard.model;

public record ColumnSchema(
        String name,
        String dataType,
        boolean nullable,
        String defaultValue,
        boolean autoIncrement
) {
}
