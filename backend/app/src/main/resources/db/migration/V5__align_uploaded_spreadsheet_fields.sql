ALTER TABLE sku
    ADD COLUMN factory_price DECIMAL(18,4) NULL AFTER current_cost,
    ADD COLUMN product_remark VARCHAR(500) NULL AFTER factory_price;

ALTER TABLE inventory_balance
    ADD COLUMN source_supplier_name VARCHAR(200) NULL AFTER in_transit_quantity,
    ADD COLUMN inventory_remark VARCHAR(500) NULL AFTER source_supplier_name;
