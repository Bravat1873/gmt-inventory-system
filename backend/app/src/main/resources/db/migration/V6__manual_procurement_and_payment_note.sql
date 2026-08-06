ALTER TABLE purchase_order
    DROP FOREIGN KEY fk_po_suggestion;

ALTER TABLE purchase_order
    MODIFY COLUMN suggestion_id BIGINT NULL,
    ADD COLUMN manual_entry BOOLEAN NOT NULL DEFAULT FALSE AFTER suggestion_id,
    ADD COLUMN purchase_remark VARCHAR(500) NULL AFTER expected_arrival_date;

ALTER TABLE purchase_order
    ADD CONSTRAINT fk_po_suggestion FOREIGN KEY (suggestion_id) REFERENCES procurement_suggestion(id);

ALTER TABLE supplier_payment
    ADD COLUMN payment_remark VARCHAR(500) NULL AFTER payment_method,
    ADD COLUMN invoice_no VARCHAR(100) NULL AFTER payment_remark,
    ADD COLUMN invoice_date DATE NULL AFTER invoice_no;
