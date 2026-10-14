package com.schemaguard.dependency.service;

import com.schemaguard.dependency.model.RepositoryRegistration;
import com.schemaguard.dependency.model.RepositoryRegistrationRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RepositoryRegistryService {

    private final Map<String, RepositoryRegistration> repositories = new ConcurrentHashMap<>();
    private final String configuredAllowedRoots;

    public RepositoryRegistryService(
            @Value("${schemaguard.scan.allowed-roots:}") String configuredAllowedRoots) {
        this.configuredAllowedRoots = configuredAllowedRoots;
    }

    public RepositoryRegistration register(RepositoryRegistrationRequest request) {
        if (request == null || isBlank(request.repositoryName())) {
            throw new IllegalArgumentException("repositoryName is required.");
        }

        if (isBlank(request.localPath())) {
            throw new IllegalArgumentException(
                    "localPath is required. Check out each application repository locally before registering it."
            );
        }

        Path repositoryPath = resolveAndValidatePath(request.localPath());
        validateRepositoryUrl(request.repositoryUrl());

        String repositoryId = isBlank(request.repositoryId())
                ? UUID.randomUUID().toString()
                : request.repositoryId().trim();

        RepositoryRegistration registration = new RepositoryRegistration(
                repositoryId,
                request.repositoryName().trim(),
                cleanOptional(request.repositoryUrl()),
                repositoryPath.toString(),
                cleanOptional(request.primaryLanguage()),
                Instant.now()
        );

        RepositoryRegistration existing = repositories.putIfAbsent(repositoryId, registration);
        if (existing != null) {
            throw new IllegalArgumentException("A repository with id '" + repositoryId + "' is already registered.");
        }

        return registration;
    }

    public List<RepositoryRegistration> list() {
        return repositories.values().stream()
                .sorted(Comparator.comparing(RepositoryRegistration::repositoryName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public RepositoryRegistration getRequired(String repositoryId) {
        if (isBlank(repositoryId)) {
            throw new IllegalArgumentException("repositoryId is required.");
        }

        RepositoryRegistration registration = repositories.get(repositoryId.trim());
        if (registration == null) {
            throw new IllegalArgumentException("No repository is registered with id '" + repositoryId + "'.");
        }

        Path checkedPath = resolveAndValidatePath(registration.localPath());
        return new RepositoryRegistration(
                registration.repositoryId(),
                registration.repositoryName(),
                registration.repositoryUrl(),
                checkedPath.toString(),
                registration.primaryLanguage(),
                registration.registeredAt()
        );
    }

    public List<RepositoryRegistration> resolveForScan(List<String> repositoryIds) {
        if (repositoryIds == null || repositoryIds.isEmpty()) {
            List<RepositoryRegistration> all = list();
            if (all.isEmpty()) {
                throw new IllegalArgumentException("Register at least one repository before scanning.");
            }
            return all.stream().map(item -> getRequired(item.repositoryId())).toList();
        }

        return repositoryIds.stream()
                .map(this::getRequired)
                .distinct()
                .toList();
    }

    private Path resolveAndValidatePath(String rawPath) {
        try {
            Path repositoryPath = Path.of(rawPath).toRealPath();
            if (!Files.isDirectory(repositoryPath)) {
                throw new IllegalArgumentException("Repository path must be a directory: " + rawPath);
            }

            boolean allowed = allowedRoots().stream().anyMatch(repositoryPath::startsWith);
            if (!allowed) {
                throw new IllegalArgumentException(
                        "Repository path is outside the configured scan roots. Configure schemaguard.scan.allowed-roots for trusted repository directories."
                );
            }

            return repositoryPath;
        } catch (IOException | java.nio.file.InvalidPathException exception) {
            throw new IllegalArgumentException("Repository path does not exist or cannot be accessed: " + rawPath);
        }
    }

    private List<Path> allowedRoots() {
        List<Path> roots = new ArrayList<>();

        if (!isBlank(configuredAllowedRoots)) {
            for (String value : configuredAllowedRoots.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!value.isBlank()) {
                    addRealRoot(roots, value.trim());
                }
            }
        } else {
            String workingDirectory = System.getProperty("user.dir");
            addRealRoot(roots, workingDirectory);

            Path current = Path.of(workingDirectory).toAbsolutePath().normalize();
            Path fileName = current.getFileName();
            if (fileName != null && "backend".equalsIgnoreCase(fileName.toString()) && current.getParent() != null) {
                addRealRoot(roots, current.getParent().toString());
            }
        }

        return roots;
    }

    private void addRealRoot(List<Path> roots, String rawRoot) {
        try {
            Path root = Path.of(rawRoot).toRealPath();
            if (Files.isDirectory(root) && !roots.contains(root)) {
                roots.add(root);
            }
        } catch (IOException | java.nio.file.InvalidPathException ignored) {
        }
    }

    private void validateRepositoryUrl(String repositoryUrl) {
        if (isBlank(repositoryUrl)) {
            return;
        }

        try {
            URI uri = URI.create(repositoryUrl.trim());
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw new IllegalArgumentException("repositoryUrl must be an absolute HTTP or HTTPS URL.");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("repositoryUrl must be a valid absolute HTTP or HTTPS URL.");
        }
    }

    private static String cleanOptional(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
