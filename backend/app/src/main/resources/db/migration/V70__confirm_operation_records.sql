ALTER TABLE operation_record
    ADD COLUMN confirmed_at DATETIME(3),
    ADD COLUMN confirmed_by BIGINT,
    ADD COLUMN confirmed_by_name VARCHAR(100);
