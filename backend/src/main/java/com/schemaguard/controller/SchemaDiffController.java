package com.schemaguard.controller;

import com.schemaguard.diff.SchemaDiffEngine;
import com.schemaguard.diff.SchemaDiffResult;
import com.schemaguard.model.DatabaseSchema;
import com.schemaguard.model.SchemaDiffRequest;
import com.schemaguard.parser.MySqlSchemaParser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/schema")
public class SchemaDiffController {

    private final MySqlSchemaParser parser = new MySqlSchemaParser();
    private final SchemaDiffEngine engine = new SchemaDiffEngine();

    @PostMapping("/diff")
    public ResponseEntity<SchemaDiffResult> compareSchemas(
            @RequestBody SchemaDiffRequest request) {

        if (request == null
                || request.oldSql() == null
                || request.oldSql().isBlank()
                || request.newSql() == null
                || request.newSql().isBlank()) {
            throw new IllegalArgumentException(
                    "Both oldSql and newSql are required."
            );
        }

        DatabaseSchema oldSchema = parser.parse(request.oldSql());
        DatabaseSchema newSchema = parser.parse(request.newSql());

        return ResponseEntity.ok(engine.compare(oldSchema, newSchema));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleParseError(
            IllegalArgumentException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(exception.getMessage());
    }
}
