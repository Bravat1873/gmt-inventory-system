CREATE INDEX idx_customer_receipt_order ON customer_receipt (sales_order_id);
ALTER TABLE customer_receipt DROP INDEX uk_receipt_order;
