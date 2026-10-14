package com.schemaguard.controller;

import com.schemaguard.diff.SchemaDiffEngine;
import com.schemaguard.diff.SchemaDiffResult;
import com.schemaguard.model.ApplicationImpact;
import com.schemaguard.model.DatabaseSchema;
import com.schemaguard.model.SchemaDiffRequest;
import com.schemaguard.model.SchemaImpactResponse;
import com.schemaguard.parser.MySqlSchemaParser;
import com.schemaguard.service.ApplicationImpactAnalysisService;
import com.schemaguard.service.DependencyGraphService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/schema")
public class SchemaDiffController {

    private static final int MAX_SQL_INPUT_CHARS = 500_000;

    private final MySqlSchemaParser parser = new MySqlSchemaParser();
    private final SchemaDiffEngine engine = new SchemaDiffEngine();
    private final ApplicationImpactAnalysisService impactAnalysisService = new ApplicationImpactAnalysisService();

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
        DatabaseSchema oldSchema = parser.parse(request.oldSql());
        DatabaseSchema newSchema = parser.parse(request.newSql());

        SchemaDiffResult diffResult = engine.compare(oldSchema, newSchema);
        DependencyGraphService graph = buildDependencyGraph(diffResult);
        List<ApplicationImpact> impacts = impactAnalysisService.analyzeImpact(diffResult, graph);
        List<String> migrationPlan = buildMigrationPlan(diffResult, impacts);

        return ResponseEntity.ok(new SchemaImpactResponse(diffResult, impacts, migrationPlan));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleParseError(
            IllegalArgumentException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(exception.getMessage());
    }

    private void validateRequest(SchemaDiffRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Both oldSql and newSql are required.");
        }

        if (request.oldSql() == null || request.oldSql().isBlank()) {
            throw new IllegalArgumentException("Both oldSql and newSql are required.");
        }

        if (request.newSql() == null || request.newSql().isBlank()) {
            throw new IllegalArgumentException("Both oldSql and newSql are required.");
        }

        if (request.oldSql().length() > MAX_SQL_INPUT_CHARS
                || request.newSql().length() > MAX_SQL_INPUT_CHARS) {
            throw new IllegalArgumentException(
                    "SQL payload exceeds the maximum supported size of " + MAX_SQL_INPUT_CHARS + " characters."
            );
        }
    }

    private DependencyGraphService buildDependencyGraph(SchemaDiffResult diffResult) {
        DependencyGraphService graph = new DependencyGraphService();

        graph.addNode("app:billing", "APPLICATION", "Billing");
        graph.addNode("app:inventory", "APPLICATION", "Inventory");
        graph.addNode("app:analytics", "APPLICATION", "Analytics");

        for (var change : diffResult.changes()) {
            String tableName = change.tableName();
            if (tableName == null || tableName.isBlank()) {
                continue;
            }

            graph.addNode("table:" + tableName, "TABLE", tableName);
            graph.addEdge("app:billing", "table:" + tableName, "READ", "dependency graph seed", "MEDIUM");
            graph.addEdge("app:inventory", "table:" + tableName, "WRITE", "dependency graph seed", "MEDIUM");

            if (change.objectType() != null && "COLUMN".equals(change.objectType())) {
                String columnName = change.objectName();
                if (columnName != null && !columnName.isBlank()) {
                    graph.addNode("column:" + tableName + "." + columnName, "COLUMN", columnName);
                    graph.addEdge("app:analytics", "column:" + tableName + "." + columnName, "READ", "dependency graph seed", "LOW");
                }
            }
        }

        return graph;
    }

    private List<String> buildMigrationPlan(SchemaDiffResult diffResult, List<ApplicationImpact> impacts) {
        List<String> plan = new ArrayList<>();
        if (diffResult == null || diffResult.changes().isEmpty()) {
            plan.add("No schema changes detected. Keep the current deployment and validate the environment is stable.");
            return plan;
        }

        plan.add("Back up the current database and run the migration against a staging environment before production rollout.");
        plan.add("Review all high-risk and medium-risk changes for destructive or data-altering operations before approval.");

        if (impacts != null && !impacts.isEmpty()) {
            plan.add("Validate impacted applications and report the affected table or column usage before moving to production.");
        }

        if (diffResult.changes().stream().anyMatch(change -> "COLUMN".equals(change.objectType()) && "MODIFIED".equals(change.changeType()))) {
            plan.add("Check nullable and type-conversion impacts in application code and test the transformed data values before commit.");
        }

        if (diffResult.changes().stream().anyMatch(change -> "TABLE".equals(change.objectType()) && "REMOVED".equals(change.changeType()))) {
            plan.add("Confirm the removed table has no remaining application dependencies or reporting queries before finalizing the migration.");
        }

        if (plan.size() < 4) {
            plan.add("Record the migration steps, validation queries, and post-deploy checks in the release notes or checklist.");
        }

        return plan;
    }
}
