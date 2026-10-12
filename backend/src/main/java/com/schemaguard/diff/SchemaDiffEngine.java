
package com.schemaguard.diff;

import com.schemaguard.model.ColumnSchema;
import com.schemaguard.model.ConstraintSchema;
import com.schemaguard.model.DatabaseSchema;
import com.schemaguard.model.IndexSchema;
import com.schemaguard.model.TableSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
                        null, "TABLE", "LOW", "Table was added"
                ));
                continue;
            }

            if (newTable == null) {
                changes.add(new SchemaChange(
                        "TABLE", "REMOVED", tableName, tableName,
                        "TABLE", null, "HIGH", "Table was removed"
                ));
                continue;
            }

            compareColumns(tableName, oldTable, newTable, changes);
            comparePrimaryKey(tableName, oldTable, newTable, changes);
            compareIndexes(tableName, oldTable, newTable, changes);
            compareConstraints(tableName, oldTable, newTable, changes);
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
            changes.add(new SchemaChange(
                    "COLUMN", "MODIFIED", tableName, columnName,
                    oldColumn.dataType(), newColumn.dataType(),
                    typeRisk(oldColumn.dataType(), newColumn.dataType()),
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

        if (!Objects.equals(
                oldColumn.defaultValue(), newColumn.defaultValue())) {
            changes.add(new SchemaChange(
                    "COLUMN", "MODIFIED", tableName, columnName,
                    oldColumn.defaultValue(), newColumn.defaultValue(),
                    "MEDIUM", "Column default value changed"
            ));
        }

        if (oldColumn.autoIncrement() != newColumn.autoIncrement()) {
            changes.add(new SchemaChange(
                    "COLUMN", "MODIFIED", tableName, columnName,
                    "autoIncrement=" + oldColumn.autoIncrement(),
                    "autoIncrement=" + newColumn.autoIncrement(),
                    "MEDIUM", "Column auto-increment setting changed"
            ));
        }
    }

    private void comparePrimaryKey(
            String tableName,
            TableSchema oldTable,
            TableSchema newTable,
            List<SchemaChange> changes) {

        List<String> oldKey = oldTable.primaryKey();
        List<String> newKey = newTable.primaryKey();

        if (!oldKey.equals(newKey)) {
            changes.add(new SchemaChange(
                    "PRIMARY_KEY", "MODIFIED", tableName, "PRIMARY",
                    String.join(", ", oldKey),
                    String.join(", ", newKey),
                    "HIGH", "Primary key changed"
            ));
        }
    }

    private void compareIndexes(
            String tableName,
            TableSchema oldTable,
            TableSchema newTable,
            List<SchemaChange> changes) {

        var oldIndexes = new java.util.TreeMap<String, IndexSchema>();
        var newIndexes = new java.util.TreeMap<String, IndexSchema>();

        oldTable.indexes().forEach(index ->
                oldIndexes.put(index.name(), index));

        newTable.indexes().forEach(index ->
                newIndexes.put(index.name(), index));

        TreeSet<String> names = new TreeSet<>();
        names.addAll(oldIndexes.keySet());
        names.addAll(newIndexes.keySet());

        for (String name : names) {
            IndexSchema oldIndex = oldIndexes.get(name);
            IndexSchema newIndex = newIndexes.get(name);

            if (oldIndex == null) {
                changes.add(new SchemaChange(
                        "INDEX", "ADDED", tableName, name,
                        null, indexValue(newIndex),
                        "LOW", "Index was added"
                ));
            } else if (newIndex == null) {
                changes.add(new SchemaChange(
                        "INDEX", "REMOVED", tableName, name,
                        indexValue(oldIndex), null,
                        "MEDIUM", "Index was removed"
                ));
            } else if (!indexValue(oldIndex).equals(indexValue(newIndex))) {
                changes.add(new SchemaChange(
                        "INDEX", "MODIFIED", tableName, name,
                        indexValue(oldIndex), indexValue(newIndex),
                        "MEDIUM", "Index definition changed"
                ));
            }
        }
    }

    private void compareConstraints(
            String tableName,
            TableSchema oldTable,
            TableSchema newTable,
            List<SchemaChange> changes) {

        var oldConstraints =
                new java.util.TreeMap<String, ConstraintSchema>();
        var newConstraints =
                new java.util.TreeMap<String, ConstraintSchema>();

        oldTable.constraints().forEach(constraint ->
                oldConstraints.put(constraint.name(), constraint));

        newTable.constraints().forEach(constraint ->
                newConstraints.put(constraint.name(), constraint));

        TreeSet<String> names = new TreeSet<>();
        names.addAll(oldConstraints.keySet());
        names.addAll(newConstraints.keySet());

        for (String name : names) {
            ConstraintSchema oldConstraint = oldConstraints.get(name);
            ConstraintSchema newConstraint = newConstraints.get(name);

            if (oldConstraint == null) {
                changes.add(new SchemaChange(
                        "CONSTRAINT", "ADDED", tableName, name,
                        null, constraintValue(newConstraint),
                        "MEDIUM", "Constraint was added"
                ));
            } else if (newConstraint == null) {
                changes.add(new SchemaChange(
                        "CONSTRAINT", "REMOVED", tableName, name,
                        constraintValue(oldConstraint), null,
                        "HIGH", "Constraint was removed"
                ));
            } else if (!constraintValue(oldConstraint)
                    .equals(constraintValue(newConstraint))) {
                changes.add(new SchemaChange(
                        "CONSTRAINT", "MODIFIED", tableName, name,
                        constraintValue(oldConstraint),
                        constraintValue(newConstraint),
                        "HIGH", "Constraint definition changed"
                ));
            }
        }
    }

    private String indexValue(IndexSchema index) {
        return "unique=" + index.unique()
                + ", columns=" + String.join(", ", index.columns());
    }

    private String constraintValue(ConstraintSchema constraint) {
        return constraint.type()
                + ", columns=" + String.join(", ", constraint.columns())
                + ", references=" + constraint.referencedTable()
                + "(" + String.join(", ", constraint.referencedColumns()) + ")";
    }

    private String typeRisk(String oldType, String newType) {
        String oldUpper = oldType.trim().toUpperCase(Locale.ROOT);
        String newUpper = newType.trim().toUpperCase(Locale.ROOT);

        if (oldUpper.contains("UNSIGNED")
                != newUpper.contains("UNSIGNED")) {
            return "HIGH";
        }

        String oldBase = baseType(oldUpper);
        String newBase = baseType(newUpper);

        if (oldBase.equals(newBase)) {
            Integer oldSize = extractSize(oldUpper);
            Integer newSize = extractSize(newUpper);

            if (oldSize != null && newSize != null) {
                return newSize < oldSize ? "HIGH" : "LOW";
            }

            return "MEDIUM";
        }

        int oldRank = integerRank(oldBase);
        int newRank = integerRank(newBase);

        if (oldRank > 0 && newRank > 0) {
            return newRank > oldRank ? "LOW" : "HIGH";
        }

        return "MEDIUM";
    }

    private int integerRank(String type) {
        return switch (type) {
            case "TINYINT" -> 1;
            case "SMALLINT" -> 2;
            case "MEDIUMINT" -> 3;
            case "INT", "INTEGER" -> 4;
            case "BIGINT" -> 5;
            default -> 0;
        };
    }

    private Integer extractSize(String type) {
        Matcher matcher = Pattern.compile("\\((\\d+)\\)").matcher(type);

        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }

        return null;
    }

    private String baseType(String type) {
        return type.replaceAll("\\s*\\(.*", "")
                .replaceAll("\\s+UNSIGNED\\b", "")
                .trim();
    }
}
