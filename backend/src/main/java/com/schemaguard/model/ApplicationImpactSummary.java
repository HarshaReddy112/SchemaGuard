package com.schemaguard.model;

import java.time.Instant;
import java.util.List;

public record ApplicationImpactSummary(
        String applicationId,
        String applicationName,
        String databaseName,
        String status,
        String repository,
        int analyzedFileCount,
        boolean scanTruncated,
        Instant lastScannedAt,
        List<ApplicationImpact> impacts
) {
    public ApplicationImpactSummary {
        impacts = List.copyOf(impacts);
    }
}
