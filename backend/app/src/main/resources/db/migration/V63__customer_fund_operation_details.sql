ALTER TABLE customer_fund_request
    ADD COLUMN operation_no VARCHAR(40) NULL,
    ADD COLUMN payment_at DATETIME(3) NULL,
    ADD COLUMN confirmed_amount DECIMAL(19,4) NULL;

UPDATE customer_fund_request
SET operation_no = CONCAT('ZJ', DATE_FORMAT(submitted_at, '%Y%m'), LPAD(id, GREATEST(5, CHAR_LENGTH(id)), '0')),
    confirmed_amount = CASE WHEN status IN ('APPROVED','REVERSED') THEN amount ELSE NULL END;

CREATE UNIQUE INDEX uk_customer_fund_operation_no ON customer_fund_request(operation_no);
CREATE INDEX idx_customer_fund_payment_date ON customer_fund_request(payment_date, id);
