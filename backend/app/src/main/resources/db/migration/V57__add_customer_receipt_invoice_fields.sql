ALTER TABLE customer_receipt
    ADD COLUMN payment_remark VARCHAR(500) NULL AFTER payment_method,
    ADD COLUMN invoice_no VARCHAR(100) NULL AFTER payment_remark,
    ADD COLUMN invoice_date DATE NULL AFTER invoice_no;
