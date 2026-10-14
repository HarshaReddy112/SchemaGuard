package com.schemaguard.controller;

import com.schemaguard.model.ApplicationDependencyScanResult;
import com.schemaguard.model.ApplicationRegistrationRequest;
import com.schemaguard.model.DependencyScanRequest;
import com.schemaguard.model.DependencyScanResult;
import com.schemaguard.model.MultiRepositoryDependencyScanResult;
import com.schemaguard.model.MultiRepositoryScanRequest;
import com.schemaguard.model.RepositoryScanTarget;
import com.schemaguard.service.DependencyRegistryService;
import com.schemaguard.service.DependencyScanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/dependencies")
public class DependencyScanController {

    private static final int MAX_REPOSITORIES_PER_SCAN = 20;

    private final DependencyScanService dependencyScanService;
    private final DependencyRegistryService registryService;

    public DependencyScanController(
            DependencyScanService dependencyScanService,
            DependencyRegistryService registryService) {
        this.dependencyScanService = dependencyScanService;
        this.registryService = registryService;
    }

    @PostMapping("/scan")
    public ResponseEntity<DependencyScanResult> scanRepository(
            @RequestBody DependencyScanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Scan request is required.");
        }
        DependencyScanResult result = dependencyScanService.scanRepository(request.repositoryPath());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/scan/multiple")
    public ResponseEntity<MultiRepositoryDependencyScanResult> scanMultipleRepositories(
            @RequestBody MultiRepositoryScanRequest request) {
        if (request == null || request.repositories().isEmpty()) {
            throw new IllegalArgumentException("At least one repository is required.");
        }
        if (request.repositories().size() > MAX_REPOSITORIES_PER_SCAN) {
            throw new IllegalArgumentException(
                    "A single scan may include at most " + MAX_REPOSITORIES_PER_SCAN + " repositories."
            );
        }

        Map<String, String> registeredApplications = registryService.getApplicationNames();
        Set<String> applicationIds = new HashSet<>();
        for (RepositoryScanTarget target : request.repositories()) {
            if (target == null || target.applicationId() == null || target.applicationId().isBlank()) {
                throw new IllegalArgumentException("Every repository target must include applicationId.");
            }
            if (target.repositoryPath() == null || target.repositoryPath().isBlank()) {
                throw new IllegalArgumentException("Every repository target must include repositoryPath.");
            }
            if (!registeredApplications.containsKey(target.applicationId())) {
                throw new IllegalArgumentException("Unknown applicationId: " + target.applicationId());
            }
            if (!applicationIds.add(target.applicationId())) {
                throw new IllegalArgumentException(
                        "Each applicationId may appear only once in a multi-repository scan: " + target.applicationId()
                );
            }
        }

        List<ApplicationDependencyScanResult> results = new ArrayList<>();
        for (RepositoryScanTarget target : request.repositories()) {
            DependencyScanResult scanResult = dependencyScanService.scanRepository(target.repositoryPath());
            results.add(new ApplicationDependencyScanResult(
                    target.applicationId(),
                    registeredApplications.get(target.applicationId()),
                    scanResult
            ));
        }

        for (ApplicationDependencyScanResult result : results) {
            registryService.replaceScannedDependencies(
                    result.applicationId(),
                    result.scanResult().evidence()
            );
        }

        List<String> warnings = new ArrayList<>();
        for (ApplicationDependencyScanResult result : results) {
            if (result.scanResult().truncated()) {
                warnings.add("Scan for " + result.applicationName() + " reached a configured scan limit; results may be incomplete.");
            }
        }
        warnings.add("Static discovery is heuristic. A missing reference does not prove that an application is unaffected.");

        return ResponseEntity.ok(new MultiRepositoryDependencyScanResult(
                results.size(),
                results.stream().mapToInt(result -> result.scanResult().scannedFiles()).sum(),
                results.stream().mapToLong(result -> result.scanResult().scannedBytes()).sum(),
                results.stream().mapToInt(result -> result.scanResult().evidence().size()).sum(),
                results,
                warnings
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<String> handleValidationError(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.getMessage());
    }
}
