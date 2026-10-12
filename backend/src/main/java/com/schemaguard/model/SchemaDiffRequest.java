package com.schemaguard.model;

public record SchemaDiffRequest(
        String oldSql,
        String newSql
) {
}
