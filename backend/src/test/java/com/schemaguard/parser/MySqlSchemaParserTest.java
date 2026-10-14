package com.schemaguard.parser;

import com.schemaguard.model.DatabaseSchema;
import com.schemaguard.model.TableSchema;

import com.schemaguard.parser.MySqlSchemaParser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MySqlSchemaParserTest {

    private final MySqlSchemaParser parser = new MySqlSchemaParser();

    @Test
    void parsesTableAndColumns() {
        String sql = """
                CREATE TABLE users (
                    id BIGINT NOT NULL AUTO_INCREMENT,
                    name VARCHAR(100) NOT NULL,
                    email VARCHAR(255),
                    PRIMARY KEY (id)
                );
                """;

        DatabaseSchema schema = parser.parse(sql);
        TableSchema users = schema.tables().get("users");

        assertNotNull(users);
        assertEquals(3, users.columns().size());
        assertEquals("BIGINT", users.columns().get("id").dataType());
        assertFalse(users.columns().get("id").nullable());
        assertTrue(users.columns().get("id").autoIncrement());
        assertEquals("VARCHAR(100)", users.columns().get("name").dataType());
        assertEquals("id", users.primaryKey().get(0));
    }

    @Test
    void rejectsSqlWithoutCreateTable() {
        assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("SELECT * FROM users;")
        );
    }

    
    @Test
    void parsesIndexesAndForeignKeys() {
        String sql = """
            CREATE TABLE users (
                id BIGINT NOT NULL,
                PRIMARY KEY (id)
            );

            CREATE TABLE orders (
                id BIGINT NOT NULL,
                user_id BIGINT NOT NULL,
                KEY idx_user_id (user_id),
                CONSTRAINT fk_orders_user
                    FOREIGN KEY (user_id) REFERENCES users (id),
                PRIMARY KEY (id)
            );
            """;

        DatabaseSchema schema = parser.parse(sql);

        TableSchema orders = schema.tables().get("orders");

        assertNotNull(orders);
        assertEquals(1, orders.indexes().size());
        assertEquals("idx_user_id", orders.indexes().get(0).name());
        assertEquals("user_id", orders.indexes().get(0).columns().get(0));

        assertEquals(1, orders.constraints().size());
        
        assertEquals(
            "users",
            orders.constraints().get(0).referencedTable()
        );
    }

    @Test
    void parsesAddedColumnFromMigration() {
        String sql = """
                CREATE TABLE users (
                    id BIGINT NOT NULL
                );
        ALTER TABLE users
                    ADD COLUMN email VARCHAR(255) NOT NULL;
                """;
        DatabaseSchema schema = parser.parse(sql);
        TableSchema users = schema.tables().get("users");
        assertEquals(2, users.columns().size());
        assertEquals(
                "VARCHAR(255)",
                users.columns().get("email").dataType()
        );
        assertFalse(users.columns().get("email").nullable());
    }

    @Test
    void parsesDroppedColumnFromMigration() {
        String sql = """
                CREATE TABLE users (
                    id BIGINT NOT NULL,
                    email VARCHAR(255)
                );
        ALTER TABLE users DROP COLUMN email;
                """;
        DatabaseSchema schema = parser.parse(sql);
        TableSchema users = schema.tables().get("users");
        assertEquals(1, users.columns().size());
        assertTrue(users.columns().containsKey("id"));
        assertFalse(users.columns().containsKey("email"));
    }

    @Test
    void parsesModifiedColumnFromMigration() {
        String sql = """
                CREATE TABLE users (
                    id INT NOT NULL,
                    name VARCHAR(50)
                );
        ALTER TABLE users
                    MODIFY COLUMN name VARCHAR(150) NOT NULL;
                """;
        DatabaseSchema schema = parser.parse(sql);
        TableSchema users = schema.tables().get("users");
        assertEquals(
                "VARCHAR(150)",
                users.columns().get("name").dataType()
        );
        assertFalse(users.columns().get("name").nullable());
    }

    @Test
    void appliesMultipleMigrationStatementsInOrder() {
        String sql = """
                CREATE TABLE users (
                    id BIGINT NOT NULL,
                    name VARCHAR(50)
                );
        ALTER TABLE users ADD COLUMN email VARCHAR(255);
                ALTER TABLE users MODIFY COLUMN name VARCHAR(100) NOT NULL;
                ALTER TABLE users DROP COLUMN email;
                """;
        DatabaseSchema schema = parser.parse(sql);
        TableSchema users = schema.tables().get("users");
        assertEquals(2, users.columns().size());
        assertEquals(
                "VARCHAR(100)",
                users.columns().get("name").dataType()
        );
        assertFalse(users.columns().get("name").nullable());
        assertFalse(users.columns().containsKey("email"));
    }

    @Test
    void rejectsUnsupportedSqlStatements() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("""
                        CREATE TABLE users (
                            id BIGINT NOT NULL,
                            PRIMARY KEY (id)
                        );
                        INSERT INTO users (id) VALUES (1);
                        """)
        );

        assertTrue(exception.getMessage().contains("Unsupported SQL statement"));
    }

    @Test
    void supportsDroppedTablesAndRenames() {
        String sql = """
                CREATE TABLE users (
                    id BIGINT NOT NULL,
                    PRIMARY KEY (id)
                );
                CREATE TABLE orders (
                    id BIGINT NOT NULL,
                    user_id BIGINT,
                    PRIMARY KEY (id)
                );
                ALTER TABLE orders RENAME TO order_archive;
                DROP TABLE users;
                """;

        DatabaseSchema schema = parser.parse(sql);

        assertNotNull(schema.tables().get("order_archive"));
        assertFalse(schema.tables().containsKey("users"));
        assertFalse(schema.tables().containsKey("orders"));
    }

}
