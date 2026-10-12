package com.schemaguard.model;

import java.util.Map;

public record DatabaseSchema(
        Map<String, TableSchema> tables
) {
}
