package com.schemaguard.model;

import java.util.List;

public record IndexSchema(
        String name,
        boolean unique,
        List<String> columns
) {
}
