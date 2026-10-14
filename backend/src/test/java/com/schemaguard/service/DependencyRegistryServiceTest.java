package com.schemaguard.service;

import com.schemaguard.model.ApplicationRegistrationRequest;
import com.schemaguard.model.DependencyRegistrationRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DependencyRegistryServiceTest {

    @Test
    void registersApplicationsAndDependencyEntries() {
        DependencyRegistryService service = new DependencyRegistryService();

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
        DependencyRegistryService service = new DependencyRegistryService();

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
}
