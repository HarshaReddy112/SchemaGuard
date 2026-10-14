package com.schemaguard.model;

public record DependencyGraphNode(
        String id,
        String type,
        String name,
        String metadata
) {
}
