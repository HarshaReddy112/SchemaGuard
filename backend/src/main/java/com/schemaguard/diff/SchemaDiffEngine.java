package com.schemaguard.diff;

import com.schemaguard.model.ColumnSchema;
import com.schemaguard.model.DatabaseSchema;
import com.schemaguard.model.TableSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class SchemaDiffEngine {

    public SchemaDiffResult compare(
            DatabaseSchema oldSchema,
            DatabaseSchema newSchema) {

        List<SchemaChange> changes = new ArrayList<>();

        TreeSet<String> tableNames = new TreeSet<>();
        tableNames.addAll(oldSchema.tables().keySet());
        tableNames.addAll(newSchema.tables().keySet());

        for (String tableName : tableNames) {
            TableSchema oldTable = oldSchema.tables().get(tableName);
            TableSchema newTable = newSchema.tables().get(tableName);

            if (oldTable == null) {
                changes.add(new SchemaChange(
                        "TABLE", "ADDED", tableName, tableName,
                        null, "TABLE", "LOW",
                        "Table was added"
                ));
                continue;
            }

            if (newTable == null) {
                changes.add(new SchemaChange(
                        "TABLE", "REMOVED", tableName, tableName,
                        "TABLE", null, "HIGH",
                        "Table was removed"
                ));
                continue;
            }

            compareColumns(tableName, oldTable, newTable, changes);
        }

        return new SchemaDiffResult(List.copyOf(changes));
    }

    private void compareColumns(
            String tableName,
            TableSchema oldTable,
            TableSchema newTable,
            List<SchemaChange> changes) {

        TreeSet<String> columnNames = new TreeSet<>();
        columnNames.addAll(oldTable.columns().keySet());
        columnNames.addAll(newTable.columns().keySet());

        for (String columnName : columnNames) {
            ColumnSchema oldColumn = oldTable.columns().get(columnName);
            ColumnSchema newColumn = newTable.columns().get(columnName);

            if (oldColumn == null) {
                changes.add(new SchemaChange(
                        "COLUMN", "ADDED", tableName, columnName,
                        null, newColumn.dataType(), "LOW",
                        "Column was added"
                ));
                continue;
            }

            if (newColumn == null) {
                changes.add(new SchemaChange(
                        "COLUMN", "REMOVED", tableName, columnName,
                        oldColumn.dataType(), null, "HIGH",
                        "Column was removed"
                ));
                continue;
            }

            compareColumnProperties(
                    tableName, oldColumn, newColumn, changes
            );
        }
    }

    private void compareColumnProperties(
            String tableName,
            ColumnSchema oldColumn,
            ColumnSchema newColumn,
            List<SchemaChange> changes) {

        String columnName = oldColumn.name();

        if (!oldColumn.dataType().equalsIgnoreCase(newColumn.dataType())) {
            boolean narrowing = isNarrowing(
                    oldColumn.dataType(), newColumn.dataType()
            );

            changes.add(new SchemaChange(
                    "COLUMN", "MODIFIED", tableName, columnName,
                    oldColumn.dataType(), newColumn.dataType(),
                    narrowing ? "HIGH" : "MEDIUM",
                    "Column data type changed"
            ));
        }

        if (oldColumn.nullable() != newColumn.nullable()) {
            boolean risky = !newColumn.nullable();

            changes.add(new SchemaChange(
                    "COLUMN", "MODIFIED", tableName, columnName,
                    "nullable=" + oldColumn.nullable(),
                    "nullable=" + newColumn.nullable(),
                    risky ? "HIGH" : "LOW",
                    "Column nullability changed"
            ));
        }

        if (!java.util.Objects.equals(
                oldColumn.defaultValue(), newColumn.defaultValue())) {
            changes.add(new SchemaChange(
                    "COLUMN", "MODIFIED", tableName, columnName,
                    oldColumn.defaultValue(), newColumn.defaultValue(),
                    "MEDIUM",
                    "Column default value changed"
            ));
        }

        if (oldColumn.autoIncrement() != newColumn.autoIncrement()) {
            changes.add(new SchemaChange(
                    "COLUMN", "MODIFIED", tableName, columnName,
                    "autoIncrement=" + oldColumn.autoIncrement(),
                    "autoIncrement=" + newColumn.autoIncrement(),
                    "MEDIUM",
                    "Column auto-increment setting changed"
            ));
        }
    }

    private boolean isNarrowing(String oldType, String newType) {
        Integer oldSize = extractSize(oldType);
        Integer newSize = extractSize(newType);

        String oldBase = baseType(oldType);
        String newBase = baseType(newType);

        if (!oldBase.equals(newBase)) {
            return true;
        }

        return oldSize != null && newSize != null
                && newSize < oldSize;
    }

    private Integer extractSize(String type) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\((\\d+)\\)")
                        .matcher(type);

        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        return null;
    }

    private String baseType(String type) {
        return type.trim()
                .replaceAll("\\s*\\(.*", "")
                .trim()
                .toUpperCase(java.util.Locale.ROOT);
    }
}
