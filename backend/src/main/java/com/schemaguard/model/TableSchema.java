package com.schemaguard.model;

import java.util.List;
import java.util.Map;

public record TableSchema(
        String name,
        Map<String, ColumnSchema> columns,
        List<String> primaryKey,
        List<IndexSchema> indexes,
        List<ConstraintSchema> constraints
) {
}
