ALTER TABLE sku
    ADD COLUMN sales_minimum_order_quantity INT NOT NULL DEFAULT 1 AFTER unit;

ALTER TABLE sku
    ADD CONSTRAINT chk_sku_sales_minimum_order_quantity
        CHECK (sales_minimum_order_quantity > 0);
