
package com.schemaguard.diff;

import com.schemaguard.diff.SchemaChange;
import com.schemaguard.diff.SchemaDiffEngine;
import com.schemaguard.diff.SchemaDiffResult;
import com.schemaguard.model.DatabaseSchema;
import com.schemaguard.parser.MySqlSchemaParser;
import org.junit.jupiter.api.Test;

import java.util.List;
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


@Test
void detectsRemovedIndex() {
    MySqlSchemaParser parser = new MySqlSchemaParser();
    SchemaDiffEngine engine = new SchemaDiffEngine();

    String oldSql = """
            CREATE TABLE orders (
                id BIGINT NOT NULL,
                user_id BIGINT,
                PRIMARY KEY (id),
                KEY idx_user_id (user_id)
            );
            """;

    String newSql = """
            CREATE TABLE orders (
                id BIGINT NOT NULL,
                user_id BIGINT,
                PRIMARY KEY (id)
            );
            """;

    DatabaseSchema oldSchema = parser.parse(oldSql);
    DatabaseSchema newSchema = parser.parse(newSql);

    List<SchemaChange> changes =
            engine.compare(oldSchema, newSchema).changes();

    assertTrue(changes.stream().anyMatch(change ->
            change.objectType().equals("INDEX")
                    && change.changeType().equals("REMOVED")
                    && change.objectName().equals("idx_user_id")
                    && change.riskLevel().equals("MEDIUM")
    ));
}

@Test
void detectsAddedIndex() {
    MySqlSchemaParser parser = new MySqlSchemaParser();
    SchemaDiffEngine engine = new SchemaDiffEngine();

    String oldSql = """
            CREATE TABLE orders (
                id BIGINT NOT NULL,
                user_id BIGINT,
                PRIMARY KEY (id)
            );
            """;

    String newSql = """
            CREATE TABLE orders (
                id BIGINT NOT NULL,
                user_id BIGINT,
                PRIMARY KEY (id),
                KEY idx_user_id (user_id)
            );
            """;

    DatabaseSchema oldSchema = parser.parse(oldSql);
    DatabaseSchema newSchema = parser.parse(newSql);

    List<SchemaChange> changes =
            engine.compare(oldSchema, newSchema).changes();

    assertTrue(changes.stream().anyMatch(change ->
            change.objectType().equals("INDEX")
                    && change.changeType().equals("ADDED")
                    && change.objectName().equals("idx_user_id")
                    && change.riskLevel().equals("LOW")
    ));
}

@Test
void detectsRemovedForeignKey() {
    MySqlSchemaParser parser = new MySqlSchemaParser();
    SchemaDiffEngine engine = new SchemaDiffEngine();

    String oldSql = """
            CREATE TABLE orders (
                id BIGINT NOT NULL,
                user_id BIGINT,
                PRIMARY KEY (id),
                CONSTRAINT fk_orders_user
                    FOREIGN KEY (user_id) REFERENCES users (id)
            );
            """;

    String newSql = """
            CREATE TABLE orders (
                id BIGINT NOT NULL,
                user_id BIGINT,
                PRIMARY KEY (id)
            );
            """;

    DatabaseSchema oldSchema = parser.parse(oldSql);
    DatabaseSchema newSchema = parser.parse(newSql);

    List<SchemaChange> changes =
            engine.compare(oldSchema, newSchema).changes();

    assertTrue(changes.stream().anyMatch(change ->
            change.objectType().equals("CONSTRAINT")
                    && change.changeType().equals("REMOVED")
                    && change.objectName().equals("fk_orders_user")
                    && change.riskLevel().equals("HIGH")
    ));
}

@Test
void detectsPrimaryKeyChange() {
    MySqlSchemaParser parser = new MySqlSchemaParser();
    SchemaDiffEngine engine = new SchemaDiffEngine();

    String oldSql = """
            CREATE TABLE users (
                id BIGINT NOT NULL,
                user_code BIGINT NOT NULL,
                PRIMARY KEY (id)
            );
            """;

    String newSql = """
            CREATE TABLE users (
                id BIGINT NOT NULL,
                user_code BIGINT NOT NULL,
                PRIMARY KEY (user_code)
            );
            """;

    DatabaseSchema oldSchema = parser.parse(oldSql);
    DatabaseSchema newSchema = parser.parse(newSql);

    List<SchemaChange> changes =
            engine.compare(oldSchema, newSchema).changes();

    assertTrue(changes.stream().anyMatch(change ->
            change.objectType().equals("PRIMARY_KEY")
                    && change.changeType().equals("MODIFIED")
                    && change.riskLevel().equals("HIGH")
    ));
}


