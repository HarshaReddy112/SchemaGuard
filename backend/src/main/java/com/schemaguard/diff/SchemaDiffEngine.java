package com.schemaguard.diff;

import com.schemaguard.model.ColumnSchema;
import com.schemaguard.model.ConstraintSchema;
import com.schemaguard.model.DatabaseSchema;
import com.schemaguard.model.IndexSchema;
import com.schemaguard.model.TableSchema;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            String risk = classifyTypeChange(oldColumn.dataType(), newColumn.dataType());

            changes.add(new SchemaChange(
                "COLUMN", "MODIFIED", tableName, columnName,
                oldColumn.dataType(), newColumn.dataType(),risk,
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

    private void comparePrimaryKey(
            String tableName,
            TableSchema oldTable,
            TableSchema newTable,
            List<SchemaChange> changes) {

        if (!oldTable.primaryKey().equals(newTable.primaryKey())) {
            changes.add(new SchemaChange(
                    "PRIMARY_KEY", "MODIFIED", tableName, "PRIMARY",
                    String.join(", ", oldTable.primaryKey()),
                    String.join(", ", newTable.primaryKey()),
                    "HIGH",
                    "Primary key definition changed"
            ));
        }
    }

    private void compareIndexes(
            String tableName,
            TableSchema oldTable,
            TableSchema newTable,
            List<SchemaChange> changes) {

        Map<String, IndexSchema> oldIndexes = new HashMap<>();
        Map<String, IndexSchema> newIndexes = new HashMap<>();

        for (IndexSchema index : oldTable.indexes()) {
            oldIndexes.put(index.name(), index);
        }

        for (IndexSchema index : newTable.indexes()) {
            newIndexes.put(index.name(), index);
        }

        TreeSet<String> names = new TreeSet<>();
        names.addAll(oldIndexes.keySet());
        names.addAll(newIndexes.keySet());

        for (String name : names) {
            IndexSchema oldIndex = oldIndexes.get(name);
            IndexSchema newIndex = newIndexes.get(name);

            if (oldIndex == null) {
                changes.add(new SchemaChange(
                        "INDEX", "ADDED", tableName, name,
                        null, formatIndex(newIndex), "LOW",
                        "Index was added"
                ));
                continue;
            }

            if (newIndex == null) {
                changes.add(new SchemaChange(
                        "INDEX", "REMOVED", tableName, name,
                        formatIndex(oldIndex), null,
                        oldIndex.unique() ? "HIGH" : "MEDIUM",
                        "Index was removed"
                ));
                continue;
            }

            if (oldIndex.unique() != newIndex.unique()
                    || !oldIndex.columns().equals(newIndex.columns())) {
                changes.add(new SchemaChange(
                        "INDEX", "MODIFIED", tableName, name,
                        formatIndex(oldIndex), formatIndex(newIndex),
                        oldIndex.unique() || newIndex.unique()
                                ? "HIGH" : "MEDIUM",
                        "Index definition changed"
                ));
            }
        }
    }

    private void compareConstraints(
            String tableName,
            TableSchema oldTable,
            TableSchema newTable,
            List<SchemaChange> changes) {

        Map<String, ConstraintSchema> oldConstraints = new HashMap<>();
        Map<String, ConstraintSchema> newConstraints = new HashMap<>();

        for (ConstraintSchema constraint : oldTable.constraints()) {
            oldConstraints.put(constraint.name(), constraint);
        }

        for (ConstraintSchema constraint : newTable.constraints()) {
            newConstraints.put(constraint.name(), constraint);
        }

        TreeSet<String> names = new TreeSet<>();
        names.addAll(oldConstraints.keySet());
        names.addAll(newConstraints.keySet());

        for (String name : names) {
            ConstraintSchema oldConstraint = oldConstraints.get(name);
            ConstraintSchema newConstraint = newConstraints.get(name);

            if (oldConstraint == null) {
                changes.add(new SchemaChange(
                        "CONSTRAINT", "ADDED", tableName, name,
                        null, formatConstraint(newConstraint), "LOW",
                        "Constraint was added"
                ));
                continue;
            }

            if (newConstraint == null) {
                changes.add(new SchemaChange(
                        "CONSTRAINT", "REMOVED", tableName, name,
                        formatConstraint(oldConstraint), null, "HIGH",
                        "Constraint was removed"
                ));
                continue;
            }

            if (!Objects.equals(oldConstraint.type(), newConstraint.type())
                    || !Objects.equals(
                            oldConstraint.columns(), newConstraint.columns())
                    || !Objects.equals(
                            oldConstraint.referencedTable(),
                            newConstraint.referencedTable())
                    || !Objects.equals(
                            oldConstraint.referencedColumns(),
                            newConstraint.referencedColumns())) {
                changes.add(new SchemaChange(
                        "CONSTRAINT", "MODIFIED", tableName, name,
                        formatConstraint(oldConstraint),
                        formatConstraint(newConstraint),
                        "HIGH",
                        "Constraint definition changed"
                ));
            }
        }
    }

    private String formatIndex(IndexSchema index) {
        return "unique=" + index.unique()
                + ", columns=" + String.join(", ", index.columns());
    }

    private String formatConstraint(ConstraintSchema constraint) {
        return "type=" + constraint.type()
                + ", columns=" + String.join(", ", constraint.columns())
                + ", referencedTable=" + constraint.referencedTable()
                + ", referencedColumns="
                + String.join(", ", constraint.referencedColumns());
    }

    
private String classifyTypeChange(String oldType, String newType) {
    String oldBase = baseType(oldType);
    String newBase = baseType(newType);

    if (isUnsigned(oldType) != isUnsigned(newType)) {
        return "HIGH";
    }

    int oldRank = integerRank(oldBase);
    int newRank = integerRank(newBase);

    if (oldRank > 0 && newRank > 0) {
        return newRank >= oldRank ? "LOW" : "HIGH";
    }

    oldRank = largeTextRank(oldBase);
    newRank = largeTextRank(newBase);

    if (oldRank > 0 && newRank > 0) {
        return newRank >= oldRank ? "LOW" : "HIGH";
    }

    if (oldBase.equals(newBase)) {
        if (isLengthType(oldBase)) {
            Integer oldSize = extractSize(oldType);
            Integer newSize = extractSize(newType);

            if (oldSize != null && newSize != null) {
                return newSize < oldSize ? "HIGH" : "LOW";
            }
        }

        if (oldBase.equals("DECIMAL") || oldBase.equals("NUMERIC")) {
            int[] oldParts = extractPrecisionScale(oldType);
            int[] newParts = extractPrecisionScale(newType);

            if (oldParts != null && newParts != null) {
                int oldIntegerDigits = oldParts[0] - oldParts[1];
                int newIntegerDigits = newParts[0] - newParts[1];

                return newIntegerDigits >= oldIntegerDigits
                        && newParts[1] >= oldParts[1]
                        ? "LOW" : "HIGH";
            }
        }

        return "MEDIUM";
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

private int largeTextRank(String type) {
    return switch (type) {
        case "TINYTEXT", "TINYBLOB" -> 1;
        case "TEXT", "BLOB" -> 2;
        case "MEDIUMTEXT", "MEDIUMBLOB" -> 3;
        case "LONGTEXT", "LONGBLOB" -> 4;
        default -> 0;
    };
}

private boolean isLengthType(String type) {
    return switch (type) {
        case "CHAR", "VARCHAR", "BINARY", "VARBINARY" -> true;
        default -> false;
    };
}

private boolean isUnsigned(String type) {
    return type.toUpperCase(java.util.Locale.ROOT)
            .matches(".*\\bUNSIGNED\\b.*");
}

private int[] extractPrecisionScale(String type) {
    java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile(
                    "\\((\\d+)\\s*,\\s*(\\d+)\\)"
            ).matcher(type);

    if (!matcher.find()) {
        return null;
    }

    return new int[] {
            Integer.parseInt(matcher.group(1)),
            Integer.parseInt(matcher.group(2))
    };
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
            .replaceAll("\\s+(UNSIGNED|SIGNED)\\b", "")
            .trim()
            .toUpperCase(java.util.Locale.ROOT);
}

}
