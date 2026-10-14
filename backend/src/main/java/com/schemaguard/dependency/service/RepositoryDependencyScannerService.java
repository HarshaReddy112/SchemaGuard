package com.schemaguard.dependency.service;

import com.schemaguard.dependency.model.DependencyEvidence;
import com.schemaguard.dependency.model.RepositoryRegistration;
import com.schemaguard.dependency.model.RepositoryScanResult;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RepositoryDependencyScannerService {

    private static final int MAX_FILES = 10000;
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 50L * 1024L * 1024L;

    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "target", "dist",
            "build", "out", "bin", ".gradle", "vendor", "coverage",
            ".next", ".nuxt", "generated", "generated-sources"
    );

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".java", ".kt", ".scala", ".sql", ".xml", ".properties",
            ".yaml", ".yml", ".py", ".js", ".jsx", ".ts", ".tsx",
            ".go", ".cs", ".php", ".rb"
    );

    private static final Pattern TABLE_REFERENCE = Pattern.compile(
            "(?i)\\b(?:FROM|JOIN|UPDATE|INTO|REFERENCES)\\s+[\\x60\\\"']?([A-Za-z_][A-Za-z0-9_$]*)[\\x60\\\"']?"
    );

    private static final Pattern SIMPLE_SELECT = Pattern.compile(
            "(?is)\\bSELECT\\s+(?:DISTINCT\\s+)?(.+?)\\s+FROM\\s+[\\x60\\\"']?([A-Za-z_][A-Za-z0-9_$]*)[\\x60\\\"']?(?:\\s+(?:AS\\s+)?[A-Za-z_][A-Za-z0-9_$]*)?\\s*(?=WHERE\\b|GROUP\\s+BY\\b|ORDER\\s+BY\\b|HAVING\\b|LIMIT\\b|;|$)"
    );

    private static final Pattern SIMPLE_COLUMN = Pattern.compile(
            "(?is)^(?:[A-Za-z_][A-Za-z0-9_$]*\\s*\\.\\s*)?[\\x60\\\"']?([A-Za-z_][A-Za-z0-9_$]*)[\\x60\\\"']?(?:\\s+(?:AS\\s+)?[A-Za-z_][A-Za-z0-9_$]*)?$"
    );

    private static final Pattern TABLE_ANNOTATION = Pattern.compile(
            "(?is)@Table\\s*\\([^)]*?\\bname\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"
    );

    private static final Pattern COLUMN_ANNOTATION = Pattern.compile(
            "(?is)@Column\\s*\\([^)]*?\\bname\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"
    );

    private final RepositoryRegistryService registryService;

    public RepositoryDependencyScannerService(RepositoryRegistryService registryService) {
        this.registryService = registryService;
    }

    public List<RepositoryScanResult> scanRepositories(List<String> repositoryIds) {
        return registryService.resolveForScan(repositoryIds).stream()
                .map(this::scanRepository)
                .toList();
    }

    public RepositoryScanResult scanRepository(RepositoryRegistration repository) {
        Path root = Path.of(repository.localPath()).toAbsolutePath().normalize();
        Map<String, DependencyEvidence> found = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        int[] filesScanned = {0};
        long[] bytesScanned = {0};
        boolean[] truncated = {false};

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    if (!dir.equals(root) && IGNORED_DIRECTORIES.contains(
                            dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink() || !attrs.isRegularFile() || !isSupportedFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }

                    if (filesScanned[0] >= MAX_FILES || bytesScanned[0] >= MAX_TOTAL_BYTES) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }

                    try {
                        long size = attrs.size();
                        if (size > MAX_FILE_BYTES) {
                            warnings.add("Skipped oversized source file: " + relative(root, file));
                            return FileVisitResult.CONTINUE;
                        }

                        if (bytesScanned[0] + size > MAX_TOTAL_BYTES) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }

                        String source = Files.readString(file, StandardCharsets.UTF_8);
                        if (source.indexOf((char) 0) >= 0) {
                            warnings.add("Skipped binary-like file: " + relative(root, file));
                            return FileVisitResult.CONTINUE;
                        }

                        filesScanned[0]++;
                        bytesScanned[0] += size;
                        scanFile(repository, root, file, source, found);
                    } catch (IOException exception) {
                        warnings.add("Could not read source file: " + relative(root, file));
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    warnings.add("Could not access path: " + relative(root, file));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            warnings.add("Repository scan failed because its directory could not be traversed.");
        }

        if (truncated[0]) {
            warnings.add("Scan limits were reached. The result may be incomplete.");
        }

        warnings.add("Static scanning is heuristic. No match does not prove that an application has no dependency.");

        List<DependencyEvidence> dependencies = List.copyOf(found.values());
        return new RepositoryScanResult(
                repository.repositoryId(),
                repository.repositoryName(),
                Instant.now(),
                filesScanned[0],
                dependencies.size(),
                dependencies,
                warnings
        );
    }

    private void scanFile(
            RepositoryRegistration repository,
            Path root,
            Path file,
            String source,
            Map<String, DependencyEvidence> found) {

        String relativePath = relative(root, file);
        String searchable = maskComments(source);

        Matcher tableMatcher = TABLE_REFERENCE.matcher(searchable);
        while (tableMatcher.find()) {
            String table = normalizeIdentifier(tableMatcher.group(1));
            if (isSqlKeyword(table)) {
                continue;
            }

            addEvidence(found, new DependencyEvidence(
                    repository.repositoryId(),
                    repository.repositoryName(),
                    relativePath,
                    lineAt(searchable, tableMatcher.start(1)),
                    "TABLE",
                    table,
                    null,
                    "STATIC_SQL_TABLE_REFERENCE",
                    "MEDIUM",
                    tableMatcher.group()
            ));
        }

        Matcher selectMatcher = SIMPLE_SELECT.matcher(searchable);
        while (selectMatcher.find()) {
            String table = normalizeIdentifier(selectMatcher.group(2));
            String projection = selectMatcher.group(1);
            for (String expression : projection.split(",")) {
                Matcher columnMatcher = SIMPLE_COLUMN.matcher(expression.trim());
                if (!columnMatcher.matches()) {
                    continue;
                }

                String column = normalizeIdentifier(columnMatcher.group(1));
                if (isSqlKeyword(column) || "*".equals(column)) {
                    continue;
                }

                addEvidence(found, new DependencyEvidence(
                        repository.repositoryId(),
                        repository.repositoryName(),
                        relativePath,
                        lineAt(searchable, selectMatcher.start(1)),
                        "COLUMN",
                        table,
                        column,
                        "STATIC_SQL_SELECT_PROJECTION",
                        "MEDIUM",
                        selectMatcher.group().length() > 240
                                ? selectMatcher.group().substring(0, 240)
                                : selectMatcher.group()
                ));
            }
        }

        Matcher tableAnnotation = TABLE_ANNOTATION.matcher(searchable);
        List<String> annotatedTables = new ArrayList<>();
        while (tableAnnotation.find()) {
            annotatedTables.add(normalizeIdentifier(tableAnnotation.group(1)));
            addEvidence(found, new DependencyEvidence(
                    repository.repositoryId(),
                    repository.repositoryName(),
                    relativePath,
                    lineAt(searchable, tableAnnotation.start()),
                    "TABLE",
                    normalizeIdentifier(tableAnnotation.group(1)),
                    null,
                    "JPA_TABLE_ANNOTATION",
                    "HIGH",
                    tableAnnotation.group()
            ));
        }

        if (annotatedTables.size() == 1) {
            String table = annotatedTables.get(0);
            Matcher columnAnnotation = COLUMN_ANNOTATION.matcher(searchable);
            while (columnAnnotation.find()) {
                addEvidence(found, new DependencyEvidence(
                        repository.repositoryId(),
                        repository.repositoryName(),
                        relativePath,
                        lineAt(searchable, columnAnnotation.start()),
                        "COLUMN",
                        table,
                        normalizeIdentifier(columnAnnotation.group(1)),
                        "JPA_COLUMN_ANNOTATION",
                        "HIGH",
                        columnAnnotation.group()
                ));
            }
        }
    }

    private void addEvidence(Map<String, DependencyEvidence> found, DependencyEvidence evidence) {
        String key = String.join("|",
                safe(evidence.repositoryId()),
                safe(evidence.filePath()),
                Integer.toString(evidence.lineNumber()),
                safe(evidence.databaseObjectType()),
                safe(evidence.tableName()),
                safe(evidence.columnName()),
                safe(evidence.detectionMethod()));
        found.putIfAbsent(key, evidence);
    }

    private static String maskComments(String source) {
        StringBuilder result = new StringBuilder(source);
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        boolean escaped = false;

        for (int i = 0; i < result.length(); i++) {
            char current = result.charAt(i);
            char next = i + 1 < result.length() ? result.charAt(i + 1) : 0;

            if (lineComment) {
                if (current == '\n' || current == '\r') {
                    lineComment = false;
                } else {
                    result.setCharAt(i, ' ');
                }
                continue;
            }

            if (blockComment) {
                if (current == '*' && next == '/') {
                    result.setCharAt(i, ' ');
                    result.setCharAt(i + 1, ' ');
                    i++;
                    blockComment = false;
                } else if (current != '\n' && current != '\r') {
                    result.setCharAt(i, ' ');
                }
                continue;
            }

            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }

            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
                continue;
            }

            if (current == '/' && next == '*') {
                result.setCharAt(i, ' ');
                result.setCharAt(i + 1, ' ');
                i++;
                blockComment = true;
            } else if (current == '/' && next == '/') {
                result.setCharAt(i, ' ');
                result.setCharAt(i + 1, ' ');
                i++;
                lineComment = true;
            } else if (current == '-' && next == '-') {
                result.setCharAt(i, ' ');
                result.setCharAt(i + 1, ' ');
                i++;
                lineComment = true;
            }
        }

        return result.toString();
    }

    private static boolean isSupportedFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static boolean isSqlKeyword(String value) {
        return Set.of("select", "distinct", "where", "on", "set", "values", "table",
                "key", "primary", "foreign", "index", "null", "not", "as").contains(
                value.toLowerCase(Locale.ROOT));
    }

    private static String normalizeIdentifier(String value) {
        return value == null ? null : value.replace("`", "").replace("\"", "").replace("'", "").trim();
    }

    private static int lineAt(String source, int position) {
        int line = 1;
        int limit = Math.min(position, source.length());
        for (int i = 0; i < limit; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
