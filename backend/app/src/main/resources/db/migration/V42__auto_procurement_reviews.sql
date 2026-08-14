ALTER TABLE sku_supplier_config
    DROP INDEX uk_sku_supplier_config_sku,
    ADD CONSTRAINT uk_sku_supplier_config_pair UNIQUE (sku_id, supplier_id);

ALTER TABLE procurement_suggestion
    ADD COLUMN system_managed BOOLEAN NOT NULL DEFAULT FALSE AFTER status,
    ADD COLUMN manually_edited BOOLEAN NOT NULL DEFAULT FALSE AFTER system_managed,
    ADD COLUMN review_reason VARCHAR(500) NULL AFTER manually_edited,
    ADD UNIQUE INDEX uk_procurement_suggestion_no (suggestion_no);

ALTER TABLE procurement_suggestion_item
    ADD COLUMN minimum_order_quantity INT NULL AFTER shortage_quantity,
    ADD COLUMN estimated_amount DECIMAL(18,2) NULL AFTER purchase_price,
    ADD COLUMN source_balance_version INT NULL AFTER estimated_amount,
    ADD INDEX idx_suggestion_item_sku_active (sku_id, suggestion_id);
