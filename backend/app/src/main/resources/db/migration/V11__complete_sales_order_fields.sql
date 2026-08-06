ALTER TABLE sales_order
    ADD COLUMN order_date DATE NULL,
    ADD COLUMN order_type VARCHAR(40) NULL,
    ADD COLUMN salesperson VARCHAR(100) NULL,
    ADD COLUMN customer_contact VARCHAR(100) NULL,
    ADD COLUMN customer_phone VARCHAR(60) NULL,
    ADD COLUMN order_remark VARCHAR(1000) NULL,
    ADD COLUMN delivery_address VARCHAR(1000) NULL,
    ADD COLUMN delivery_contact VARCHAR(100) NULL,
    ADD COLUMN delivery_phone VARCHAR(60) NULL,
    ADD COLUMN shipping_method VARCHAR(100) NULL;

UPDATE sales_order
SET order_date = DATE(created_at), order_type = 'SALES_ORDER', salesperson = 'SYSTEM'
WHERE order_date IS NULL OR order_type IS NULL OR salesperson IS NULL;

CREATE INDEX idx_sales_order_date_id ON sales_order(order_date, id);
