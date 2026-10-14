package com.schemaguard.controller;

import com.schemaguard.model.ApplicationDependency;
import com.schemaguard.model.ApplicationRegistrationRequest;
import com.schemaguard.model.DependencyRegistrationRequest;
import com.schemaguard.service.DependencyRegistryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/dependencies")
public class DependencyRegistryController {

    private final DependencyRegistryService registryService;

    public DependencyRegistryController(DependencyRegistryService registryService) {
        this.registryService = registryService;
    }

    @PostMapping("/applications")
    public ResponseEntity<Map<String, String>> registerApplication(
            @RequestBody ApplicationRegistrationRequest request) {
        registryService.registerApplication(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(registryService.getApplicationNames());
    }

    @GetMapping("/applications")
    public ResponseEntity<Map<String, String>> listApplications() {
        return ResponseEntity.ok(registryService.getApplicationNames());
    }

    @PostMapping("/entries")
    public ResponseEntity<List<ApplicationDependency>> registerDependency(
            @RequestBody DependencyRegistrationRequest request) {
        registryService.registerDependency(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(registryService.listDependencies());
    }

    @GetMapping("/entries")
    public ResponseEntity<List<ApplicationDependency>> listDependencies() {
        return ResponseEntity.ok(registryService.listDependencies());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleValidationError(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.getMessage());
    }
}
