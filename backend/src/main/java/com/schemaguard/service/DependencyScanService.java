package com.schemaguard.service;

import com.schemaguard.model.ApplicationDependency;
import com.schemaguard.model.DependencyRegistrationRequest;
import com.schemaguard.model.DependencyScanResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class DependencyScanService {

    private static final int MAX_FILES = 500;
    private static final long MAX_FILE_SIZE_BYTES = 1_048_576;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".java", ".kt", ".py", ".sql", ".xml", ".properties",
            ".js", ".ts", ".jsx", ".tsx"
    );
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "build", "dist", "out"
    );
    private static final Pattern SQL_REFERENCE = Pattern.compile(
            "(?is)\\b(FROM|JOIN|UPDATE|INTO|DELETE\\s+FROM)\\s+"
                    + "[`\"']?([A-Za-z_][A-Za-z0-9_$]*)[`\"']?"
                    + "(?![A-Za-z0-9_$])"
                    + "(?!\\s+import\\b)"
    );
    private static final Pattern TABLE_ANNOTATION = Pattern.compile(
            "(?is)@Table\\s*\\([^)]*?"
                    + "(?:name|tableName)\\s*=\\s*[\"']([A-Za-z_][A-Za-z0-9_$]*)[\"']"
    );
    private static final Pattern SCHEMA_DDL = Pattern.compile(
            "(?is)\\bCREATE\\s+TABLE\\b"
    );
    private static final Pattern PYTHON_ROUTE = Pattern.compile(
            "^\\s*@\\w+\\.(route|get|post|put|patch|delete)\\s*"
                    + "\\(\\s*(['\"])(.*?)\\2(.*)$"
    );
    private static final Pattern PYTHON_METHODS = Pattern.compile(
            "methods\\s*=\\s*\\[([^]]*)]"
    );
    private static final Pattern PYTHON_METHOD = Pattern.compile(
            "['\"]([A-Za-z]+)['\"]"
    );
    private static final Pattern PYTHON_FUNCTION = Pattern.compile(
            "^\\s*def\\s+(\\w+)\\s*\\("
    );

    private final DependencyRegistryService registry;
    private final GitHubRepositoryArchiveService githubArchiveService;

    public DependencyScanService(DependencyRegistryService registry) {
        this(registry, new GitHubRepositoryArchiveService());
    }

    @Autowired
    public DependencyScanService(
            DependencyRegistryService registry,
            GitHubRepositoryArchiveService githubArchiveService) {
        this.registry = registry;
        this.githubArchiveService = githubArchiveService;
    }

    public DependencyScanResult scanRepository(
            String applicationId,
            String repositoryPath) {
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("applicationId is required.");
        }
        if (repositoryPath == null || repositoryPath.isBlank()) {
            throw new IllegalArgumentException("repositoryPath is required.");
        }

        if (registry.listApplications().stream()
                .noneMatch(application -> application.applicationId()
                        .equals(applicationId))) {
            throw new IllegalArgumentException(
                    "Unknown applicationId: " + applicationId
            );
        }

        Path downloadedRoot = null;
        Path root;
        if (repositoryPath.trim().regionMatches(
                true, 0, "https://", 0, "https://".length())) {
            downloadedRoot =
                    githubArchiveService.downloadRepository(repositoryPath);
            root = downloadedRoot;
        } else {
            try {
                root = Path.of(repositoryPath).toRealPath();
            } catch (IOException | RuntimeException exception) {
                throw new IllegalArgumentException(
                        "Repository path does not exist or cannot be accessed.",
                        exception
                );
            }
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException(
                        "Repository path must be a directory."
                );
            }
        }

        int scannedFiles = 0;
        boolean truncated = false;
        Set<String> registeredTables = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (isSkipped(root, file)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || !hasSupportedExtension(file)) {
                    continue;
                }
                if (scannedFiles >= MAX_FILES) {
                    truncated = true;
                    break;
                }
                long size = Files.size(file);
                if (size > MAX_FILE_SIZE_BYTES) {
                    continue;
                }
                scannedFiles++;
                String content = Files.readString(file, StandardCharsets.UTF_8);
                if (file.getFileName().toString().toLowerCase(Locale.ROOT)
                        .endsWith(".sql")
                        && SCHEMA_DDL.matcher(content).find()) {
                    continue;
                }
                findReferences(content).stream().distinct().forEach(reference -> {
                    String relativePath = root.relativize(file).toString();
                    registry.registerDependency(
                            new DependencyRegistrationRequest(
                                    applicationId,
                                    reference.tableName(),
                                    null,
                                    reference.dependencyType(),
                                    relativePath,
                                    "Static reference: " + reference.evidence(),
                                    "MEDIUM",
                                    reference.apiEndpoint()
                            )
                    );
                    registeredTables.add(reference.tableName().toLowerCase(Locale.ROOT));
                });
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Unable to scan repository.", exception
            );
        } finally {
            if (downloadedRoot != null) {
                githubArchiveService.deleteDownloadedRepository(downloadedRoot);
            }
        }

        List<ApplicationDependency> applicationDependencies =
                registry.listDependencies().stream()
                        .filter(dependency -> dependency.applicationId()
                                .equals(applicationId))
                        .toList();
        return new DependencyScanResult(
                applicationId,
                scannedFiles,
                registeredTables.size(),
                truncated,
                applicationDependencies
        );
    }

    private static List<ScanReference> findReferences(String content) {
        List<ScanReference> references = new ArrayList<>();
        List<FunctionRoute> functionRoutes = findPythonFunctionRoutes(content);
        Matcher sqlMatcher = SQL_REFERENCE.matcher(content);
        while (sqlMatcher.find()) {
            String operation = sqlMatcher.group(1).toUpperCase(Locale.ROOT);
            String type = operation.equals("FROM") || operation.equals("JOIN")
                    ? "READ" : "WRITE";
            references.add(new ScanReference(
                    sqlMatcher.group(2),
                    type,
                    compact(sqlMatcher.group()),
                    findApiEndpoint(functionRoutes, sqlMatcher.start())
            ));
        }
        Matcher annotationMatcher = TABLE_ANNOTATION.matcher(content);
        while (annotationMatcher.find()) {
            references.add(new ScanReference(
                    annotationMatcher.group(1),
                    "READ_WRITE",
                    compact(annotationMatcher.group()),
                    null
            ));
        }
        return references;
    }

    private static List<FunctionRoute> findPythonFunctionRoutes(String content) {
        List<FunctionRoute> functionRoutes = new ArrayList<>();
        List<String> pendingRoutes = new ArrayList<>();
        int lineOffset = 0;
        for (String line : content.split("\\R", -1)) {
            Matcher routeMatcher = PYTHON_ROUTE.matcher(line);
            if (routeMatcher.matches()) {
                String decorator = routeMatcher.group(1).toUpperCase(Locale.ROOT);
                String path = routeMatcher.group(3);
                Matcher methodsMatcher = PYTHON_METHODS.matcher(routeMatcher.group(4));
                List<String> methods = new ArrayList<>();
                if (decorator.equals("ROUTE") && methodsMatcher.find()) {
                    Matcher methodMatcher =
                            PYTHON_METHOD.matcher(methodsMatcher.group(1));
                    while (methodMatcher.find()) {
                        methods.add(methodMatcher.group(1).toUpperCase(Locale.ROOT));
                    }
                }
                if (methods.isEmpty()) {
                    methods.add(decorator.equals("ROUTE") ? "GET" : decorator);
                }
                for (String method : methods) {
                    pendingRoutes.add(method + " " + path);
                }
            }

            Matcher functionMatcher = PYTHON_FUNCTION.matcher(line);
            if (functionMatcher.find()) {
                if (!pendingRoutes.isEmpty()) {
                    String functionName = functionMatcher.group(1);
                    String endpoint = String.join(", ", pendingRoutes)
                            + " (" + functionName + ")";
                    functionRoutes.add(new FunctionRoute(lineOffset, endpoint));
                }
                pendingRoutes.clear();
            }
            lineOffset += line.length() + 1;
        }
        return functionRoutes;
    }

    private static String findApiEndpoint(
            List<FunctionRoute> functionRoutes,
            int referenceOffset) {
        String endpoint = null;
        for (FunctionRoute functionRoute : functionRoutes) {
            if (functionRoute.offset() > referenceOffset) {
                break;
            }
            endpoint = functionRoute.endpoint();
        }
        return endpoint;
    }

    private static String compact(String evidence) {
        String singleLine = evidence.replaceAll("\\s+", " ").trim();
        return singleLine.length() > 200
                ? singleLine.substring(0, 200)
                : singleLine;
    }

    private record ScanReference(
            String tableName,
            String dependencyType,
            String evidence,
            String apiEndpoint) {
    }

    private record FunctionRoute(int offset, String endpoint) {
    }

    private static boolean hasSupportedExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static boolean isSkipped(Path root, Path file) {
        Path relative = root.relativize(file);
        for (Path part : relative) {
            if (SKIPPED_DIRECTORIES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }
}
