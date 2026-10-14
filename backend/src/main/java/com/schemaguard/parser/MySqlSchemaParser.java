
package com.schemaguard.parser;

import com.schemaguard.model.ColumnSchema;
import com.schemaguard.model.ConstraintSchema;
import com.schemaguard.model.DatabaseSchema;
import com.schemaguard.model.IndexSchema;
import com.schemaguard.model.TableSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MySqlSchemaParser {

    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "(?is)^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:`([^`]+)`|([\\w]+))\\s*\\("
    );

    private static final Pattern ALTER_TABLE_PATTERN = Pattern.compile(
            "(?is)^ALTER\\s+TABLE\\s+(?:`([^`]+)`|([\\w]+))\\s+(.+)$"
    );

    private static final Pattern DROP_TABLE_PATTERN = Pattern.compile(
            "(?is)^DROP\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:`([^`]+)`|([\\w]+))$"
    );

    private static final Pattern RENAME_TABLE_PATTERN = Pattern.compile(
            "(?is)^RENAME\\s+TABLE\\s+(?:`([^`]+)`|([\\w]+))\\s+TO\\s+(?:`([^`]+)`|([\\w]+))$"
    );

    public DatabaseSchema parse(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL input cannot be empty.");
        }

        Map<String, TableSchema> tables = new LinkedHashMap<>();
        boolean sawSupportedStatement = false;

        for (String statement : splitStatements(sql)) {
            String item = statement.trim();

            if (item.isEmpty()) {
                continue;
            }

            if (CREATE_TABLE_PATTERN.matcher(item).find()) {
                parseCreateTable(item, tables);
                sawSupportedStatement = true;
            } else if (ALTER_TABLE_PATTERN.matcher(item).matches()) {
                parseAlterTable(item, tables);
                sawSupportedStatement = true;
            } else if (DROP_TABLE_PATTERN.matcher(item).matches()) {
                parseDropTable(item, tables);
                sawSupportedStatement = true;
            } else if (RENAME_TABLE_PATTERN.matcher(item).matches()) {
                parseRenameTable(item, tables);
                sawSupportedStatement = true;
            } else {
                throw new IllegalArgumentException(
                        "Unsupported SQL statement: " + item
                );
            }
        }

        if (!sawSupportedStatement) {
            throw new IllegalArgumentException(
                    "No supported CREATE TABLE statements were found."
            );
        }

        return new DatabaseSchema(tables);
    }

    private void parseCreateTable(
            String sql,
            Map<String, TableSchema> tables
    ) {
        Matcher matcher = CREATE_TABLE_PATTERN.matcher(sql);

        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Unsupported CREATE TABLE statement: " + sql
            );
        }

        String tableName = matcher.group(1) != null
                ? matcher.group(1)
                : matcher.group(2);

        int openingParen = matcher.end() - 1;
        int closingParen = findMatchingParenthesis(sql, openingParen);

        if (closingParen == -1) {
            throw new IllegalArgumentException(
                    "Unclosed table definition: " + tableName
            );
        }

        String definition = sql.substring(openingParen + 1, closingParen);

        Map<String, ColumnSchema> columns = new LinkedHashMap<>();
        List<String> primaryKey = new ArrayList<>();
        List<IndexSchema> indexes = new ArrayList<>();
        List<ConstraintSchema> constraints = new ArrayList<>();

        for (String part : splitDefinitions(definition)) {
            String item = part.trim();

            if (item.isEmpty()) {
                continue;
            }

            String upper = item.toUpperCase(Locale.ROOT);

            if (upper.startsWith("PRIMARY KEY")) {
                primaryKey.addAll(extractColumns(item));
            } else if (upper.startsWith("UNIQUE KEY")
                    || upper.startsWith("UNIQUE INDEX")) {
                indexes.add(parseIndex(item, true));
            } else if (upper.startsWith("KEY")
                    || upper.startsWith("INDEX")) {
                indexes.add(parseIndex(item, false));
            } else if (upper.startsWith("CONSTRAINT")
                    && upper.contains("FOREIGN KEY")) {
                constraints.add(parseForeignKey(item));
            } else if (upper.startsWith("FOREIGN KEY")) {
                constraints.add(parseForeignKey(item));
            } else {
                ColumnSchema column = parseColumn(item);
                columns.put(column.name(), column);
            }
        }

        if (tables.containsKey(tableName)) {
            throw new IllegalArgumentException(
                    "Table already exists in parsed schema: " + tableName
            );
        }

        tables.put(
                tableName,
                new TableSchema(
                        tableName,
                        columns,
                        primaryKey,
                        indexes,
                        constraints
                )
        );
    }

    private void parseAlterTable(
            String sql,
            Map<String, TableSchema> tables
    ) {
        Matcher matcher = ALTER_TABLE_PATTERN.matcher(sql);

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Unsupported ALTER TABLE statement: " + sql
            );
        }

        String tableName = matcher.group(1) != null
                ? matcher.group(1)
                : matcher.group(2);

        TableSchema table = tables.get(tableName);

        if (table == null) {
            throw new IllegalArgumentException(
                    "Cannot alter unknown table: " + tableName
            );
        }

        Map<String, ColumnSchema> columns =
                new LinkedHashMap<>(table.columns());
        List<String> primaryKey = new ArrayList<>(table.primaryKey());
        List<IndexSchema> indexes = new ArrayList<>(table.indexes());
        List<ConstraintSchema> constraints =
                new ArrayList<>(table.constraints());
        String effectiveTableName = table.name();

        for (String operation : splitDefinitions(matcher.group(3))) {
            String item = operation.trim();
            String upper = item.toUpperCase(Locale.ROOT);

            if (upper.startsWith("ADD COLUMN ")) {
                ColumnSchema column = parseColumn(
                        item.substring("ADD COLUMN ".length()).trim()
                );
                addColumn(columns, column, tableName);
            } else if (upper.startsWith("ADD ")) {
                if (upper.startsWith("ADD KEY ")
                        || upper.startsWith("ADD INDEX ")
                        || upper.startsWith("ADD UNIQUE KEY ")
                        || upper.startsWith("ADD UNIQUE INDEX ")) {
                    indexes.add(parseIndex(
                            item.substring("ADD ".length()).trim(),
                            upper.contains("UNIQUE")
                    ));
                } else if (upper.startsWith("ADD CONSTRAINT ")
                        || upper.startsWith("ADD FOREIGN KEY")) {
                    constraints.add(parseForeignKey(
                            item.substring("ADD ".length()).trim()
                    ));
                } else {
                    ColumnSchema column = parseColumn(
                            item.substring("ADD ".length()).trim()
                    );
                    addColumn(columns, column, tableName);
                }
            } else if (upper.startsWith("DROP COLUMN ")) {
                String columnName = normalizeIdentifier(
                        item.substring("DROP COLUMN ".length()).trim()
                );
                dropColumn(columns, primaryKey, indexes, constraints,
                        columnName, tableName);
            } else if (upper.startsWith("DROP PRIMARY KEY")) {
                primaryKey.clear();
            } else if (upper.startsWith("DROP INDEX ")) {
                String indexName = normalizeIdentifier(
                        item.substring("DROP INDEX ".length()).trim()
                );
                indexes.removeIf(index -> index.name().equalsIgnoreCase(indexName));
            } else if (upper.startsWith("DROP KEY ")) {
                String indexName = normalizeIdentifier(
                        item.substring("DROP KEY ".length()).trim()
                );
                indexes.removeIf(index -> index.name().equalsIgnoreCase(indexName));
            } else if (upper.startsWith("DROP CONSTRAINT ")) {
                String constraintName = normalizeIdentifier(
                        item.substring("DROP CONSTRAINT ".length()).trim()
                );
                constraints.removeIf(constraint ->
                        constraint.name().equalsIgnoreCase(constraintName)
                );
            } else if (upper.startsWith("DROP FOREIGN KEY ")) {
                String constraintName = normalizeIdentifier(
                        item.substring("DROP FOREIGN KEY ".length()).trim()
                );
                constraints.removeIf(constraint ->
                        constraint.name().equalsIgnoreCase(constraintName)
                );
            } else if (upper.startsWith("MODIFY COLUMN ")) {
                ColumnSchema column = parseColumn(
                        item.substring("MODIFY COLUMN ".length()).trim()
                );
                modifyColumn(columns, column, tableName);
            } else if (upper.startsWith("MODIFY ")) {
                ColumnSchema column = parseColumn(
                        item.substring("MODIFY ".length()).trim()
                );
                modifyColumn(columns, column, tableName);
            } else if (upper.startsWith("CHANGE COLUMN ")) {
                renameOrChangeColumn(columns, primaryKey, indexes,
                        constraints, item.substring("CHANGE COLUMN ".length()),
                        tableName, true);
            } else if (upper.startsWith("RENAME COLUMN ")) {
                renameOrChangeColumn(columns, primaryKey, indexes,
                        constraints, item.substring("RENAME COLUMN ".length()),
                        tableName, false);
            } else if (upper.startsWith("RENAME TO ")) {
                effectiveTableName = normalizeIdentifier(
                        item.substring("RENAME TO ".length()).trim()
                );
                if (tables.containsKey(effectiveTableName)
                        && !effectiveTableName.equalsIgnoreCase(tableName)) {
                    throw new IllegalArgumentException(
                            "Table already exists in parsed schema: "
                                    + effectiveTableName
                    );
                }
            } else {
                throw new IllegalArgumentException(
                        "Unsupported ALTER TABLE operation: " + item
                );
            }
        }

        TableSchema updatedTable = new TableSchema(
                effectiveTableName,
                columns,
                primaryKey,
                indexes,
                constraints
        );

        tables.remove(tableName);
        tables.put(effectiveTableName, updatedTable);
    }

    private void parseDropTable(
            String sql,
            Map<String, TableSchema> tables
    ) {
        Matcher matcher = DROP_TABLE_PATTERN.matcher(sql.trim());

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Unsupported DROP TABLE statement: " + sql
            );
        }

        String tableName = matcher.group(1) != null
                ? matcher.group(1)
                : matcher.group(2);
        String normalizedName = normalizeIdentifier(tableName);

        if (tables.containsKey(normalizedName)) {
            tables.remove(normalizedName);
        }
    }

    private void parseRenameTable(
            String sql,
            Map<String, TableSchema> tables
    ) {
        Matcher matcher = RENAME_TABLE_PATTERN.matcher(sql.trim());

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Unsupported RENAME TABLE statement: " + sql
            );
        }

        String oldName = matcher.group(1) != null
                ? matcher.group(1)
                : matcher.group(2);
        String newName = matcher.group(3) != null
                ? matcher.group(3)
                : matcher.group(4);

        String originalName = normalizeIdentifier(oldName);
        String renamedName = normalizeIdentifier(newName);

        if (!tables.containsKey(originalName)) {
            throw new IllegalArgumentException(
                    "Cannot rename unknown table: " + originalName
            );
        }

        if (tables.containsKey(renamedName)
                && !renamedName.equalsIgnoreCase(originalName)) {
            throw new IllegalArgumentException(
                    "Table already exists in parsed schema: " + renamedName
            );
        }

        TableSchema table = tables.remove(originalName);
        tables.put(
                renamedName,
                new TableSchema(
                        renamedName,
                        new LinkedHashMap<>(table.columns()),
                        new ArrayList<>(table.primaryKey()),
                        new ArrayList<>(table.indexes()),
                        new ArrayList<>(table.constraints())
                )
        );
    }

    private void renameOrChangeColumn(
            Map<String, ColumnSchema> columns,
            List<String> primaryKey,
            List<IndexSchema> indexes,
            List<ConstraintSchema> constraints,
            String definition,
            String tableName,
            boolean changeColumn
    ) {
        String trimmed = definition.trim();
        Matcher renameMatcher = Pattern.compile(
                "(?is)^(?:`?([\\w]+)`?|([\\w]+))\\s+TO\\s+(?:`?([\\w]+)`?|([\\w]+))$"
        ).matcher(trimmed);

        if (renameMatcher.matches()) {
            String originalName = normalizeIdentifier(
                    renameMatcher.group(1) != null
                            ? renameMatcher.group(1)
                            : renameMatcher.group(2)
            );
            String newName = normalizeIdentifier(
                    renameMatcher.group(3) != null
                            ? renameMatcher.group(3)
                            : renameMatcher.group(4)
            );

            renameColumn(columns, primaryKey, indexes, constraints,
                    originalName, newName, tableName);
            return;
        }

        Matcher matcher = Pattern.compile(
                "(?is)^(?:`?([\\w]+)`?|([\\w]+))\\s+(?:`?([\\w]+)`?|([\\w]+))\\s*(.*)$"
        ).matcher(trimmed);

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Unsupported column rename/change definition: " + definition
            );
        }

        String originalName = normalizeIdentifier(
                matcher.group(1) != null ? matcher.group(1) : matcher.group(2)
        );
        String newName = normalizeIdentifier(
                matcher.group(3) != null ? matcher.group(3) : matcher.group(4)
        );
        String newDefinition = matcher.group(5).trim();

        if (!columns.containsKey(originalName)) {
            throw new IllegalArgumentException(
                    "Cannot change unknown column " + originalName
                            + " in table " + tableName
            );
        }

        ColumnSchema originalColumn = columns.remove(originalName);
        ColumnSchema replacement = changeColumn
                ? parseColumn(newName + " " + newDefinition)
                : new ColumnSchema(
                        newName,
                        originalColumn.dataType(),
                        originalColumn.nullable(),
                        originalColumn.defaultValue(),
                        originalColumn.autoIncrement()
                );

        columns.put(newName, replacement);

        primaryKey.replaceAll(name ->
                name.equalsIgnoreCase(originalName) ? newName : name
        );

        indexes.replaceAll(index -> new IndexSchema(
                index.name(),
                index.unique(),
                index.columns().stream()
                        .map(name -> name.equalsIgnoreCase(originalName)
                                ? newName
                                : name)
                        .toList()
        ));

        constraints.replaceAll(constraint -> new ConstraintSchema(
                constraint.name(),
                constraint.type(),
                constraint.columns().stream()
                        .map(name -> name.equalsIgnoreCase(originalName)
                                ? newName
                                : name)
                        .toList(),
                constraint.referencedTable(),
                constraint.referencedColumns().stream()
                        .map(name -> name.equalsIgnoreCase(originalName)
                                ? newName
                                : name)
                        .toList()
        ));
    }

    private void renameColumn(
            Map<String, ColumnSchema> columns,
            List<String> primaryKey,
            List<IndexSchema> indexes,
            List<ConstraintSchema> constraints,
            String originalName,
            String newName,
            String tableName
    ) {
        if (!columns.containsKey(originalName)) {
            throw new IllegalArgumentException(
                    "Cannot rename unknown column " + originalName
                            + " in table " + tableName
            );
        }

        ColumnSchema originalColumn = columns.remove(originalName);
        columns.put(newName, new ColumnSchema(
                newName,
                originalColumn.dataType(),
                originalColumn.nullable(),
                originalColumn.defaultValue(),
                originalColumn.autoIncrement()
        ));

        primaryKey.replaceAll(name ->
                name.equalsIgnoreCase(originalName) ? newName : name
        );

        indexes.replaceAll(index -> new IndexSchema(
                index.name(),
                index.unique(),
                index.columns().stream()
                        .map(name -> name.equalsIgnoreCase(originalName)
                                ? newName
                                : name)
                        .toList()
        ));

        constraints.replaceAll(constraint -> new ConstraintSchema(
                constraint.name(),
                constraint.type(),
                constraint.columns().stream()
                        .map(name -> name.equalsIgnoreCase(originalName)
                                ? newName
                                : name)
                        .toList(),
                constraint.referencedTable(),
                constraint.referencedColumns().stream()
                        .map(name -> name.equalsIgnoreCase(originalName)
                                ? newName
                                : name)
                        .toList()
        ));
    }

    private void addColumn(
            Map<String, ColumnSchema> columns,
            ColumnSchema column,
            String tableName
    ) {
        if (columns.containsKey(column.name())) {
            throw new IllegalArgumentException(
                    "Column already exists in table " + tableName
                            + ": " + column.name()
            );
        }

        columns.put(column.name(), column);
    }

    private void dropColumn(
            Map<String, ColumnSchema> columns,
            List<String> primaryKey,
            List<IndexSchema> indexes,
            List<ConstraintSchema> constraints,
            String columnName,
            String tableName
    ) {
        if (columns.remove(columnName) == null) {
            throw new IllegalArgumentException(
                    "Cannot drop unknown column " + columnName
                            + " from table " + tableName
            );
        }

        primaryKey.removeIf(name -> name.equalsIgnoreCase(columnName));

        indexes.removeIf(index ->
                index.columns().stream()
                        .anyMatch(name -> name.equalsIgnoreCase(columnName))
        );

        constraints.removeIf(constraint ->
                constraint.columns().stream()
                        .anyMatch(name -> name.equalsIgnoreCase(columnName))
        );
    }

    private void modifyColumn(
            Map<String, ColumnSchema> columns,
            ColumnSchema column,
            String tableName
    ) {
        if (!columns.containsKey(column.name())) {
            throw new IllegalArgumentException(
                    "Cannot modify unknown column " + column.name()
                            + " in table " + tableName
            );
        }

        columns.put(column.name(), column);
    }

    private String normalizeIdentifier(String value) {
        String name = value.trim();

        if (name.startsWith("`") && name.endsWith("`")
                && name.length() >= 2) {
            name = name.substring(1, name.length() - 1);
        } else if (name.startsWith("\"") && name.endsWith("\"")
                && name.length() >= 2) {
            name = name.substring(1, name.length() - 1);
        }

        if (!name.matches("[\\w]+")) {
            throw new IllegalArgumentException(
                    "Unsupported column identifier: " + value
            );
        }

        return name;
    }

    private List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (c == '\\' && i + 1 < sql.length()) {
                current.append(c);
                current.append(sql.charAt(++i));
                continue;
            }

            if (c == '\'' && !inDoubleQuote && !inBacktick) {
                if (inSingleQuote && i + 1 < sql.length()
                        && sql.charAt(i + 1) == '\'') {
                    current.append(c);
                    current.append(sql.charAt(++i));
                    continue;
                }
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote && !inBacktick) {
                inDoubleQuote = !inDoubleQuote;
            } else if (c == '`' && !inSingleQuote && !inDoubleQuote) {
                inBacktick = !inBacktick;
            }

            if (c == ';' && !inSingleQuote
                    && !inDoubleQuote && !inBacktick) {
                statements.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        if (!current.toString().isBlank()) {
            statements.add(current.toString());
        }

        return statements;
    }

    private List<String> splitDefinitions(String definition) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inString = false;
        char quote = '\0';

        for (int i = 0; i < definition.length(); i++) {
            char c = definition.charAt(i);

            if ((c == '\'' || c == '"') && !inString) {
                inString = true;
                quote = c;
            } else if (c == quote && inString
                    && (i == 0 || definition.charAt(i - 1) != '\\')) {
                inString = false;
            }

            if (!inString) {
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }

            current.append(c);
        }

        if (!current.toString().isBlank()) {
            parts.add(current.toString());
        }

        return parts;
    }

    private ColumnSchema parseColumn(String definition) {
        Matcher matcher = Pattern.compile(
                "(?is)^[`\"]?([\\w]+)[`\"]?\\s+([\\w]+(?:\\s*\\([^)]*\\))?(?:\\s+UNSIGNED)?)\\s*(.*)$"
        ).matcher(definition.trim());

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Unsupported column definition: " + definition
            );
        }

        String name = matcher.group(1);
        String dataType = matcher.group(2).trim();
        String options = matcher.group(3);
        String upper = options.toUpperCase(Locale.ROOT);

        boolean nullable = !upper.contains("NOT NULL");
        boolean autoIncrement = upper.contains("AUTO_INCREMENT");
        String defaultValue = null;

        Matcher defaultMatcher = Pattern.compile(
                "(?is)\\bDEFAULT\\s+('(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|[^\\s,]+)"
        ).matcher(options);

        if (defaultMatcher.find()) {
            defaultValue = defaultMatcher.group(1);
        }

        return new ColumnSchema(
                name,
                dataType,
                nullable,
                defaultValue,
                autoIncrement
        );
    }

    private IndexSchema parseIndex(String definition, boolean unique) {
        Matcher matcher = Pattern.compile(
                "(?is)^(?:UNIQUE\\s+)?(?:KEY|INDEX)\\s+(?:`([^`]+)`|([\\w]+))\\s*\\((.*?)\\)"
        ).matcher(definition.trim());

        if (!matcher.find()) {
            throw new IllegalArgumentException(
                    "Unsupported index definition: " + definition
            );
        }

        String name = matcher.group(1) != null
                ? matcher.group(1)
                : matcher.group(2);

        return new IndexSchema(
                name,
                unique,
                extractColumns(matcher.group(3))
        );
    }

    private ConstraintSchema parseForeignKey(String definition) {
        Matcher matcher = Pattern.compile(
                "(?is)^(?:CONSTRAINT\\s+`?([\\w]+)`?\\s+)?FOREIGN\\s+KEY\\s*(?:`?([\\w]+)`?\\s*)?\\((.*?)\\)\\s*REFERENCES\\s+(?:`([^`]+)`|([\\w]+))\\s*\\((.*?)\\)"
        ).matcher(definition.trim());

        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Unsupported foreign key definition: " + definition
            );
        }

        String name = matcher.group(1) != null
                ? matcher.group(1)
                : "unnamed_foreign_key";

        String referencedTable = matcher.group(4) != null
                ? matcher.group(4)
                : matcher.group(5);

        return new ConstraintSchema(
                name,
                "FOREIGN KEY",
                extractColumns(matcher.group(3)),
                referencedTable,
                extractColumns(matcher.group(6))
        );
    }

    private List<String> extractColumns(String definition) {
        List<String> columns = new ArrayList<>();

        int openingParen = definition.indexOf('(');
        int closingParen = definition.lastIndexOf(')');

        String columnDefinitions;

        if (openingParen >= 0 && closingParen > openingParen) {
            columnDefinitions = definition.substring(
                    openingParen + 1,
                    closingParen
            );
        } else {
            columnDefinitions = definition;
        }

        for (String column : columnDefinitions.split(",")) {
            String cleaned = column.trim()
                    .replaceAll("\\s*\\(.*", "")
                    .replace("`", "")
                    .replace("\"", "");

            if (!cleaned.isBlank()) {
                columns.add(cleaned);
            }
        }

        return columns;
    }

    private int findMatchingParenthesis(String sql, int openingParen) {
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;

        for (int i = openingParen; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (c == '\\' && i + 1 < sql.length()) {
                i++;
                continue;
            }

            if (c == '\'' && !inDoubleQuote && !inBacktick) {
                if (inSingleQuote && i + 1 < sql.length()
                        && sql.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                inSingleQuote = !inSingleQuote;
                continue;
            }

            if (c == '"' && !inSingleQuote && !inBacktick) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }

            if (c == '`' && !inSingleQuote && !inDoubleQuote) {
                inBacktick = !inBacktick;
                continue;
            }

            if (inSingleQuote || inDoubleQuote || inBacktick) {
                continue;
            }

            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;

                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }
}
