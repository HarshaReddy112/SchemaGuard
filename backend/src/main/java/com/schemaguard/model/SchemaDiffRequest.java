package com.schemaguard.model;

public record SchemaDiffRequest(
        String oldSql,
        String newSql,
        String databaseName,
        String migrationSql
) {
    public SchemaDiffRequest(String oldSql, String newSql) {
        this(oldSql, newSql, null, null);
    }

    public SchemaDiffRequest(
            String oldSql,
            String newSql,
            String databaseName) {
        this(oldSql, newSql, databaseName, null);
    }
}
