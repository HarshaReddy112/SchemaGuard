package com.schemaguard.service;

import com.schemaguard.diff.SchemaChange;
import com.schemaguard.diff.SchemaDiffResult;
import com.schemaguard.model.ApplicationImpact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationImpactAnalysisServiceTest {

    @Test
    void matchesSchemaChangesToDependentApplications() {
        DependencyGraphService graph = new DependencyGraphService();
        graph.addApplicationDependency(
                "app:billing",
                "Billing",
                "users",
                "email",
                "READ",
                "manual registry entry",
                "HIGH"
        );

        SchemaDiffResult diff = new SchemaDiffResult(List.of(
                new SchemaChange(
                        "COLUMN",
                        "MODIFIED",
                        "users",
                        "email",
                        "VARCHAR(255)",
                        "VARCHAR(512)",
                        "MEDIUM",
                        "Column type changed"
                )
        ));

        List<ApplicationImpact> impacts = new ApplicationImpactAnalysisService().analyzeImpact(diff, graph);

        assertFalse(impacts.isEmpty());
        assertTrue(impacts.stream().anyMatch(impact -> "app:billing".equals(impact.applicationId())));
        assertEquals("users", impacts.getFirst().changedTable());
    }

    @Test
    void flagsChangesWithoutDirectDependencyEvidenceForManualReview() {
        DependencyGraphService graph = new DependencyGraphService();
        graph.addNode("app:billing", "APPLICATION", "Billing");
        graph.addNode("table:users", "TABLE", "users");
        graph.addEdge("app:billing", "table:users", "READ", "manual registry", "HIGH");

        SchemaDiffResult diff = new SchemaDiffResult(List.of(
                new SchemaChange(
                        "TABLE",
                        "REMOVED",
                        "orders",
                        "orders",
                        "TABLE",
                        null,
                        "HIGH",
                        "Table removed"
                )
        ));

        List<ApplicationImpact> impacts = new ApplicationImpactAnalysisService().analyzeImpact(diff, graph);

        assertFalse(impacts.isEmpty());
        assertTrue(impacts.stream().anyMatch(impact -> impact.applicationId() == null));
        assertTrue(impacts.stream().anyMatch(impact -> impact.reason().contains("manual")));
    }
}
