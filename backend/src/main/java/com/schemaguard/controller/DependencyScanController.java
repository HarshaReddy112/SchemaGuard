package com.schemaguard.controller;

import com.schemaguard.model.DependencyScanRequest;
import com.schemaguard.model.DependencyScanResult;
import com.schemaguard.service.DependencyScanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dependencies")
public class DependencyScanController {

    private final DependencyScanService dependencyScanService = new DependencyScanService();

    @PostMapping("/scan")
    public ResponseEntity<DependencyScanResult> scanRepository(
            @RequestBody DependencyScanRequest request) {
        DependencyScanResult result = dependencyScanService.scanRepository(request.repositoryPath());
        return ResponseEntity.ok(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleValidationError(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.getMessage());
    }
}
