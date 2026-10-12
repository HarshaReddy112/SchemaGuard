package com.schemaguard.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class DatabaseHealthService {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseHealthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, String> checkConnection() {
        String database = jdbcTemplate.queryForObject(
                "SELECT DATABASE()", String.class);

        String version = jdbcTemplate.queryForObject(
                "SELECT VERSION()", String.class);

        return Map.of(
                "status", "connected",
                "database", database,
                "version", version
        );
    }
}
