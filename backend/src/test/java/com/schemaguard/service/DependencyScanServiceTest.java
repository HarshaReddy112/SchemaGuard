package com.schemaguard.service;

import com.schemaguard.model.DependencyScanResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DependencyScanServiceTest {

    @Test
    void scansJavaSqlReferencesAndSelectColumnsInRepository() throws Exception {
        Path tempDir = Files.createTempDirectory("schemaguard-scan");
        Path appDir = tempDir.resolve("app");
        Files.createDirectories(appDir);

        Path javaFile = appDir.resolve("UserRepository.java");
        Files.writeString(javaFile,
                "String sql = \"SELECT id, email FROM users WHERE id = ?\";\n"
                        + "jdbcTemplate.query(\"SELECT * FROM orders\");",
                java.nio.charset.StandardCharsets.UTF_8);

        DependencyScanResult result = new DependencyScanService().scanRepository(appDir.toString());

        assertTrue(result.evidence().stream().anyMatch(e ->
                "users".equals(e.tableName()) && "static-sql-pattern".equals(e.detector())));
        assertTrue(result.evidence().stream().anyMatch(e ->
                "users".equals(e.tableName())
                        && "email".equals(e.columnName())
                        && "static-select-projection".equals(e.detector())));
        assertTrue(result.evidence().stream().anyMatch(e ->
                "orders".equals(e.tableName()) && "static-sql-pattern".equals(e.detector())));
    }

    @Test
    void findsJpaAndJdbcPatternsAcrossCommonApplicationAccessPatterns() throws Exception {
        Path tempDir = Files.createTempDirectory("schemaguard-jpa-scan");
        Path appDir = tempDir.resolve("app");
        Files.createDirectories(appDir);

        Path javaFile = appDir.resolve("OrderRepository.java");
        Files.writeString(javaFile,
                "@Entity\n"
                        + "@Table(name = \"orders\")\n"
                        + "public class OrderEntity {}\n\n"
                        + "@Query(value = \"SELECT * FROM invoices WHERE customer_id = :id\")\n"
                        + "jdbcTemplate.queryForObject(\"SELECT * FROM users WHERE id = ?\", Long.class, 1L);",
                java.nio.charset.StandardCharsets.UTF_8);

        DependencyScanResult result = new DependencyScanService().scanRepository(appDir.toString());

        assertTrue(result.evidence().stream().anyMatch(e -> e.tableName().equals("orders")));
        assertTrue(result.evidence().stream().anyMatch(e -> e.tableName().equals("invoices")));
        assertTrue(result.evidence().stream().anyMatch(e -> e.tableName().equals("users")));
    }

    @Test
    void associatesJpaColumnWithItsExplicitTableMapping() throws Exception {
        Path tempDir = Files.createTempDirectory("schemaguard-jpa-columns");
        Path appDir = Files.createDirectories(tempDir.resolve("app"));

        Files.writeString(appDir.resolve("UserEntity.java"),
                "@Entity\n"
                        + "@Table(name = \"users\")\n"
                        + "class UserEntity {\n"
                        + "    @Column(name = \"email\")\n"
                        + "    String email;\n"
                        + "}\n",
                java.nio.charset.StandardCharsets.UTF_8);

        DependencyScanResult result = new DependencyScanService().scanRepository(appDir.toString());

        assertTrue(result.evidence().stream().anyMatch(e ->
                "users".equals(e.tableName())
                        && "email".equals(e.columnName())
                        && "jpa-column-annotation".equals(e.detector())
                        && "HIGH".equals(e.confidence())));
    }

    @Test
    void ignoresSqlReferencesInsideSourceComments() throws Exception {
        Path tempDir = Files.createTempDirectory("schemaguard-comments");
        Path appDir = Files.createDirectories(tempDir.resolve("app"));

        Files.writeString(appDir.resolve("Example.java"),
                "// SELECT id FROM fake_line_table;\n"
                        + "String sql = \"SELECT id, email FROM users\";\n"
                        + "/* SELECT id FROM fake_block_table; */\n",
                java.nio.charset.StandardCharsets.UTF_8);

        DependencyScanResult result = new DependencyScanService().scanRepository(appDir.toString());

        assertTrue(result.evidence().stream().anyMatch(e -> "users".equals(e.tableName())));
        assertFalse(result.evidence().stream().anyMatch(e -> "fake_line_table".equals(e.tableName())));
        assertFalse(result.evidence().stream().anyMatch(e -> "fake_block_table".equals(e.tableName())));
        assertTrue(result.evidence().stream().anyMatch(e ->
                "users".equals(e.tableName()) && "email".equals(e.columnName())));
    }

    @Test
    void rejectsUnsafeRepositoryPaths() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyScanService().scanRepository("../unsafe-root")
        );

        assertTrue(exception.getMessage().contains("must not include parent traversal"));
    }
}
