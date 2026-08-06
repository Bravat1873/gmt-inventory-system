CREATE TABLE import_batch (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    import_type VARCHAR(20) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_hash CHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    total_rows INT NOT NULL DEFAULT 0,
    valid_rows INT NOT NULL DEFAULT 0,
    error_rows INT NOT NULL DEFAULT 0,
    ignored_rows INT NOT NULL DEFAULT 0,
    committed_rows INT NOT NULL DEFAULT 0,
    result_detail JSON,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    committed_at DATETIME(3),
    CONSTRAINT uk_import_batch_type_hash UNIQUE (import_type, file_hash)
);

CREATE TABLE import_row (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    source_sheet VARCHAR(100) NOT NULL,
    source_row INT NOT NULL,
    row_status VARCHAR(20) NOT NULL,
    normalized_data JSON NOT NULL,
    error_message VARCHAR(1000),
    manual_entry BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_import_row_batch_status (batch_id, row_status),
    CONSTRAINT fk_import_row_batch FOREIGN KEY (batch_id) REFERENCES import_batch(id),
    CONSTRAINT ck_import_row_status CHECK (row_status IN ('VALID', 'ERROR', 'IGNORED'))
);
