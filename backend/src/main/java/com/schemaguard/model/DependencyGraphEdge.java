package com.schemaguard.model;

public record DependencyGraphEdge(
        String fromId,
        String toId,
        String type,
        String evidence,
        String confidence
) {
}
