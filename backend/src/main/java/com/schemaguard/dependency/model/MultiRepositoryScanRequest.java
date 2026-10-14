package com.schemaguard.dependency.model;

import java.util.List;

public record MultiRepositoryScanRequest(List<String> repositoryIds) {
    public MultiRepositoryScanRequest {
        repositoryIds = repositoryIds == null ? List.of() : List.copyOf(repositoryIds);
    }
}
