package com.schemaguard.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DependencyGraphServiceTest {

    @Test
    void constructsGraphAndFindsMultiHopPaths() {
        DependencyGraphService graph = new DependencyGraphService();

        graph.addNode("app:billing", "APPLICATION", "Billing");
        graph.addNode("table:users", "TABLE", "users");
        graph.addNode("column:users.email", "COLUMN", "users.email");
        graph.addNode("table:orders", "TABLE", "orders");

        graph.addEdge("app:billing", "table:users", "READ", "manual registry", "HIGH");
        graph.addEdge("table:users", "column:users.email", "READ", "SQL from repository", "MEDIUM");
        graph.addEdge("column:users.email", "table:orders", "TRANSITIVE", "join dependency", "LOW");

        assertEquals(4, graph.getNodes().size());
        assertEquals(3, graph.getEdges().size());
        assertFalse(graph.findPaths("app:billing", 2).isEmpty());
    }

    @Test
    void addsApplicationDependenciesAndScanEvidenceToTheGraph() {
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
        graph.addScanEvidence(
                "file:src/main/java/com/example/UserRepository.java",
                "orders",
                "SELECT * FROM orders WHERE customer_id = ?",
                "jdbc-template",
                "MEDIUM"
        );

        assertTrue(graph.getNodes().containsKey("app:billing"));
        assertTrue(graph.getNodes().containsKey("table:users"));
        assertTrue(graph.getNodes().containsKey("table:orders"));
        assertTrue(graph.getEdges().stream().anyMatch(edge -> edge.fromId().equals("app:billing") && edge.toId().equals("table:users")));
        assertTrue(graph.getEdges().stream().anyMatch(edge -> edge.fromId().equals("file:src/main/java/com/example/UserRepository.java") && edge.toId().equals("table:orders")));
    }

    @Test
    void preventsInfiniteLoopsDuringTraversal() {
        DependencyGraphService graph = new DependencyGraphService();

        graph.addNode("a", "APPLICATION", "A");
        graph.addNode("b", "TABLE", "B");

        graph.addEdge("a", "b", "READ", "manual", "MEDIUM");
        graph.addEdge("b", "a", "TRANSITIVE", "cycle", "LOW");

        assertDoesNotThrow(() -> graph.findPaths("a", 3));
    }
}
