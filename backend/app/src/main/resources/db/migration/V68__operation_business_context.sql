ALTER TABLE operation_record ADD COLUMN change_summary JSON;
ALTER TABLE operation_change
    ADD COLUMN related_info TEXT,
    ADD COLUMN meaningful_change BOOLEAN NOT NULL DEFAULT TRUE;
