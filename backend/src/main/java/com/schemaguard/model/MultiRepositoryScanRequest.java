package com.schemaguard.model;

import java.util.List;

public record MultiRepositoryScanRequest(List<RepositoryScanTarget> repositories) {
    public MultiRepositoryScanRequest {
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
    }
}
