package com.schemaguard.model;

public record SchemaDiffRequest(
        String oldSql,
        String newSql,
        String databaseName
) {
    public SchemaDiffRequest(String oldSql, String newSql) {
        this(oldSql, newSql, null);
    }
}
