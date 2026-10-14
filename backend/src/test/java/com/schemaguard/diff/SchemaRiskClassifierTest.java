package com.schemaguard.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemaRiskClassifierTest {

    @Test
    void ranksTypeWideningAsLowRisk() {
        assertEquals("LOW",
                SchemaRiskClassifier.typeRisk("VARCHAR(50)", "VARCHAR(100)"));
    }

    @Test
    void ranksTypeNarrowingAsHighRisk() {
        assertEquals("HIGH",
                SchemaRiskClassifier.typeRisk("VARCHAR(255)", "VARCHAR(50)"));
    }

    @Test
    void ranksTurningColumnNotNullAsHighRisk() {
        assertEquals("HIGH",
                SchemaRiskClassifier.nullabilityRisk(true, false));
    }

    @Test
    void ranksAddedIndexAsLowRisk() {
        assertEquals("LOW", SchemaRiskClassifier.indexAddedRisk());
    }
}
