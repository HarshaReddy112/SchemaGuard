package com.schemaguard.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DependencyImpactIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

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
                                  "confidence": "HIGH"
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
                                  "newSql": "CREATE TABLE customers (id BIGINT NOT NULL, PRIMARY KEY (id));"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diffResult.overallRisk").value("HIGH"))
                .andExpect(jsonPath("$.riskScore.score").value(93))
                .andExpect(jsonPath("$.riskScore.affectedApplicationCount").value(1))
                .andExpect(jsonPath("$.impacts[0].applicationId").value("orders-api-it"))
                .andExpect(jsonPath("$.impacts[0].source").value("src/CustomerRepository.java"))
                .andExpect(jsonPath("$.impacts[0].confidence").value("HIGH"));
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
}
