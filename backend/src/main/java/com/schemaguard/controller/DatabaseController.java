
package com.schemaguard.controller;

import com.schemaguard.service.DatabaseHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/system")
public class DatabaseController {

    private final DatabaseHealthService databaseHealthService;

    public DatabaseController(DatabaseHealthService databaseHealthService) {
        this.databaseHealthService = databaseHealthService;
    }

    @GetMapping("/database")
    public Map<String, String> databaseStatus() {
        return databaseHealthService.checkConnection();
    }
}