ALTER TABLE sku_supplier_config
    DROP INDEX uk_sku_supplier_config_sku,
    ADD CONSTRAINT uk_sku_supplier_config_sku_supplier UNIQUE (sku_id, supplier_id);
