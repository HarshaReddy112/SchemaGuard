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
class SchemaDiffControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void diffEndpointReturnsRiskSummary() throws Exception {
        String request = """
                {
                  "oldSql": "CREATE TABLE users (id BIGINT NOT NULL, email VARCHAR(255), PRIMARY KEY (id));",
                  "newSql": "CREATE TABLE users (id BIGINT NOT NULL, PRIMARY KEY (id));"
                }
                """;

        mockMvc.perform(post("/api/v1/schema/diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallRisk").value("HIGH"))
                .andExpect(jsonPath("$.changes[0].objectType").value("COLUMN"))
                .andExpect(jsonPath("$.recommendations").isArray());
    }

    @Test
    void diffWithImpactEndpointIncludesImpactMetadata() throws Exception {
        String request = """
                {
                  "oldSql": "CREATE TABLE users (id BIGINT NOT NULL, name VARCHAR(100), PRIMARY KEY (id));",
                  "newSql": "CREATE TABLE users (id BIGINT NOT NULL, name VARCHAR(50), PRIMARY KEY (id));"
                }
                """;

        mockMvc.perform(post("/api/v1/schema/diff-with-impact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.diffResult.overallRisk").value("HIGH"))
                .andExpect(jsonPath("$.impacts").isArray())
                .andExpect(jsonPath("$.migrationPlan").isArray());
    }

    @Test
    void diffEndpointRejectsBlankSql() throws Exception {
        String request = """
                {
                  "oldSql": "",
                  "newSql": "CREATE TABLE users (id BIGINT NOT NULL, PRIMARY KEY (id));"
                }
                """;

        mockMvc.perform(post("/api/v1/schema/diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest());
    }
}
