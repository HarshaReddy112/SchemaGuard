package com.schemaguard.service;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

final class TestDatabase {

    private TestDatabase() {
    }

    static DependencyRegistryService newDependencyRegistryService() {
        JdbcDataSource dataSource = newDataSource();
        return new DependencyRegistryService(new JdbcTemplate(dataSource));
    }

    static JdbcDataSource newDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:" + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return dataSource;
    }
}
