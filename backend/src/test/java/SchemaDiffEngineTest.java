
package com.schemaguard.diff;

import com.schemaguard.diff.SchemaDiffEngine;
import com.schemaguard.diff.SchemaDiffResult;
import com.schemaguard.model.DatabaseSchema;
import com.schemaguard.parser.MySqlSchemaParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SchemaDiffEngineTest {

    private final MySqlSchemaParser parser = new MySqlSchemaParser();
    private final SchemaDiffEngine engine = new SchemaDiffEngine();

    @Test
    void detectsRemovedColumn() {
        DatabaseSchema oldSchema = parser.parse("""
                CREATE TABLE users (
                    id BIGINT NOT NULL,
                    email VARCHAR(255),
                    PRIMARY KEY (id)
                );
                """);

        DatabaseSchema newSchema = parser.parse("""
                CREATE TABLE users (
                    id BIGINT NOT NULL,
                    PRIMARY KEY (id)
                );
                """);

        SchemaDiffResult result = engine.compare(oldSchema, newSchema);

        assertTrue(result.changes().stream().anyMatch(change ->
                change.objectType().equals("COLUMN")
                        && change.changeType().equals("REMOVED")
                        && change.objectName().equals("email")
                        && change.riskLevel().equals("HIGH")
        ));
    }

    @Test
    void detectsAddedTable() {
        DatabaseSchema oldSchema = parser.parse("""
                CREATE TABLE users (
                    id BIGINT NOT NULL,
                    PRIMARY KEY (id)
                );
                """);

        DatabaseSchema newSchema = parser.parse("""
                CREATE TABLE users (
                    id BIGINT NOT NULL,
                    PRIMARY KEY (id)
                );

                CREATE TABLE payments (
                    id BIGINT NOT NULL,
                    PRIMARY KEY (id)
                );
                """);

        SchemaDiffResult result = engine.compare(oldSchema, newSchema);

        assertTrue(result.changes().stream().anyMatch(change ->
                change.objectType().equals("TABLE")
                        && change.changeType().equals("ADDED")
                        && change.objectName().equals("payments")
        ));
    }

    @Test
    void identicalSchemasProduceNoChanges() {
        DatabaseSchema oldSchema = parser.parse("""
                CREATE TABLE users (
                    id BIGINT NOT NULL,
                    name VARCHAR(100),
                    PRIMARY KEY (id)
                );
                """);

        DatabaseSchema newSchema = parser.parse("""
                CREATE TABLE users (
                    id BIGINT NOT NULL,
                    name VARCHAR(100),
                    PRIMARY KEY (id)
                );
                """);

        SchemaDiffResult result = engine.compare(oldSchema, newSchema);

        assertTrue(result.changes().isEmpty());
    }

    
@Test
void detectsColumnTypeChange() {
    DatabaseSchema oldSchema = parser.parse("""
            CREATE TABLE users (
                id BIGINT NOT NULL,
                name VARCHAR(100),
                PRIMARY KEY (id)
            );
            """);

    DatabaseSchema newSchema = parser.parse("""
            CREATE TABLE users (
                id BIGINT NOT NULL,
                name VARCHAR(50),
                PRIMARY KEY (id)
            );
            """);

    SchemaDiffResult result = engine.compare(oldSchema, newSchema);

    assertTrue(result.changes().stream().anyMatch(change ->
            change.objectType().equals("COLUMN")
                    && change.changeType().equals("MODIFIED")
                    && change.objectName().equals("name")
                    && "VARCHAR(100)".equals(change.oldValue())
                    && "VARCHAR(50)".equals(change.newValue())
                    && change.riskLevel().equals("HIGH")
    ));
}


@Test
void detectsNullabilityChange() {
    DatabaseSchema oldSchema = parser.parse("""
            CREATE TABLE users (
                id BIGINT NOT NULL,
                name VARCHAR(100),
                PRIMARY KEY (id)
            );
            """);

    DatabaseSchema newSchema = parser.parse("""
            CREATE TABLE users (
                id BIGINT NOT NULL,
                name VARCHAR(100) NOT NULL,
                PRIMARY KEY (id)
            );
            """);

    SchemaDiffResult result = engine.compare(oldSchema, newSchema);

    assertTrue(result.changes().stream().anyMatch(change ->
            change.objectType().equals("COLUMN")
                    && change.changeType().equals("MODIFIED")
                    && change.objectName().equals("name")
                    && "nullable=true".equals(change.oldValue())
                    && "nullable=false".equals(change.newValue())
                    && change.riskLevel().equals("HIGH")
    ));
}

}
