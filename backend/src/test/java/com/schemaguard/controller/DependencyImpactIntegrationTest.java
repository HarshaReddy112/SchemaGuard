package com.schemaguard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaguard.model.ApplicationRegistrationRequest;
import com.schemaguard.model.DependencyScanRequest;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DependencyImpactIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    private Path repository;

    @Test
    void reportsOnlyRegisteredApplicationsUsingTheChangedColumn() throws Exception {
        mockMvc.perform(post("/api/v1/dependencies/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": "orders-api-it",
                                  "name": "Orders API",
                                  "databaseName": "commerce"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/dependencies/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": "catalog-api-it",
                                  "name": "Catalog API",
                                  "databaseName": "commerce"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/dependencies/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": "orders-api-it",
                                  "tableName": "customers",
                                  "columnName": "email",
                                  "dependencyType": "READ",
                                  "source": "src/CustomerRepository.java",
                                  "evidence": "SELECT email FROM customers",
                                  "confidence": "HIGH",
                                  "apiEndpoint": "GET /customers (getCustomerEmail)"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/dependencies/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": "orders-api-it",
                                  "tableName": "customers",
                                  "columnName": "email",
                                  "dependencyType": "WRITE",
                                  "source": "src/CustomerController.java",
                                  "evidence": "INSERT INTO customers (email)",
                                  "confidence": "HIGH",
                                  "apiEndpoint": "POST /customers (createCustomer)"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/dependencies/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": "catalog-api-it",
                                  "tableName": "products",
                                  "columnName": "sku",
                                  "dependencyType": "READ",
                                  "source": "src/ProductRepository.java",
                                  "evidence": "SELECT sku FROM products",
                                  "confidence": "HIGH"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/schema/diff-with-impact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "databaseName": "commerce",
                                  "oldSql": "CREATE TABLE customers (id BIGINT NOT NULL, email VARCHAR(255), PRIMARY KEY (id));",
                                  "newSql": "CREATE TABLE customers (id BIGINT NOT NULL, PRIMARY KEY (id));",
                                  "migrationSql": "ALTER TABLE customers ALGORITHM=INSTANT, DROP COLUMN email;"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diffResult.overallRisk").value("HIGH"))
                .andExpect(jsonPath("$.riskScore.score").value(97))
                .andExpect(jsonPath("$.riskScore.riskLevel").value("CRITICAL"))
                .andExpect(jsonPath("$.riskScore.affectedApplicationCount").value(1))
                .andExpect(jsonPath("$.riskScore.affectedApiCount").value(2))
                .andExpect(jsonPath("$.riskScore.affectedComponentCount").value(2))
                .andExpect(jsonPath("$.riskScore.componentExposurePoints").value(4))
                .andExpect(jsonPath("$.riskScore.targetMetadataStatus")
                        .value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.riskScore.tableSizeRiskPoints").value(0))
                .andExpect(jsonPath("$.riskScore.ddlAlgorithmAssessment")
                        .value(org.hamcrest.Matchers.containsString(
                                "Explicit MySQL ALGORITHM")))
                .andExpect(jsonPath("$.impacts[0].applicationId").value("orders-api-it"))
                .andExpect(jsonPath("$.impacts[0].source").value("src/CustomerRepository.java"))
                .andExpect(jsonPath("$.impacts[0].confidence").value("HIGH"))
                .andExpect(jsonPath("$.impacts[0].apiEndpoint")
                        .value("GET /customers (getCustomerEmail)"))
                .andExpect(jsonPath("$.impacts[1].apiEndpoint")
                        .value("POST /customers (createCustomer)"));
    }

    @Test
    void rejectsDependenciesForUnknownApplications() throws Exception {
        mockMvc.perform(post("/api/v1/dependencies/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": "not-registered",
                                  "tableName": "customers",
                                  "dependencyType": "READ"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void distinguishesAffectedUnmatchedAndUnscannedApplications()
            throws Exception {
        registerApplication("users-service-coverage-it", "User Service");
        registerApplication("order-service-coverage-it", "Order Service");
        registerApplication(
                "notification-service-coverage-it", "Notification Service");

        mockMvc.perform(post("/api/v1/dependencies/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationId": "users-service-coverage-it",
                                  "tableName": "users",
                                  "columnName": "email",
                                  "dependencyType": "READ",
                                  "source": "src/UserRepository.java",
                                  "evidence": "SELECT email FROM users",
                                  "confidence": "HIGH"
                                }
                                """))
                .andExpect(status().isCreated());

        Files.writeString(repository.resolve("OrderRepository.java"),
                "SELECT id FROM orders");
        mockMvc.perform(post("/api/v1/dependencies/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DependencyScanRequest(
                                        "order-service-coverage-it",
                                        repository.toString()
                                ))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/schema/diff-with-impact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "databaseName": "commerce",
                                  "oldSql": "CREATE TABLE users (id BIGINT NOT NULL, email VARCHAR(255), PRIMARY KEY (id));",
                                  "newSql": "CREATE TABLE users (id BIGINT NOT NULL, PRIMARY KEY (id));"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.applicationSummaries[?(@.applicationId == 'users-service-coverage-it')].status")
                        .value(hasItem("POTENTIALLY_AFFECTED")))
                .andExpect(jsonPath(
                        "$.applicationSummaries[?(@.applicationId == 'order-service-coverage-it')].status")
                        .value(hasItem("NO_REFERENCE_FOUND")))
                .andExpect(jsonPath(
                        "$.applicationSummaries[?(@.applicationId == 'order-service-coverage-it')].analyzedFileCount")
                        .value(hasItem(1)))
                .andExpect(jsonPath(
                        "$.applicationSummaries[?(@.applicationId == 'notification-service-coverage-it')].status")
                        .value(hasItem("NOT_SCANNED")));
    }

    private void registerApplication(String applicationId, String name)
            throws Exception {
        mockMvc.perform(post("/api/v1/dependencies/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ApplicationRegistrationRequest(
                                        applicationId, name, "commerce"))))
                .andExpect(status().isCreated());
    }
}
