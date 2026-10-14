package com.schemaguard.service;

import com.schemaguard.model.DependencyEvidence;
import com.schemaguard.model.DependencyScanResult;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DependencyScanService {

    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".git", "node_modules", "target", "build", "out", "dist",
            ".idea", ".vscode", ".gradle", "vendor", "coverage",
            ".next", ".nuxt", "generated", "generated-sources"
    );

    private static final Set<String> SCANNED_EXTENSIONS = Set.of(
            ".java", ".kt", ".sql", ".xml", ".properties", ".js",
            ".ts", ".jsx", ".tsx", ".scala", ".py", ".go", ".cs", ".php", ".rb"
    );

    private static final int MAX_FILES = 200;
    private static final long MAX_FILE_SIZE_BYTES = 1024L * 1024L;
    private static final long MAX_TOTAL_SIZE_BYTES = 50L * 1024L * 1024L;

    private static final Pattern TABLE_REFERENCE_PATTERN = Pattern.compile(
            "(?is)\\b(?:FROM|JOIN|UPDATE|INTO|DELETE\\s+FROM|ALTER\\s+TABLE|CREATE\\s+TABLE|TRUNCATE\\s+TABLE)\\s+[\\x60\\x22\\x27]?([A-Za-z0-9_]+)[\\x60\\x22\\x27]?"
    );

    private static final Pattern SELECT_PROJECTION_PATTERN = Pattern.compile(
            "(?is)\\bSELECT\\s+(?:DISTINCT\\s+)?(.*?)\\s+FROM\\s+[\\x60\\x22\\x27]?([A-Za-z0-9_]+)[\\x60\\x22\\x27]?(?=\\s+(?:WHERE\\b|GROUP\\s+BY\\b|ORDER\\s+BY\\b|HAVING\\b|LIMIT\\b)|\\s*;|[\\x22\\x27]|\\s*$)"
    );

    private static final Pattern SIMPLE_COLUMN_PATTERN = Pattern.compile(
            "(?is)^\\s*(?:[A-Za-z_][A-Za-z0-9_$]*\\s*\\.\\s*)?[\\x60\\x22]?([A-Za-z_][A-Za-z0-9_$]*)[\\x60\\x22]?\\s*(?:\\s+(?:AS\\s+)?[A-Za-z_][A-Za-z0-9_$]*)?\\s*$"
    );

    private static final Pattern JPA_TABLE_ANNOTATION_PATTERN = Pattern.compile(
            "(?is)@(?:Table|SecondaryTable|JoinTable|CollectionTable)\\s*\\((?:[^)]*?)\\b(?:name|tableName)\\s*=\\s*[\\x22'\\x60]?([A-Za-z0-9_]+)[\\x22'\\x60]?(?:[^)]*?)\\)"
    );

    private static final Pattern JPA_ENTITY_PATTERN = Pattern.compile(
            "(?is)@Entity\\s*(?:\\((?:[^)]*?)\\b(?:name|tableName)\\s*=\\s*[\\x22'\\x60]?([A-Za-z0-9_]+)[\\x22'\\x60]?(?:[^)]*?)\\))?"
    );

    private static final Pattern JPA_COLUMN_ANNOTATION_PATTERN = Pattern.compile(
            "(?is)@Column\\s*\\((?:[^)]*?)\\bname\\s*=\\s*[\\x22'\\x60]?([A-Za-z0-9_]+)[\\x22'\\x60]?(?:[^)]*?)\\)"
    );

    private final List<Path> allowedRoots = resolveAllowedRoots();

    public DependencyScanResult scanRepository(String repositoryPath) {
        if (repositoryPath == null || repositoryPath.isBlank()) {
            throw new IllegalArgumentException("repositoryPath is required.");
        }

        Path requestedPath;
        Path root;
        try {
            requestedPath = Path.of(repositoryPath);
            for (Path segment : requestedPath) {
                if ("..".equals(segment.toString())) {
                    throw new IllegalArgumentException(
                            "repositoryPath must not include parent traversal segments."
                    );
                }
            }
            root = requestedPath.toRealPath();
        } catch (IOException | java.nio.file.InvalidPathException exception) {
            throw new IllegalArgumentException("Repository path does not exist or cannot be accessed: " + repositoryPath);
        }

        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Repository path does not exist or is not a directory: " + repositoryPath);
        }

        if (allowedRoots.stream().noneMatch(root::startsWith)) {
            throw new IllegalArgumentException(
                    "Repository path is outside the configured scan roots. Configure SCHEMAGUARD_SCAN_ALLOWEDROOTS for trusted repository directories."
            );
        }

        List<DependencyEvidence> evidence = new ArrayList<>();
        int[] scannedFiles = {0};
        long[] scannedBytes = {0L};
        boolean[] truncated = {false};

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!directory.equals(root) && SKIP_DIRECTORIES.contains(
                            directory.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isSymbolicLink() || !attributes.isRegularFile() || !isSupportedFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (scannedFiles[0] >= MAX_FILES) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }

                    try {
                        long size = attributes.size();
                        if (size > MAX_FILE_SIZE_BYTES) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (scannedBytes[0] + size > MAX_TOTAL_SIZE_BYTES) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }

                        String source = Files.readString(file, StandardCharsets.UTF_8);
                        if (source.indexOf((char) 0) >= 0) {
                            return FileVisitResult.CONTINUE;
                        }

                        scannedFiles[0]++;
                        scannedBytes[0] += size;
                        evidence.addAll(scanFileForReferences(file, source));
                    } catch (IOException exception) {
                        throw new ScanReadException(exception);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    throw new ScanReadException(exception);
                }
            });
        } catch (ScanReadException exception) {
            throw new IllegalArgumentException("Unable to read a source file while scanning repository: " + root);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to scan repository: " + root);
        }

        return new DependencyScanResult(evidence, scannedFiles[0], scannedBytes[0], root.toString(), truncated[0]);
    }

    private boolean shouldSkip(Path file) {
        Path current = file.getParent();
        while (current != null) {
            String name = current.getFileName() == null ? "" : current.getFileName().toString();
            if (SKIP_DIRECTORIES.contains(name.toLowerCase(Locale.ROOT))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private List<DependencyEvidence> scanFileForReferences(Path file, String content) {
        List<DependencyEvidence> matches = new ArrayList<>();
        String searchable = maskComments(content);

        addTableMatches(matches, file, searchable, TABLE_REFERENCE_PATTERN, "static-sql-pattern");
        addTableMatches(matches, file, searchable, JPA_TABLE_ANNOTATION_PATTERN, "jpa-table-annotation");
        addTableMatches(matches, file, searchable, JPA_ENTITY_PATTERN, "jpa-entity-table");
        addSelectColumns(matches, file, searchable);
        addJpaColumns(matches, file, searchable);

        return matches;
    }

    private void addTableMatches(
            List<DependencyEvidence> matches,
            Path file,
            String content,
            Pattern pattern,
            String detector) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String tableName = firstNonNull(matcher.group(1));
            if (tableName == null || tableName.isBlank() || isSqlKeyword(tableName)) {
                continue;
            }
            matches.add(new DependencyEvidence(
                    tableName,
                    null,
                    sourceLocation(file, content, matcher.start()),
                    matcher.group().trim(),
                    detector,
                    detector.startsWith("jpa-") ? "HIGH" : "MEDIUM"
            ));
        }
    }

    private void addSelectColumns(List<DependencyEvidence> matches, Path file, String content) {
        Matcher queryMatcher = SELECT_PROJECTION_PATTERN.matcher(content);
        while (queryMatcher.find()) {
            String projection = queryMatcher.group(1);
            String tableName = firstNonNull(queryMatcher.group(2));
            if (tableName == null) {
                continue;
            }

            for (String item : projection.split(",")) {
                Matcher columnMatcher = SIMPLE_COLUMN_PATTERN.matcher(item.trim());
                if (!columnMatcher.matches()) {
                    continue;
                }

                String columnName = firstNonNull(columnMatcher.group(1));
                if (columnName == null || isSqlKeyword(columnName) || "*".equals(columnName)) {
                    continue;
                }

                matches.add(new DependencyEvidence(
                        tableName,
                        columnName,
                        sourceLocation(file, content, queryMatcher.start(1)),
                        queryMatcher.group().trim(),
                        "static-select-projection",
                        "MEDIUM"
                ));
            }
        }
    }

    private void addJpaColumns(List<DependencyEvidence> matches, Path file, String content) {
        Matcher tableMatcher = JPA_TABLE_ANNOTATION_PATTERN.matcher(content);
        List<String> tableNames = new ArrayList<>();
        while (tableMatcher.find()) {
            String name = firstNonNull(tableMatcher.group(1));
            if (name != null) {
                tableNames.add(name);
            }
        }

        if (tableNames.size() != 1) {
            return;
        }

        String tableName = tableNames.get(0);
        Matcher columnMatcher = JPA_COLUMN_ANNOTATION_PATTERN.matcher(content);
        while (columnMatcher.find()) {
            String columnName = firstNonNull(columnMatcher.group(1));
            if (columnName == null) {
                continue;
            }

            matches.add(new DependencyEvidence(
                    tableName,
                    columnName,
                    sourceLocation(file, content, columnMatcher.start()),
                    columnMatcher.group().trim(),
                    "jpa-column-annotation",
                    "HIGH"
            ));
        }
    }

    private boolean isSupportedFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return SCANNED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private boolean isSqlKeyword(String value) {
        return Set.of("select", "distinct", "where", "on", "set", "values", "table",
                "key", "primary", "foreign", "index", "null", "not", "as", "from").contains(
                value.toLowerCase(Locale.ROOT));
    }

    private String sourceLocation(Path file, String content, int offset) {
        return file.toString() + ":" + lineAt(content, offset);
    }

    private int lineAt(String content, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, content.length()); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String maskComments(String source) {
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

            if (current == '\'' || current == '"' || current == 96) {
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

    private List<Path> resolveAllowedRoots() {
        List<Path> roots = new ArrayList<>();
        String configured = System.getenv("SCHEMAGUARD_SCAN_ALLOWEDROOTS");

        if (configured != null && !configured.isBlank()) {
            for (String item : configured.split(Pattern.quote(File.pathSeparator))) {
                addAllowedRoot(roots, item.trim());
            }
        } else {
            String workingDirectory = System.getProperty("user.dir");
            addAllowedRoot(roots, workingDirectory);
            addAllowedRoot(roots, System.getProperty("java.io.tmpdir"));

            Path current = Path.of(workingDirectory).toAbsolutePath().normalize();
            Path name = current.getFileName();
            if (name != null && "backend".equalsIgnoreCase(name.toString()) && current.getParent() != null) {
                addAllowedRoot(roots, current.getParent().toString());
            }
        }

        return List.copyOf(roots);
    }

    private void addAllowedRoot(List<Path> roots, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        try {
            Path realPath = Path.of(candidate).toRealPath();
            if (Files.isDirectory(realPath) && !roots.contains(realPath)) {
                roots.add(realPath);
            }
        } catch (IOException | java.nio.file.InvalidPathException ignored) {
        }
    }

    private static final class ScanReadException extends RuntimeException {
        private ScanReadException(IOException cause) {
            super(cause);
        }
    }
}
