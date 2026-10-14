package com.schemaguard.model;

import java.time.Instant;

public record ApplicationScanCoverage(
        String applicationId,
        String repository,
        int analyzedFileCount,
        boolean truncated,
        Instant scannedAt
) {
}
