package com.schemaguard.dependency;

import com.schemaguard.dependency.model.RepositoryRegistration;
import com.schemaguard.dependency.model.RepositoryRegistrationRequest;
import com.schemaguard.dependency.model.RepositoryScanResult;
import com.schemaguard.dependency.service.RepositoryDependencyScannerService;
import com.schemaguard.dependency.service.RepositoryRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryDependencyScannerServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void scansMultipleRepositoriesAndCollectsTableAndColumnEvidence() throws Exception {
        Path userService = Files.createDirectories(tempDir.resolve("user-service"));
        Path orderService = Files.createDirectories(tempDir.resolve("order-service"));

        Files.writeString(userService.resolve("UserRepository.java"), """
                package example;
                class UserRepository {
                    String query = "SELECT id, email FROM users WHERE id = ?";
                }
                """);

        Files.writeString(orderService.resolve("OrderRepository.sql"), """
                SELECT id, user_id FROM orders;
                SELECT id FROM users;
                """);

        RepositoryRegistryService registry = new RepositoryRegistryService(tempDir.toString());
        registry.register(new RepositoryRegistrationRequest(
                "user-service", "User Service", "https://github.com/example/user-service",
                userService.toString(), "Java"));
        registry.register(new RepositoryRegistrationRequest(
                "order-service", "Order Service", "https://github.com/example/order-service",
                orderService.toString(), "SQL"));

        RepositoryDependencyScannerService scanner = new RepositoryDependencyScannerService(registry);
        List<RepositoryScanResult> results = scanner.scanRepositories(List.of());

        assertEquals(2, results.size());

        RepositoryScanResult userResult = results.stream()
                .filter(result -> result.repositoryId().equals("user-service"))
                .findFirst()
                .orElseThrow();

        assertTrue(userResult.dependencies().stream().anyMatch(dependency ->
                "TABLE".equals(dependency.databaseObjectType())
                        && "users".equalsIgnoreCase(dependency.tableName())));

        assertTrue(userResult.dependencies().stream().anyMatch(dependency ->
                "COLUMN".equals(dependency.databaseObjectType())
                        && "users".equalsIgnoreCase(dependency.tableName())
                        && "email".equalsIgnoreCase(dependency.columnName())));

        RepositoryScanResult orderResult = results.stream()
                .filter(result -> result.repositoryId().equals("order-service"))
                .findFirst()
                .orElseThrow();

        assertTrue(orderResult.dependencies().stream().anyMatch(dependency ->
                "orders".equalsIgnoreCase(dependency.tableName())));
    }

    @Test
    void recordsJpaTableAndColumnAnnotations() throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve("catalog-service"));
        Files.writeString(repository.resolve("UserEntity.java"), """
                @Entity
                @Table(name = "users")
                class UserEntity {
                    @Column(name = "email")
                    String email;
                }
                """);

        RepositoryRegistryService registry = new RepositoryRegistryService(tempDir.toString());
        RepositoryRegistration registration = registry.register(new RepositoryRegistrationRequest(
                "catalog", "Catalog Service", null, repository.toString(), "Java"));

        RepositoryDependencyScannerService scanner = new RepositoryDependencyScannerService(registry);
        RepositoryScanResult result = scanner.scanRepository(registration);

        assertTrue(result.dependencies().stream().anyMatch(dependency ->
                "JPA_TABLE_ANNOTATION".equals(dependency.detectionMethod())
                        && "users".equalsIgnoreCase(dependency.tableName())
                        && "HIGH".equals(dependency.confidence())));

        assertTrue(result.dependencies().stream().anyMatch(dependency ->
                "JPA_COLUMN_ANNOTATION".equals(dependency.detectionMethod())
                        && "users".equalsIgnoreCase(dependency.tableName())
                        && "email".equalsIgnoreCase(dependency.columnName())));
    }

    @Test
    void rejectsRepositoryPathsOutsideAllowedRoots() throws Exception {
        RepositoryRegistryService registry = new RepositoryRegistryService(tempDir.toString());

        assertThrows(IllegalArgumentException.class, () -> registry.register(
                new RepositoryRegistrationRequest(
                        "outside", "Outside Repository", null,
                        tempDir.getParent().toString(), "Java")));
    }

    @Test
    void rejectsDuplicateRepositoryIds() throws Exception {
        Path repository = Files.createDirectories(tempDir.resolve("service"));
        RepositoryRegistryService registry = new RepositoryRegistryService(tempDir.toString());
        RepositoryRegistrationRequest request = new RepositoryRegistrationRequest(
                "service", "Service", null, repository.toString(), "Java");

        registry.register(request);

        assertThrows(IllegalArgumentException.class, () -> registry.register(request));
    }
}
