package com.schemaguard.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitHubRepositoryArchiveServiceTest {

    @Test
    void acceptsPublicGithubRepositoryRootUrls() {
        var repository = GitHubRepositoryArchiveService.parseRepositoryUrl(
                "https://github.com/HarshaReddy112/Hotel-Reservation-System-Web-App"
        );

        assertEquals("HarshaReddy112", repository.owner());
        assertEquals(
                "Hotel-Reservation-System-Web-App",
                repository.name()
        );
    }

    @Test
    void rejectsNonGithubUrlsAndRepositorySubpaths() {
        assertThrows(IllegalArgumentException.class,
                () -> GitHubRepositoryArchiveService.parseRepositoryUrl(
                        "https://example.com/owner/repository"
                ));
        assertThrows(IllegalArgumentException.class,
                () -> GitHubRepositoryArchiveService.parseRepositoryUrl(
                        "https://github.com/owner/repository/tree/main"
                ));
        assertThrows(IllegalArgumentException.class,
                () -> GitHubRepositoryArchiveService.parseRepositoryUrl(
                        "https://github.com/owner/repository?redirect=example.com"
                ));
    }
}
