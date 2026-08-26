ALTER TABLE customer_receipt
    ADD COLUMN confirmed_amount DECIMAL(18,2) NULL AFTER amount;

ALTER TABLE supplier_payment
    ADD COLUMN confirmed_amount DECIMAL(18,2) NULL AFTER amount;

ALTER TABLE sales_invoice
    ADD COLUMN confirmed_invoice_no VARCHAR(120) NULL AFTER invoice_no,
    ADD COLUMN confirmed_amount DECIMAL(18,2) NULL AFTER tax_inclusive_amount,
    ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER remark,
    ADD COLUMN review_remark VARCHAR(500) NULL AFTER review_status,
    ADD COLUMN reviewed_by BIGINT NULL AFTER review_remark,
    ADD COLUMN reviewed_at DATETIME(3) NULL AFTER reviewed_by;

ALTER TABLE purchase_invoice
    ADD COLUMN confirmed_invoice_no VARCHAR(120) NULL AFTER invoice_no,
    ADD COLUMN confirmed_amount DECIMAL(18,2) NULL AFTER tax_inclusive_amount,
    ADD COLUMN review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER remark,
    ADD COLUMN review_remark VARCHAR(500) NULL AFTER review_status,
    ADD COLUMN reviewed_by BIGINT NULL AFTER review_remark,
    ADD COLUMN reviewed_at DATETIME(3) NULL AFTER reviewed_by;
