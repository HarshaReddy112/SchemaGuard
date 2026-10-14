package com.schemaguard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaguard.model.ApplicationRegistrationRequest;
import com.schemaguard.model.MultiRepositoryScanRequest;
import com.schemaguard.model.RepositoryScanTarget;
import com.schemaguard.model.SchemaDiffRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MultiRepositoryDependencyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path temporaryDirectory;

    @Test
    void scansMultipleRegisteredRepositoriesAndUsesEvidenceForMigrationImpact() throws Exception {
        String userServiceId = "user-service-" + UUID.randomUUID();
        String orderServiceId = "order-service-" + UUID.randomUUID();

        Path userRepository = Files.createDirectories(temporaryDirectory.resolve("user-service"));
        Path orderRepository = Files.createDirectories(temporaryDirectory.resolve("order-service"));

        Files.writeString(userRepository.resolve("UserRepository.java"),
                "class UserRepository { String sql = \"SELECT id, email FROM users WHERE id = ?\"; }");
        Files.writeString(orderRepository.resolve("OrderRepository.sql"),
                "SELECT id, total FROM orders WHERE id = ?;");

        registerApplication(userServiceId, "User Service");
        registerApplication(orderServiceId, "Order Service");

        MultiRepositoryScanRequest scanRequest = new MultiRepositoryScanRequest(List.of(
                new RepositoryScanTarget(userServiceId, userRepository.toString()),
                new RepositoryScanTarget(orderServiceId, orderRepository.toString())
        ));

        mockMvc.perform(post("/api/v1/dependencies/scan/multiple")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(scanRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repositoriesScanned").value(2))
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.results[*].applicationId").value(hasItem(userServiceId)))
                .andExpect(jsonPath("$.results[*].applicationId").value(hasItem(orderServiceId)));

        mockMvc.perform(get("/api/v1/dependencies/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.applicationId == '" + userServiceId
                        + "' && @.tableName == 'users' && @.columnName == 'email')]",
                        hasSize(1)));

        SchemaDiffRequest diffRequest = new SchemaDiffRequest(
                "CREATE TABLE users (id BIGINT NOT NULL, email VARCHAR(255), PRIMARY KEY (id));",
                "CREATE TABLE users (id BIGINT NOT NULL, email VARCHAR(512), PRIMARY KEY (id));"
        );

        mockMvc.perform(post("/api/v1/schema/diff-with-impact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(diffRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impacts[*].applicationId").value(hasItem(userServiceId)));
    }

    private void registerApplication(String applicationId, String name) throws Exception {
        ApplicationRegistrationRequest request = new ApplicationRegistrationRequest(
                applicationId,
                name,
                "local-checkout",
                "schemaguard_test",
                "Integration test application"
        );

        mockMvc.perform(post("/api/v1/dependencies/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }
}
