package com.schemaguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class GitHubRepositoryArchiveService {

    private static final long MAX_ARCHIVE_BYTES = 25L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 100L * 1024 * 1024;
    private static final int MAX_ARCHIVE_ENTRIES = 20_000;
    private static final int MAX_EXTRACTED_SOURCE_FILES = 501;
    private static final long MAX_SOURCE_FILE_BYTES = 1_048_576;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".java", ".kt", ".py", ".sql", ".xml", ".properties",
            ".js", ".ts", ".jsx", ".tsx"
    );
    private static final Pattern REPOSITORY_URL = Pattern.compile(
            "(?i)^https://github\\.com/([a-z0-9](?:[a-z0-9-]{0,38}))/"
                    + "([a-z0-9_.-]+?)(?:\\.git)?/?$"
    );
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public Path downloadRepository(String repositoryUrl) {
        Repository repository = parseRepositoryUrl(repositoryUrl);
        Path temporaryDirectory = null;
        try {
            String branch = fetchDefaultBranch(repository);
            temporaryDirectory = Files.createTempDirectory("schemaguard-github-");
            Path archive = temporaryDirectory.resolve("repository.zip");
            downloadArchive(repository, branch, archive);
            Path extractedRoot = temporaryDirectory.resolve("source");
            Files.createDirectory(extractedRoot);
            extractSourceFiles(archive, extractedRoot);
            Files.delete(archive);
            return extractedRoot;
        } catch (IOException exception) {
            deleteTemporaryDirectory(temporaryDirectory);
            throw new IllegalArgumentException(
                    "Unable to download or safely extract the public GitHub repository.",
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            deleteTemporaryDirectory(temporaryDirectory);
            throw new IllegalArgumentException(
                    "GitHub repository download was interrupted.",
                    exception
            );
        } catch (RuntimeException exception) {
            deleteTemporaryDirectory(temporaryDirectory);
            throw exception;
        }
    }

    public void deleteDownloadedRepository(Path extractedRoot) {
        if (extractedRoot != null) {
            deleteTemporaryDirectory(extractedRoot.getParent());
        }
    }

    static Repository parseRepositoryUrl(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new IllegalArgumentException("repositoryPath is required.");
        }
        Matcher matcher = REPOSITORY_URL.matcher(repositoryUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Repository must be a local directory path or a public "
                            + "GitHub URL like https://github.com/owner/repository."
            );
        }
        String repositoryName = matcher.group(2);
        if (repositoryName.equals(".") || repositoryName.equals("..")) {
            throw new IllegalArgumentException("Invalid GitHub repository name.");
        }
        return new Repository(matcher.group(1), repositoryName);
    }

    private static String fetchDefaultBranch(Repository repository)
            throws IOException, InterruptedException {
        URI uri = URI.create(
                "https://api.github.com/repos/"
                        + repository.owner() + "/" + repository.name()
        );
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "SchemaGuard-Dependency-Scanner")
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP_CLIENT.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException(
                        "GitHub could not read this repository. Check that it is "
                                + "public and the URL points to a repository root."
                );
            }
            JsonNode json = OBJECT_MAPPER.readTree(readLimited(
                    body, 1_048_576, "GitHub repository metadata"
            ));
            String branch = json.path("default_branch").asText();
            if (branch.isBlank()) {
                throw new IllegalArgumentException(
                        "GitHub did not provide a default branch for this repository."
                );
            }
            return branch;
        }
    }

    private static void downloadArchive(
            Repository repository,
            String branch,
            Path archive) throws IOException, InterruptedException {
        String encodedBranch = branch.replace("%", "%25")
                .replace("/", "%2F")
                .replace(" ", "%20");
        URI archiveUri = URI.create(
                "https://codeload.github.com/"
                        + repository.owner() + "/" + repository.name()
                        + "/zip/refs/heads/" + encodedBranch
        );
        HttpResponse<InputStream> response = sendArchiveRequest(archiveUri);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IllegalArgumentException(
                        "GitHub did not provide a downloadable repository archive."
                );
            }
            try (var output = Files.newOutputStream(archive)) {
                copyLimited(body, output, MAX_ARCHIVE_BYTES,
                        "GitHub repository archive");
            }
        }
    }

    private static HttpResponse<InputStream> sendArchiveRequest(URI uri)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", "SchemaGuard-Dependency-Scanner")
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP_CLIENT.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 300 && response.statusCode() < 400) {
            try (InputStream body = response.body()) {
                String location = response.headers()
                        .firstValue("Location")
                        .orElseThrow(() -> new IllegalArgumentException(
                                "GitHub archive redirect did not include a location."
                        ));
                URI redirect = uri.resolve(location);
                if (!"https".equalsIgnoreCase(redirect.getScheme())
                        || !"codeload.github.com".equalsIgnoreCase(
                                redirect.getHost())) {
                    throw new IllegalArgumentException(
                            "GitHub archive redirected to an unsupported host."
                    );
                }
                return HTTP_CLIENT.send(
                        HttpRequest.newBuilder(redirect)
                                .timeout(Duration.ofSeconds(45))
                                .header("User-Agent",
                                        "SchemaGuard-Dependency-Scanner")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream()
                );
            }
        }
        return response;
    }

    private static void extractSourceFiles(Path archive, Path destination)
            throws IOException {
        long totalExpandedBytes = 0;
        int entryCount = 0;
        int sourceFileCount = 0;
        try (InputStream fileInput = Files.newInputStream(archive);
             ZipInputStream zip = new ZipInputStream(fileInput)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IllegalArgumentException(
                            "GitHub repository archive has too many files."
                    );
                }
                String entryName = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }

                Path relativePath;
                try {
                    relativePath = Path.of(entryName).normalize();
                } catch (InvalidPathException exception) {
                    throw new IllegalArgumentException(
                            "GitHub archive contains an invalid file path.",
                            exception
                    );
                }
                if (entryName.contains("\\")
                        || relativePath.isAbsolute()
                        || relativePath.startsWith("..")
                        || relativePath.getNameCount() < 2) {
                    throw new IllegalArgumentException(
                            "GitHub archive contains an unsafe file path."
                    );
                }
                Path sourcePath = relativePath.subpath(
                        1, relativePath.getNameCount());
                if (sourcePath.toString().isBlank()) {
                    continue;
                }

                boolean extract = hasSupportedExtension(sourcePath)
                        && sourceFileCount < MAX_EXTRACTED_SOURCE_FILES;
                Path outputPath = destination.resolve(sourcePath).normalize();
                if (!outputPath.startsWith(destination)) {
                    throw new IllegalArgumentException(
                            "GitHub archive contains a path outside its root."
                    );
                }
                long fileBytes = 0;
                if (extract) {
                    Files.createDirectories(outputPath.getParent());
                    try (var output = Files.newOutputStream(outputPath)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = zip.read(buffer)) != -1) {
                            fileBytes += bytesRead;
                            totalExpandedBytes += bytesRead;
                            enforceExpandedLimit(totalExpandedBytes);
                            if (fileBytes <= MAX_SOURCE_FILE_BYTES) {
                                output.write(buffer, 0, bytesRead);
                            }
                        }
                    }
                    if (fileBytes > MAX_SOURCE_FILE_BYTES) {
                        Files.delete(outputPath);
                    } else {
                        sourceFileCount++;
                    }
                } else {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = zip.read(buffer)) != -1) {
                        totalExpandedBytes += bytesRead;
                        enforceExpandedLimit(totalExpandedBytes);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void enforceExpandedLimit(long totalExpandedBytes) {
        if (totalExpandedBytes > MAX_EXTRACTED_BYTES) {
            throw new IllegalArgumentException(
                    "GitHub repository expands beyond the 100 MiB scan limit."
            );
        }
    }

    private static boolean hasSupportedExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return ALLOWED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static byte[] readLimited(
            InputStream input,
            long limit,
            String description) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copyLimited(input, output, limit, description);
        return output.toByteArray();
    }

    private static void copyLimited(
            InputStream input,
            java.io.OutputStream output,
            long limit,
            String description) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            total += bytesRead;
            if (total > limit) {
                throw new IllegalArgumentException(
                        description + " exceeds the configured size limit."
                );
            }
            output.write(buffer, 0, bytesRead);
        }
    }

    private static void deleteTemporaryDirectory(Path directory) {
        if (directory == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted((first, second) ->
                            second.getNameCount() - first.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new IllegalStateException(
                                    "Unable to remove temporary GitHub scan files.",
                                    exception
                            );
                        }
                    });
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to remove temporary GitHub scan files.",
                    exception
            );
        }
    }

    record Repository(String owner, String name) {
    }
}
