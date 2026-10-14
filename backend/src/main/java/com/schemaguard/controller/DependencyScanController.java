package com.schemaguard.controller;

import com.schemaguard.model.DependencyScanRequest;
import com.schemaguard.model.DependencyScanResult;
import com.schemaguard.service.DependencyScanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dependencies")
public class DependencyScanController {

    private final DependencyScanService scanner;

    public DependencyScanController(DependencyScanService scanner) {
        this.scanner = scanner;
    }

    @PostMapping("/scan")
    public ResponseEntity<DependencyScanResult> scanRepository(
            @RequestBody DependencyScanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "applicationId and repositoryPath are required."
            );
        }
        return ResponseEntity.ok(scanner.scanRepository(
                request.applicationId(),
                request.repositoryPath()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleValidationError(
            IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }
}
