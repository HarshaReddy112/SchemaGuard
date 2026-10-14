package com.schemaguard.service;

import com.schemaguard.model.DependencyScanResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DependencyScanServiceTest {

    @Test
    void scansJavaSqlReferencesInRepository() throws Exception {
        Path tempDir = Files.createTempDirectory("schemaguard-scan");
        Path appDir = tempDir.resolve("app");
        Files.createDirectories(appDir);

        Path javaFile = appDir.resolve("UserRepository.java");
        Files.writeString(javaFile,
                "String sql = \"SELECT id, email FROM users WHERE id = ?\";\n"
                        + "jdbcTemplate.query(\"SELECT * FROM orders\");",
                java.nio.charset.StandardCharsets.UTF_8);

        DependencyScanResult result = new DependencyScanService().scanRepository(appDir.toString());

        assertEquals(2, result.evidence().size());
        assertTrue(result.evidence().stream().anyMatch(e -> e.tableName().equals("users")));
        assertTrue(result.evidence().stream().anyMatch(e -> e.tableName().equals("orders")));
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
    void rejectsUnsafeRepositoryPaths() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyScanService().scanRepository("../unsafe-root")
        );

        assertTrue(exception.getMessage().contains("must not include parent traversal"));
    }
}
