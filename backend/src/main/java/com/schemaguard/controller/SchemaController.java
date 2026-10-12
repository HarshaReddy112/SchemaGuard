package com.schemaguard.controller;

import com.schemaguard.model.DatabaseSchema;
import com.schemaguard.model.SchemaParseRequest;
import com.schemaguard.parser.MySqlSchemaParser;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.GetMapping;

@RestController
@RequestMapping("/api/v1/schema")
public class SchemaController {

    private final MySqlSchemaParser schemaParser;

    public SchemaController() {
        this.schemaParser = new MySqlSchemaParser();
    }

    @PostMapping("/parse")
    public ResponseEntity<DatabaseSchema> parseSchema(
            @RequestBody SchemaParseRequest request) {

        DatabaseSchema schema = schemaParser.parse(request.sql());
        return ResponseEntity.ok(schema);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleParseError(
            IllegalArgumentException exception) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(exception.getMessage());
    }

           
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(
            Map.of(
                    "status", "UP",
                    "service", "SchemaGuard",
                    "message", "Schema parser API is running"
            )
        );
    }

}
