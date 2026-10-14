package com.schemaguard.dependency.controller;

import com.schemaguard.dependency.model.MultiRepositoryScanRequest;
import com.schemaguard.dependency.model.MultiRepositoryScanResponse;
import com.schemaguard.dependency.model.RepositoryRegistration;
import com.schemaguard.dependency.model.RepositoryRegistrationRequest;
import com.schemaguard.dependency.model.RepositoryScanResult;
import com.schemaguard.dependency.service.RepositoryDependencyScannerService;
import com.schemaguard.dependency.service.RepositoryRegistryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/dependencies")
public class DependencyController {

    private final RepositoryRegistryService registryService;
    private final RepositoryDependencyScannerService scannerService;

    public DependencyController(
            RepositoryRegistryService registryService,
            RepositoryDependencyScannerService scannerService) {
        this.registryService = registryService;
        this.scannerService = scannerService;
    }

    @PostMapping("/applications")
    public ResponseEntity<RepositoryRegistration> registerRepository(
            @RequestBody RepositoryRegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registryService.register(request));
    }

    @GetMapping("/applications")
    public ResponseEntity<List<RepositoryRegistration>> listRepositories() {
        return ResponseEntity.ok(registryService.list());
    }

    @PostMapping("/scan")
    public ResponseEntity<MultiRepositoryScanResponse> scanRepositories(
            @RequestBody(required = false) MultiRepositoryScanRequest request) {
        List<String> repositoryIds = request == null ? List.of() : request.repositoryIds();
        List<RepositoryScanResult> results = scannerService.scanRepositories(repositoryIds);
        List<String> warnings = new ArrayList<>();
        results.forEach(result -> warnings.addAll(result.warnings()));

        return ResponseEntity.ok(new MultiRepositoryScanResponse(
                results.size(),
                results.stream().mapToInt(RepositoryScanResult::filesScanned).sum(),
                results.stream().mapToInt(RepositoryScanResult::referencesFound).sum(),
                results,
                warnings.stream().distinct().toList()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleValidationError(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(exception.getMessage());
    }
}
