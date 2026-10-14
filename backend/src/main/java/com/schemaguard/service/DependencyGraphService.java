package com.schemaguard.service;

import com.schemaguard.model.DependencyGraphEdge;
import com.schemaguard.model.DependencyGraphNode;
import com.schemaguard.model.DependencyGraphPath;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

public class DependencyGraphService {

    private final Map<String, DependencyGraphNode> nodes = new LinkedHashMap<>();
    private final Set<DependencyGraphEdge> edges = new LinkedHashSet<>();

    public void addNode(String id, String type, String name) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Node id is required.");
        }
        nodes.put(id, new DependencyGraphNode(id, type, name, null));
    }

    public void addApplicationDependency(
            String applicationId,
            String applicationName,
            String tableName,
            String columnName,
            String dependencyType,
            String evidence,
            String confidence) {
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("Application id is required.");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name is required.");
        }
        if (dependencyType == null || dependencyType.isBlank()) {
            throw new IllegalArgumentException("Dependency type is required.");
        }

        addNode(applicationId, "APPLICATION", applicationName == null || applicationName.isBlank() ? applicationId : applicationName);
        addNode(tableNodeId(tableName), "TABLE", tableName);
        addEdge(applicationId, tableNodeId(tableName), dependencyType, evidence, confidence);

        if (columnName != null && !columnName.isBlank()) {
            addNode(columnNodeId(tableName, columnName), "COLUMN", columnName);
            addEdge(applicationId, columnNodeId(tableName, columnName), dependencyType, evidence, confidence);
        }
    }

    public void addScanEvidence(String sourceLocation, String tableName, String snippet, String detector, String confidence) {
        if (sourceLocation == null || sourceLocation.isBlank()) {
            throw new IllegalArgumentException("Source location is required.");
        }
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name is required.");
        }

        addNode(sourceLocation, "SOURCE", sourceLocation);
        addNode(tableNodeId(tableName), "TABLE", tableName);
        addEdge(sourceLocation, tableNodeId(tableName), "CONTAINS", detector + ": " + snippet, confidence);
    }

    public void addEdge(String fromId, String toId, String type, String evidence, String confidence) {
        if (!nodes.containsKey(fromId) || !nodes.containsKey(toId)) {
            throw new IllegalArgumentException("Graph edge references unknown node IDs.");
        }

        edges.add(new DependencyGraphEdge(fromId, toId, type, evidence, confidence));
    }

    public List<DependencyGraphPath> findPaths(String startNodeId, int maxDepth) {
        if (!nodes.containsKey(startNodeId)) {
            throw new IllegalArgumentException("Unknown start node: " + startNodeId);
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be >= 0.");
        }

        List<DependencyGraphPath> results = new ArrayList<>();
        Queue<State> queue = new ArrayDeque<>();
        queue.add(new State(startNodeId, new ArrayList<>(Collections.singletonList(startNodeId)), 0));
        Set<String> visited = new LinkedHashSet<>();
        visited.add(startNodeId);

        while (!queue.isEmpty()) {
            State state = queue.poll();
            if (state.depth >= maxDepth) {
                continue;
            }

            List<String> nextNodes = new ArrayList<>();
            for (DependencyGraphEdge edge : edges) {
                if (!Objects.equals(edge.fromId(), state.nodeId)) {
                    continue;
                }
                if (!visited.add(edge.toId())) {
                    continue;
                }
                nextNodes.add(edge.toId());
                List<String> path = new ArrayList<>(state.path);
                path.add(edge.toId());
                results.add(new DependencyGraphPath(
                        startNodeId,
                        edge.toId(),
                        path,
                        List.of(edge.type()),
                        state.depth + 1
                ));
                queue.add(new State(edge.toId(), path, state.depth + 1));
            }
        }

        return results;
    }

    public List<String> findDependentApplications(String tableName, String columnName) {
        if (tableName == null || tableName.isBlank()) {
            return List.of();
        }

        String targetId = columnName == null || columnName.isBlank()
                ? tableNodeId(tableName)
                : columnNodeId(tableName, columnName);

        List<String> dependentApplications = new ArrayList<>();
        for (DependencyGraphEdge edge : edges) {
            if (!Objects.equals(edge.toId(), targetId)) {
                continue;
            }
            DependencyGraphNode node = nodes.get(edge.fromId());
            if (node != null && "APPLICATION".equals(node.type())) {
                dependentApplications.add(edge.fromId());
            }
        }

        return dependentApplications.stream().distinct().toList();
    }

    public DependencyGraphEdge findEdgeFromApplicationToObject(String applicationId, String tableName, String columnName) {
        if (applicationId == null || applicationId.isBlank()) {
            return null;
        }
        if (tableName == null || tableName.isBlank()) {
            return null;
        }

        String targetId = (columnName == null || columnName.isBlank())
                ? tableNodeId(tableName)
                : columnNodeId(tableName, columnName);

        return edges.stream()
                .filter(edge -> Objects.equals(edge.fromId(), applicationId))
                .filter(edge -> Objects.equals(edge.toId(), targetId))
                .findFirst()
                .orElse(null);
    }

    public Map<String, DependencyGraphNode> getNodes() {
        return new LinkedHashMap<>(nodes);
    }

    public Set<DependencyGraphEdge> getEdges() {
        return new LinkedHashSet<>(edges);
    }

    private String tableNodeId(String tableName) {
        return "table:" + tableName;
    }

    private String columnNodeId(String tableName, String columnName) {
        return "column:" + tableName + "." + columnName;
    }

    private record State(String nodeId, List<String> path, int depth) {
    }
}
