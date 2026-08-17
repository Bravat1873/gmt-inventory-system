ALTER TABLE sku_supplier_config
    MODIFY purchase_price DECIMAL(18,4) NULL,
    MODIFY moq INT NULL,
    MODIFY lead_time_days INT NULL DEFAULT NULL;

ALTER TABLE sku_supplier_purchase_info
    MODIFY purchase_price DECIMAL(18,4) NULL,
    MODIFY moq INT NULL,
    MODIFY lead_time_days INT NULL DEFAULT NULL;
