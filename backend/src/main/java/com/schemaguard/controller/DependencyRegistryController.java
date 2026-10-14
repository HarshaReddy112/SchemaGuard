package com.schemaguard.controller;

import com.schemaguard.model.ApplicationDependency;
import com.schemaguard.model.ApplicationRegistrationRequest;
import com.schemaguard.model.DependencyRegistrationRequest;
import com.schemaguard.service.DependencyRegistryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dependencies")
public class DependencyRegistryController {

    private final DependencyRegistryService registry;

    public DependencyRegistryController(DependencyRegistryService registry) {
        this.registry = registry;
    }

    @PostMapping("/applications")
    public ResponseEntity<List<ApplicationRegistrationRequest>> registerApplication(
            @RequestBody ApplicationRegistrationRequest request) {
        registry.registerApplication(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(registry.listApplications());
    }

    @GetMapping("/applications")
    public ResponseEntity<List<ApplicationRegistrationRequest>> listApplications() {
        return ResponseEntity.ok(registry.listApplications());
    }

    @PostMapping("/entries")
    public ResponseEntity<List<ApplicationDependency>> registerDependency(
            @RequestBody DependencyRegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(registry.registerDependency(request));
    }

    @GetMapping("/entries")
    public ResponseEntity<List<ApplicationDependency>> listDependencies() {
        return ResponseEntity.ok(registry.listDependencies());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleValidationError(
            IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }
}
