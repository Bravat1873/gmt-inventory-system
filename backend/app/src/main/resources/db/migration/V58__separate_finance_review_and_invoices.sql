ALTER TABLE customer_receipt
    MODIFY COLUMN confirmed_by BIGINT NULL,
    ADD COLUMN review_status VARCHAR(20) NULL AFTER confirmed_by,
    ADD COLUMN reviewed_by BIGINT NULL AFTER review_status,
    ADD COLUMN reviewed_at DATETIME(3) NULL AFTER reviewed_by,
    ADD COLUMN review_remark VARCHAR(500) NULL AFTER reviewed_at;

ALTER TABLE supplier_payment
    MODIFY COLUMN confirmed_by BIGINT NULL,
    ADD COLUMN review_status VARCHAR(20) NULL AFTER confirmed_by,
    ADD COLUMN reviewed_by BIGINT NULL AFTER review_status,
    ADD COLUMN reviewed_at DATETIME(3) NULL AFTER reviewed_by,
    ADD COLUMN review_remark VARCHAR(500) NULL AFTER reviewed_at;

ALTER TABLE sales_invoice
    MODIFY COLUMN invoice_date DATE NULL,
    MODIFY COLUMN tax_inclusive_amount DECIMAL(18,2) NULL,
    ADD COLUMN remark VARCHAR(500) NULL AFTER tax_inclusive_amount,
    ADD COLUMN updated_by BIGINT NULL AFTER created_by,
    ADD COLUMN updated_at DATETIME(3) NULL AFTER created_at;

CREATE TABLE purchase_invoice (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    purchase_order_id BIGINT NOT NULL,
    invoice_no VARCHAR(100) NOT NULL,
    invoice_date DATE NULL,
    tax_inclusive_amount DECIMAL(18,2) NULL,
    remark VARCHAR(500) NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NULL,
    CONSTRAINT uk_purchase_invoice_purchase UNIQUE (purchase_order_id),
    CONSTRAINT uk_purchase_invoice_no UNIQUE (invoice_no),
    CONSTRAINT fk_purchase_invoice_purchase FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(id)
);

ALTER TABLE sales_shipment
    ADD COLUMN logistics_company VARCHAR(200) NULL AFTER remark,
    ADD COLUMN logistics_no VARCHAR(100) NULL AFTER logistics_company,
    ADD COLUMN logistics_remark VARCHAR(500) NULL AFTER logistics_no,
    ADD COLUMN logistics_updated_at DATETIME(3) NULL AFTER logistics_remark;
