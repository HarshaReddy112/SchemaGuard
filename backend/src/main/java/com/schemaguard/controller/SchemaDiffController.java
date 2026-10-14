package com.schemaguard.controller;

import com.schemaguard.diff.SchemaDiffEngine;
import com.schemaguard.diff.SchemaDiffResult;
import com.schemaguard.model.DatabaseSchema;
import com.schemaguard.model.SchemaDiffRequest;
import com.schemaguard.model.SchemaImpactResponse;
import com.schemaguard.parser.MySqlSchemaParser;
import com.schemaguard.service.MigrationImpactAnalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schema")
public class SchemaDiffController {

    private static final int MAX_SQL_INPUT_CHARS = 500_000;

    private final MySqlSchemaParser parser = new MySqlSchemaParser();
    private final SchemaDiffEngine engine = new SchemaDiffEngine();
    private final MigrationImpactAnalysisService impactAnalysisService;

    public SchemaDiffController(
            MigrationImpactAnalysisService impactAnalysisService) {
        this.impactAnalysisService = impactAnalysisService;
    }

    @PostMapping("/diff")
    public ResponseEntity<SchemaDiffResult> compareSchemas(
            @RequestBody SchemaDiffRequest request) {

        validateRequest(request);

        DatabaseSchema oldSchema = parser.parse(request.oldSql());
        DatabaseSchema newSchema = parser.parse(request.newSql());

        return ResponseEntity.ok(engine.compare(oldSchema, newSchema));
    }

    @PostMapping("/diff-with-impact")
    public ResponseEntity<SchemaImpactResponse> compareSchemasWithImpact(
            @RequestBody SchemaDiffRequest request) {
        validateRequest(request);
            if (request.databaseName() == null || request.databaseName().isBlank()) {
                throw new IllegalArgumentException(
                        "databaseName is required for dependency impact analysis."
                );
            }
        DatabaseSchema oldSchema = parser.parse(request.oldSql());
        DatabaseSchema newSchema = parser.parse(request.newSql());
        SchemaDiffResult diff = engine.compare(oldSchema, newSchema);
        var impacts = impactAnalysisService.analyze(diff, request.databaseName());
        var score = impactAnalysisService.calculateRiskScore(diff, impacts);
        var plan = impactAnalysisService.buildMigrationPlan(diff, impacts);
        return ResponseEntity.ok(
                new SchemaImpactResponse(diff, impacts, score, plan)
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleParseError(
            IllegalArgumentException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(exception.getMessage());
    }

    private void validateRequest(SchemaDiffRequest request) {
        if (request == null
                || request.oldSql() == null
                || request.oldSql().isBlank()
                || request.newSql() == null
                || request.newSql().isBlank()) {
            throw new IllegalArgumentException(
                    "Both oldSql and newSql are required."
            );
        }
        if (request.oldSql().length() > MAX_SQL_INPUT_CHARS
                || request.newSql().length() > MAX_SQL_INPUT_CHARS) {
            throw new IllegalArgumentException(
                    "Each SQL input must be no larger than "
                            + MAX_SQL_INPUT_CHARS + " characters."
            );
        }
    }
}
