package com.schemaguard.model;

import java.util.List;

public record DependencyGraphPath(
        String fromId,
        String toId,
        List<String> path,
        List<String> edges,
        int depth
) {
}
