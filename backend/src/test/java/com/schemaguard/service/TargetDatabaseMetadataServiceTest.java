package com.schemaguard.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetDatabaseMetadataServiceTest {

    @Test
    void reportsWhenTargetMetadataIsNotConfigured() {
        TargetDatabaseMetadataService service =
                new TargetDatabaseMetadataService("", "", "");

        var snapshot = service.read("commerce", List.of("users"));

        assertEquals("NOT_CONFIGURED", snapshot.status());
        assertTrue(snapshot.tableSizesBytes().isEmpty());
    }

    @Test
    void increasesRiskByLargestAffectedTableSize() {
        long megabyte = 1024L * 1024;
        long gigabyte = 1024L * megabyte;

        assertEquals(0,
                TargetDatabaseMetadataService.tableSizeRiskPoints(99 * megabyte));
        assertEquals(2,
                TargetDatabaseMetadataService.tableSizeRiskPoints(100 * megabyte));
        assertEquals(4,
                TargetDatabaseMetadataService.tableSizeRiskPoints(gigabyte));
        assertEquals(7,
                TargetDatabaseMetadataService.tableSizeRiskPoints(10 * gigabyte));
        assertEquals(10,
                TargetDatabaseMetadataService.tableSizeRiskPoints(100 * gigabyte));
    }

    @Test
    void scoresOnlyExplicitMySqlDdlAlgorithmHints() {
        var copy = TargetDatabaseMetadataService.assessDdlAlgorithm(
                "ALTER TABLE users ALGORITHM=COPY, DROP COLUMN email;");
        var inplace = TargetDatabaseMetadataService.assessDdlAlgorithm(
                "ALTER TABLE users ALGORITHM=INPLACE, ADD COLUMN note TEXT;");
        var instant = TargetDatabaseMetadataService.assessDdlAlgorithm(
                "ALTER TABLE users ALGORITHM=INSTANT, ADD COLUMN note TEXT;");
        var unspecified = TargetDatabaseMetadataService.assessDdlAlgorithm(
                "ALTER TABLE users DROP COLUMN email;");

        assertEquals(10, copy.riskPoints());
        assertEquals(4, inplace.riskPoints());
        assertEquals(0, instant.riskPoints());
        assertEquals("NOT_SPECIFIED", unspecified.value());
        assertEquals(0, unspecified.riskPoints());
    }
}
