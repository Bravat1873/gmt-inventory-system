ALTER TABLE sku ADD COLUMN code_suffix TEXT NULL AFTER sku_code;
ALTER TABLE sku ADD COLUMN ean_code VARCHAR(12) NULL, ADD CONSTRAINT uk_sku_ean_code UNIQUE (ean_code);
ALTER TABLE purchase_order_item ADD COLUMN price_source VARCHAR(30) NULL AFTER purchase_price;
