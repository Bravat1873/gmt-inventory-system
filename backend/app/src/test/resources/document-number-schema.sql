DROP TABLE IF EXISTS document_number_sequence;

CREATE TABLE document_number_sequence (
    document_type VARCHAR(32) NOT NULL,
    year_month CHAR(6) NOT NULL,
    current_value INT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_type, year_month),
    CONSTRAINT ck_document_number_value CHECK (current_value BETWEEN 0 AND 99999)
);
