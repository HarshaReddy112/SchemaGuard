
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
                        null, "TABLE", SchemaRiskClassifier.tableAddedRisk(),
                        "Table was added"
                ));
                continue;
            }

            if (newTable == null) {
                changes.add(new SchemaChange(
                        "TABLE", "REMOVED", tableName, tableName,
                        "TABLE", null, SchemaRiskClassifier.tableRemovedRisk(),
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
        Map<String, String> possibleRenames =
                detectPossibleRenames(oldTable, newTable);
        var renameTargets = new java.util.HashSet<>(possibleRenames.values());

        for (String columnName : columnNames) {
            ColumnSchema oldColumn = oldTable.columns().get(columnName);
            ColumnSchema newColumn = newTable.columns().get(columnName);

            if (oldColumn == null) {
                if (renameTargets.contains(columnName)) {
                    continue;
                }
                changes.add(new SchemaChange(
                        "COLUMN", "ADDED", tableName, columnName,
                        null, newColumn.dataType(),
                        SchemaRiskClassifier.columnAddedRisk(newColumn.nullable()),
                        "Column was added"
                ));
                continue;
            }

            if (newColumn == null) {
                String possibleNewName = possibleRenames.get(columnName);
                if (possibleNewName != null) {
                    ColumnSchema renamedColumn =
                            newTable.columns().get(possibleNewName);
                    changes.add(new SchemaChange(
                            "COLUMN", "POSSIBLE_RENAME", tableName, columnName,
                            oldColumn.dataType(),
                            possibleNewName + ": " + renamedColumn.dataType(),
                            "HIGH",
                            "Possible column rename to " + possibleNewName
                                    + "; verify it is not a drop and add"
                    ));
                    continue;
                }
                changes.add(new SchemaChange(
                        "COLUMN", "REMOVED", tableName, columnName,
                        oldColumn.dataType(), null,
                        SchemaRiskClassifier.columnRemovedRisk(),
                        "Column was removed"
                ));
                continue;
            }

            compareColumnProperties(
                    tableName, oldColumn, newColumn, changes
            );
        }
    }

    private Map<String, String> detectPossibleRenames(
            TableSchema oldTable,
            TableSchema newTable
    ) {
        Map<String, String> possibleRenames = new HashMap<>();
        Map<String, Integer> addedCandidatesByType = new HashMap<>();

        for (ColumnSchema newColumn : newTable.columns().values()) {
            if (!oldTable.columns().containsKey(newColumn.name())) {
                String type = newColumn.dataType().toUpperCase(
                        java.util.Locale.ROOT);
                addedCandidatesByType.merge(type, 1, Integer::sum);
            }
        }
        for (ColumnSchema oldColumn : oldTable.columns().values()) {
            if (newTable.columns().containsKey(oldColumn.name())) {
                continue;
            }
            String type = oldColumn.dataType().toUpperCase(
                    java.util.Locale.ROOT);
            if (addedCandidatesByType.getOrDefault(type, 0) != 1) {
                continue;
            }
            List<ColumnSchema> matchingAdded = newTable.columns().values()
                    .stream()
                    .filter(column -> !oldTable.columns()
                            .containsKey(column.name()))
                    .filter(column -> column.dataType()
                            .equalsIgnoreCase(oldColumn.dataType()))
                    .toList();
            List<ColumnSchema> matchingRemoved = oldTable.columns().values()
                    .stream()
                    .filter(column -> !newTable.columns()
                            .containsKey(column.name()))
                    .filter(column -> column.dataType()
                            .equalsIgnoreCase(oldColumn.dataType()))
                    .toList();
            if (matchingAdded.size() == 1 && matchingRemoved.size() == 1) {
                possibleRenames.put(
                        oldColumn.name(), matchingAdded.get(0).name());
            }
        }
        return possibleRenames;
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
                    SchemaRiskClassifier.typeRisk(
                            oldColumn.dataType(), newColumn.dataType()
                    ),
                    "Column data type changed"
            ));
        }

        if (oldColumn.nullable() != newColumn.nullable()) {
            changes.add(new SchemaChange(
                    "COLUMN", "MODIFIED", tableName, columnName,
                    "nullable=" + oldColumn.nullable(),
                    "nullable=" + newColumn.nullable(),
                    SchemaRiskClassifier.nullabilityRisk(
                            oldColumn.nullable(), newColumn.nullable()
                    ),
                    "Column nullability changed"
            ));
        }

        if (!Objects.equals(
                oldColumn.defaultValue(), newColumn.defaultValue())) {
            changes.add(new SchemaChange(
                    "COLUMN", "MODIFIED", tableName, columnName,
                    oldColumn.defaultValue(), newColumn.defaultValue(),
                    SchemaRiskClassifier.defaultValueRisk(),
                    "Column default value changed"
            ));
        }

        if (oldColumn.autoIncrement() != newColumn.autoIncrement()) {
            changes.add(new SchemaChange(
                    "COLUMN", "MODIFIED", tableName, columnName,
                    "autoIncrement=" + oldColumn.autoIncrement(),
                    "autoIncrement=" + newColumn.autoIncrement(),
                    SchemaRiskClassifier.autoIncrementRisk(),
                    "Column auto-increment setting changed"
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
                    SchemaRiskClassifier.primaryKeyRisk(),
                    "Primary key changed"
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
                        SchemaRiskClassifier.indexAddedRisk(),
                        "Index was added"
                ));
            } else if (newIndex == null) {
                changes.add(new SchemaChange(
                        "INDEX", "REMOVED", tableName, name,
                        indexValue(oldIndex), null,
                        SchemaRiskClassifier.indexRemovedRisk(),
                        "Index was removed"
                ));
            } else if (!indexValue(oldIndex).equals(indexValue(newIndex))) {
                changes.add(new SchemaChange(
                        "INDEX", "MODIFIED", tableName, name,
                        indexValue(oldIndex), indexValue(newIndex),
                        SchemaRiskClassifier.indexModifiedRisk(),
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
                        SchemaRiskClassifier.constraintAddedRisk(),
                        "Constraint was added"
                ));
            } else if (newConstraint == null) {
                changes.add(new SchemaChange(
                        "CONSTRAINT", "REMOVED", tableName, name,
                        constraintValue(oldConstraint), null,
                        SchemaRiskClassifier.constraintRemovedRisk(),
                        "Constraint was removed"
                ));
            } else if (!constraintValue(oldConstraint)
                    .equals(constraintValue(newConstraint))) {
                changes.add(new SchemaChange(
                        "CONSTRAINT", "MODIFIED", tableName, name,
                        constraintValue(oldConstraint),
                        constraintValue(newConstraint),
                        SchemaRiskClassifier.constraintModifiedRisk(),
                        "Constraint definition changed"
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
                + "(" + String.join(", ", constraint.referencedColumns()) + ")"
                + (constraint.expression() == null
                        ? "" : ", expression=" + constraint.expression());
    }

}
