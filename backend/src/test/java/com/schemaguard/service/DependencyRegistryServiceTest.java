package com.schemaguard.service;

import com.schemaguard.model.ApplicationRegistrationRequest;
import com.schemaguard.model.ApplicationScanCoverage;
import com.schemaguard.model.DependencyRegistrationRequest;
import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DependencyRegistryServiceTest {

    @Test
    void registersApplicationsAndDependencyEntries() {
        DependencyRegistryService service =
                TestDatabase.newDependencyRegistryService();

        service.registerApplication(new ApplicationRegistrationRequest(
                "billing-service",
                "Billing Service",
                "https://example.com/billing",
                "billing",
                "Payment processing"
        ));

        service.registerDependency(new DependencyRegistrationRequest(
                "billing-service",
                "users",
                "email",
                "READ",
                "manual",
                "billing service checks users.email for invoice lookups",
                "HIGH"
        ));

        assertEquals(1, service.getApplicationNames().size());
        assertEquals(1, service.listDependencies().size());
        assertEquals("users", service.listDependencies().get(0).tableName());
    }

    @Test
    void rejectsUnknownApplications() {
        DependencyRegistryService service =
                TestDatabase.newDependencyRegistryService();

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.registerDependency(new DependencyRegistrationRequest(
                        "missing-app",
                        "users",
                        "id",
                        "READ",
                        "manual",
                        "unused",
                        "HIGH"
                ))
        );

        assertTrue(exception.getMessage().contains("Unknown applicationId"));
    }

    @Test
    void keepsApplicationsDependenciesAndScanCoverageAcrossServiceInstances() {
        JdbcDataSource dataSource = TestDatabase.newDataSource();
        DependencyRegistryService firstInstance =
                new DependencyRegistryService(new JdbcTemplate(dataSource));
        firstInstance.registerApplication(new ApplicationRegistrationRequest(
                "catalog-service",
                "Catalog Service",
                "store",
                "https://example.test/catalog",
                "Product catalog"
        ));
        firstInstance.replaceScannedDependencies(
                "catalog-service",
                List.of(new DependencyRegistrationRequest(
                        "catalog-service",
                        "products",
                        "sku",
                        "READ",
                        "src/ProductRepository.java",
                        "SELECT sku FROM products",
                        "HIGH"
                )),
                new ApplicationScanCoverage(
                        "catalog-service",
                        "catalog",
                        12,
                        false,
                        Instant.parse("2025-01-01T00:00:00Z")
                ));

        DependencyRegistryService restartedInstance =
                new DependencyRegistryService(new JdbcTemplate(dataSource));

        assertEquals("Catalog Service",
                restartedInstance.listApplications().get(0).name());
        assertEquals("sku",
                restartedInstance.listDependencies().get(0).columnName());
        assertEquals(12,
                restartedInstance.getScanCoverage("catalog-service")
                        .analyzedFileCount());
    }
}
