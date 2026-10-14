package com.schemaguard.service;

import com.schemaguard.model.ApplicationRegistrationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyScanServiceTest {

    @TempDir
    Path repository;

    @Test
    void discoversSqlReferencesAndRegistersSourceEvidence() throws Exception {
        DependencyRegistryService registry =
                TestDatabase.newDependencyRegistryService();
        registry.registerApplication(new ApplicationRegistrationRequest(
                "billing-api",
                "Billing API",
                "billing"
        ));
        Files.writeString(repository.resolve("BillingRepository.java"), """
                class BillingRepository {
                    String query = "SELECT id FROM invoices WHERE status = 'OPEN'";
                    String update = "UPDATE invoices SET status = 'PAID'";
                }
                """);
        Files.createDirectory(repository.resolve("target"));
        Files.writeString(repository.resolve("target/generated.sql"),
                "SELECT * FROM ignored_table");
        Files.writeString(repository.resolve("hotel_reservation.sql"), """
                CREATE TABLE bookings (id INT);
                INSERT INTO bookings VALUES (1);
                """);

        DependencyScanService scanner = new DependencyScanService(registry);
        var result = scanner.scanRepository("billing-api", repository.toString());

        assertEquals(2, result.scannedFiles());
        assertEquals(1, result.dependenciesRegistered());
        assertTrue(result.dependencies().stream().anyMatch(dependency ->
                dependency.tableName().equals("invoices")
                        && dependency.source().equals("BillingRepository.java")
                        && dependency.confidence().equals("MEDIUM")
                        && dependency.evidence().contains("FROM invoices")
        ));
        assertTrue(result.dependencies().stream().noneMatch(dependency ->
                dependency.tableName().equals("ignored_table")
                        || dependency.source().equals("hotel_reservation.sql")
        ));
    }

    @Test
    void scansPythonQueriesWithoutTreatingImportsAsDatabaseDependencies()
            throws Exception {
        DependencyRegistryService registry =
                TestDatabase.newDependencyRegistryService();
        registry.registerApplication(new ApplicationRegistrationRequest(
                "flask-api",
                "Flask API",
                "hotel_db"
        ));
        Files.writeString(repository.resolve("routes.py"), """
                from flask import Flask, jsonify
                from flask_cors import CORS
                app = Flask(__name__)

                @app.route("/users", methods=["GET"])
                def list_users():
                    query = "SELECT * FROM users"
                    return query

                @app.route("/users", methods=["POST"])
                def create_user():
                    query = "INSERT INTO users (Name) VALUES ('guest')"
                    return query
                """);

        var result = new DependencyScanService(registry)
                .scanRepository("flask-api", repository.toString());

        assertTrue(result.dependencies().stream().anyMatch(dependency ->
                dependency.tableName().equals("users")
                        && dependency.apiEndpoint().equals("GET /users (list_users)")
        ));
        assertTrue(result.dependencies().stream().anyMatch(dependency ->
                dependency.tableName().equals("users")
                        && dependency.apiEndpoint().equals("POST /users (create_user)")
        ));
        assertTrue(result.dependencies().stream().noneMatch(dependency ->
                dependency.tableName().toLowerCase().startsWith("flas")
        ));
    }

    @Test
    void rescanReplacesStaleSourceEvidence() throws Exception {
        DependencyRegistryService registry =
                TestDatabase.newDependencyRegistryService();
        registry.registerApplication(new ApplicationRegistrationRequest(
                "rescan-api",
                "Rescan API",
                "billing"
        ));
        Path source = repository.resolve("Repository.java");
        Files.writeString(source, "SELECT id FROM old_invoices");
        DependencyScanService scanner = new DependencyScanService(registry);

        scanner.scanRepository("rescan-api", repository.toString());
        Files.writeString(source, "SELECT id FROM new_invoices");
        var result = scanner.scanRepository("rescan-api", repository.toString());

        assertTrue(result.dependencies().stream().noneMatch(dependency ->
                dependency.tableName().equals("old_invoices")
        ));
        assertTrue(result.dependencies().stream().anyMatch(dependency ->
                dependency.tableName().equals("new_invoices")
        ));
    }
}