@Test
void detectsIntegerWideningAsLowRisk() {
    MySqlSchemaParser parser = new MySqlSchemaParser();
    SchemaDiffEngine engine = new SchemaDiffEngine();

    DatabaseSchema oldSchema = parser.parse("""
            CREATE TABLE users (
                id INT NOT NULL
            );
            """);

    DatabaseSchema newSchema = parser.parse("""
            CREATE TABLE users (
                id BIGINT NOT NULL
            );
            """);

    List<SchemaChange> changes =
            engine.compare(oldSchema, newSchema).changes();

    assertTrue(changes.stream().anyMatch(change ->
            change.objectType().equals("COLUMN")
                    && change.objectName().equals("id")
                    && change.oldValue().equalsIgnoreCase("INT")
                    && change.newValue().equalsIgnoreCase("BIGINT")
                    && change.riskLevel().equals("LOW")
    ));
}

@Test
void detectsIntegerNarrowingAsHighRisk() {
    MySqlSchemaParser parser = new MySqlSchemaParser();
    SchemaDiffEngine engine = new SchemaDiffEngine();

    DatabaseSchema oldSchema = parser.parse("""
            CREATE TABLE users (
                id BIGINT NOT NULL
            );
            """);

    DatabaseSchema newSchema = parser.parse("""
            CREATE TABLE users (
                id INT NOT NULL
            );
            """);

    List<SchemaChange> changes =
            engine.compare(oldSchema, newSchema).changes();

    assertTrue(changes.stream().anyMatch(change ->
            change.objectType().equals("COLUMN")
                    && change.objectName().equals("id")
                    && change.riskLevel().equals("HIGH")
    ));
}

@Test
void detectsStringLengthIncreaseAsLowRisk() {
    MySqlSchemaParser parser = new MySqlSchemaParser();
    SchemaDiffEngine engine = new SchemaDiffEngine();

    DatabaseSchema oldSchema = parser.parse("""
            CREATE TABLE users (
                name VARCHAR(50)
            );
            """);

    DatabaseSchema newSchema = parser.parse("""
            CREATE TABLE users (
                name VARCHAR(100)
            );
            """);

    List<SchemaChange> changes =
            engine.compare(oldSchema, newSchema).changes();

    assertTrue(changes.stream().anyMatch(change ->
            change.objectType().equals("COLUMN")
                    && change.objectName().equals("name")
                    && change.riskLevel().equals("LOW")
    ));
}

@Test
void detectsSignednessChangeAsHighRisk() {
    MySqlSchemaParser parser = new MySqlSchemaParser();
    SchemaDiffEngine engine = new SchemaDiffEngine();

    DatabaseSchema oldSchema = parser.parse("""
            CREATE TABLE users (
                id INT NOT NULL
            );
            """);

    DatabaseSchema newSchema = parser.parse("""
            CREATE TABLE users (
                id INT UNSIGNED NOT NULL
            );
            """);

    List<SchemaChange> changes =
            engine.compare(oldSchema, newSchema).changes();

    assertTrue(changes.stream().anyMatch(change ->
            change.objectType().equals("COLUMN")
                    && change.objectName().equals("id")
                    && change.riskLevel().equals("HIGH")
    ));
}


@Test
void generatesMigrationRiskSummary() {
    MySqlSchemaParser parser = new MySqlSchemaParser();
    SchemaDiffEngine engine = new SchemaDiffEngine();

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

    assertEquals(1, result.highRiskCount());
    assertEquals(0, result.mediumRiskCount());
    assertEquals(0, result.lowRiskCount());
    assertEquals("HIGH", result.overallRisk());
    assertTrue(result.recommendations().stream().anyMatch(
            recommendation ->
                    recommendation.contains("high-risk changes")
    ));
}

@Test
void identicalSchemasProduceNoMigrationRisk() {
    MySqlSchemaParser parser = new MySqlSchemaParser();
    SchemaDiffEngine engine = new SchemaDiffEngine();

    String sql = """
            CREATE TABLE users (
                id BIGINT NOT NULL,
                email VARCHAR(255),
                PRIMARY KEY (id)
            );
            """;

    DatabaseSchema schema = parser.parse(sql);

    SchemaDiffResult result = engine.compare(schema, schema);

    assertTrue(result.changes().isEmpty());
    assertEquals(0, result.highRiskCount());
    assertEquals(0, result.mediumRiskCount());
    assertEquals(0, result.lowRiskCount());
    assertEquals("NONE", result.overallRisk());
    assertFalse(result.recommendations().isEmpty());
}

@Test
void identifiesUnambiguousSameTypeColumnRenameAsPossible() {
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
                contact_email VARCHAR(255),
                PRIMARY KEY (id)
            );
            """);

    List<SchemaChange> changes =
            engine.compare(oldSchema, newSchema).changes();

    assertEquals(1, changes.size());
    assertEquals("POSSIBLE_RENAME", changes.get(0).changeType());
    assertEquals("email", changes.get(0).objectName());
    assertEquals("contact_email: VARCHAR(255)", changes.get(0).newValue());
    assertTrue(changes.get(0).message().contains("Possible column rename"));
}


}
