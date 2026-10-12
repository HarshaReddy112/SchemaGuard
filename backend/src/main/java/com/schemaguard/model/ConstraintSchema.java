package com.schemaguard.model;

import java.util.List;

public record ConstraintSchema(
        String name,
        String type,
        List<String> columns,
        String referencedTable,
        List<String> referencedColumns
) {
}
