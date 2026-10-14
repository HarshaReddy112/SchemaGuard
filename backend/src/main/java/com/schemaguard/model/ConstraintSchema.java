package com.schemaguard.model;

import java.util.List;

public record ConstraintSchema(
        String name,
        String type,
        List<String> columns,
        String referencedTable,
        List<String> referencedColumns,
        String expression
) {
    public ConstraintSchema(
            String name,
            String type,
            List<String> columns,
            String referencedTable,
            List<String> referencedColumns
    ) {
        this(name, type, columns, referencedTable, referencedColumns, null);
    }
}
