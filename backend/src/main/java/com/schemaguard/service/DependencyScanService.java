package com.schemaguard.service;

import com.schemaguard.model.DependencyEvidence;
import com.schemaguard.model.DependencyScanResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DependencyScanService {

    private static final Set<String> SKIP_DIRECTORIES = Set.of(
            ".git",
            "node_modules",
            "target",
            "build",
            "out",
            "dist",
            ".idea",
            ".vscode"
    );

    private static final Set<String> SCANNED_EXTENSIONS = Set.of(
            ".java",
            ".kt",
            ".sql",
            ".xml",
            ".properties",
            ".js",
            ".ts",
            ".jsx",
            ".tsx"
    );

    private static final Pattern TABLE_REFERENCE_PATTERN = Pattern.compile(
            "(?is)\\b(?:FROM|JOIN|UPDATE|INTO|DELETE\\s+FROM|ALTER\\s+TABLE|CREATE\\s+TABLE|TRUNCATE\\s+TABLE)\\s+(?:`?([A-Za-z0-9_]+)`?|\"?([A-Za-z0-9_]+)\"?)"
    );

    private static final Pattern JPA_TABLE_ANNOTATION_PATTERN = Pattern.compile(
            "(?is)@(?:Table|SecondaryTable|JoinTable|CollectionTable)\\s*\\((?:[^)]*?)\\b(?:name|tableName)\\s*=\\s*[\"'`]?(?:([A-Za-z0-9_]+)|\"([A-Za-z0-9_]+)\"|`([A-Za-z0-9_]+)`)[\"'`]?(?:[^)]*?)\\)"
    );

    private static final Pattern JPA_ENTITY_PATTERN = Pattern.compile(
            "(?is)@Entity\\s*(?:\\((?:[^)]*?)\\b(?:name|tableName)\\s*=\\s*[\"'`]?(?:([A-Za-z0-9_]+)|\"([A-Za-z0-9_]+)\"|`([A-Za-z0-9_]+)`)[\"'`]?(?:[^)]*?)\\))?"
    );

    private static final int MAX_FILES = 200;
    private static final long MAX_FILE_SIZE_BYTES = 1024 * 1024;

    public DependencyScanResult scanRepository(String repositoryPath) {
        if (repositoryPath == null || repositoryPath.isBlank()) {
            throw new IllegalArgumentException("repositoryPath is required.");
        }
        if (repositoryPath.contains("..")) {
            throw new IllegalArgumentException("repositoryPath must not include parent traversal segments.");
        }

        Path root = Path.of(repositoryPath).toAbsolutePath().normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("Repository path does not exist or is not a directory: " + repositoryPath);
        }

        List<DependencyEvidence> evidence = new ArrayList<>();
        int scannedFiles = 0;
        long scannedBytes = 0L;
        boolean truncated = false;

        try {
            List<Path> files = new ArrayList<>();
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .forEach(files::add);
            }

            if (files.size() > MAX_FILES) {
                truncated = true;
            }

            for (Path file : files) {
                if (scannedFiles >= MAX_FILES) {
                    truncated = true;
                    break;
                }

                if (shouldSkip(file)) {
                    continue;
                }

                String fileName = file.getFileName().toString();
                String lower = fileName.toLowerCase(Locale.ROOT);
                if (SCANNED_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
                    continue;
                }

                long size = Files.size(file);
                if (size > MAX_FILE_SIZE_BYTES) {
                    continue;
                }

                scannedFiles++;
                scannedBytes += size;

                String content = Files.readString(file, StandardCharsets.UTF_8);
                evidence.addAll(scanFileForReferences(file, content));
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to scan repository: " + repositoryPath, exception);
        }

        return new DependencyScanResult(evidence, scannedFiles, scannedBytes, root.toString(), truncated);
    }

    private boolean shouldSkip(Path file) {
        Path current = file;
        while (current != null) {
            String name = current.getFileName() == null ? "" : current.getFileName().toString();
            if (SKIP_DIRECTORIES.contains(name)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private List<DependencyEvidence> scanFileForReferences(Path file, String content) {
        List<DependencyEvidence> matches = new ArrayList<>();
        addMatches(matches, file, content, TABLE_REFERENCE_PATTERN, "static-sql-pattern");
        addMatches(matches, file, content, JPA_TABLE_ANNOTATION_PATTERN, "jpa-table-annotation");
        addMatches(matches, file, content, JPA_ENTITY_PATTERN, "jpa-entity-table");
        return matches;
    }

    private void addMatches(List<DependencyEvidence> matches, Path file, String content, Pattern pattern, String detector) {
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String tableName = null;
            for (int i = 1; i <= matcher.groupCount(); i++) {
                String groupValue = matcher.group(i);
                if (groupValue != null && !groupValue.isBlank()) {
                    tableName = groupValue;
                    break;
                }
            }
            if (tableName == null || tableName.isBlank()) {
                continue;
            }

            String snippet = content.substring(
                    Math.max(0, matcher.start() - 40),
                    Math.min(content.length(), matcher.end() + 80)
            ).replaceAll("\\s+", " ").trim();

            matches.add(new DependencyEvidence(
                    tableName,
                    null,
                    file.toString(),
                    snippet,
                    detector,
                    "MEDIUM"
            ));
        }
    }

    private String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
