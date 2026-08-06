ALTER TABLE sys_user
    ADD COLUMN phone VARCHAR(40) NULL AFTER display_name;

CREATE INDEX idx_customer_name_enabled ON customer (customer_name, enabled, id);
CREATE INDEX idx_sku_model_enabled ON sku (model, enabled, id);
CREATE INDEX idx_sku_name_enabled ON sku (product_name, enabled, id);
CREATE INDEX idx_sales_status_created ON sales_order (status, created_at, id);
CREATE INDEX idx_sales_customer_created ON sales_order (customer_id, created_at, id);
CREATE INDEX idx_sales_fifo ON sales_order (receipt_confirmed_at, id, status);
CREATE INDEX idx_balance_updated_sku ON inventory_balance (updated_at, sku_id, id);
CREATE INDEX idx_suggestion_status_created ON procurement_suggestion (status, created_at, id);
CREATE INDEX idx_purchase_status_created ON purchase_order (status, created_at, id);
CREATE INDEX idx_purchase_supplier_created ON purchase_order (supplier_id, created_at, id);
CREATE INDEX idx_receipt_time_order ON customer_receipt (received_at, sales_order_id);
CREATE INDEX idx_payment_time_order ON supplier_payment (paid_at, purchase_order_id);
