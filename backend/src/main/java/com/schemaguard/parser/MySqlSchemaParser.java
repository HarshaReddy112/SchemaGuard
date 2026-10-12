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

    public DatabaseSchema parse(String sql) {
        Map<String, TableSchema> tables = new LinkedHashMap<>();

        Pattern tablePattern = Pattern.compile(
        "(?is)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:`([^`]+)`|([\\w]+))\\s*\\("
        );

        Matcher tableMatcher = tablePattern.matcher(sql);

        while (tableMatcher.find()) {
            String tableName = tableMatcher.group(1) != null? tableMatcher.group(1): tableMatcher.group(2);

            int openingParen = tableMatcher.end() - 1;
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

        if (tables.isEmpty()) {
            throw new IllegalArgumentException(
                    "No CREATE TABLE statements were found."
            );
        }

        return new DatabaseSchema(tables);
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
        Matcher matcher = Pattern.compile("(?is)^(?:UNIQUE\\s+)?(?:KEY|INDEX)\\s+(?:`([^`]+)`|([\\w]+))\\s*\\((.*?)\\)").matcher(definition.trim());

        if (!matcher.find()) {
            throw new IllegalArgumentException("Unsupported index definition: " + definition);
        }

        String name = matcher.group(1) != null? matcher.group(1): matcher.group(2);

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