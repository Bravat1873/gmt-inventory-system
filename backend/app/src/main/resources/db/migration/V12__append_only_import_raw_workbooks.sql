CREATE TABLE import_workbook_file (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    file_content LONGBLOB NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_import_workbook_file_batch (batch_id),
    CONSTRAINT fk_import_workbook_file_batch FOREIGN KEY (batch_id) REFERENCES import_batch(id)
);

CREATE TABLE import_workbook_sheet (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    sheet_index INT NOT NULL,
    sheet_name VARCHAR(255) NOT NULL,
    column_count INT NOT NULL,
    INDEX idx_import_workbook_sheet_batch (batch_id),
    CONSTRAINT fk_import_workbook_sheet_batch FOREIGN KEY (batch_id) REFERENCES import_batch(id)
);

CREATE TABLE import_workbook_row (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id BIGINT NOT NULL,
    sheet_id BIGINT NOT NULL,
    source_row INT NOT NULL,
    cell_values JSON NOT NULL,
    INDEX idx_import_workbook_row_batch (batch_id),
    INDEX idx_import_workbook_row_sheet_source (sheet_id, source_row),
    CONSTRAINT fk_import_workbook_row_batch FOREIGN KEY (batch_id) REFERENCES import_batch(id),
    CONSTRAINT fk_import_workbook_row_sheet FOREIGN KEY (sheet_id) REFERENCES import_workbook_sheet(id)
);
