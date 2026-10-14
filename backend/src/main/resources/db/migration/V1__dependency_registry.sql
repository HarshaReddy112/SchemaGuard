CREATE TABLE sg_applications (
    application_id VARCHAR(191) NOT NULL,
    name VARCHAR(255) NOT NULL,
    database_name VARCHAR(255) NOT NULL,
    repository VARCHAR(2048),
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (application_id)
) ENGINE=InnoDB;

CREATE TABLE sg_dependencies (
    id BIGINT NOT NULL AUTO_INCREMENT,
    dependency_key CHAR(64) NOT NULL,
    application_id VARCHAR(191) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    column_name VARCHAR(255),
    dependency_type VARCHAR(32) NOT NULL,
    source VARCHAR(1024) NOT NULL,
    evidence TEXT NOT NULL,
    api_endpoint VARCHAR(512),
    confidence VARCHAR(16) NOT NULL,
    scan_generated BOOLEAN NOT NULL DEFAULT FALSE,
    discovered_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uq_sg_dependencies_key UNIQUE (dependency_key),
    CONSTRAINT fk_sg_dependencies_application
        FOREIGN KEY (application_id)
        REFERENCES sg_applications (application_id)
        ON DELETE CASCADE,
    INDEX idx_sg_dependencies_table_column (table_name, column_name),
    INDEX idx_sg_dependencies_application (application_id)
) ENGINE=InnoDB;

CREATE TABLE sg_scan_coverage (
    application_id VARCHAR(191) NOT NULL,
    repository VARCHAR(2048) NOT NULL,
    analyzed_file_count INT NOT NULL,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    scanned_at TIMESTAMP NOT NULL,
    PRIMARY KEY (application_id),
    CONSTRAINT fk_sg_scan_coverage_application
        FOREIGN KEY (application_id)
        REFERENCES sg_applications (application_id)
        ON DELETE CASCADE
) ENGINE=InnoDB;
