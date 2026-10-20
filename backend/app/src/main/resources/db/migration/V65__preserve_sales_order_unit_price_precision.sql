-- Preserve fractional unit prices; order totals remain rounded to cents.
ALTER TABLE sales_order_item MODIFY COLUMN sale_price DECIMAL(24,8) NOT NULL;
