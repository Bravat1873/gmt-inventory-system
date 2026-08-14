CREATE TABLE sku_supplier_purchase_info (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    supplier_product_config_id BIGINT NOT NULL,
    purchase_price DECIMAL(18,4) NOT NULL,
    moq INT NOT NULL,
    lead_time_days INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT fk_purchase_info_supplier_product
        FOREIGN KEY (supplier_product_config_id) REFERENCES sku_supplier_config(id),
    CONSTRAINT ck_purchase_info_price CHECK (purchase_price >= 0),
    CONSTRAINT ck_purchase_info_moq CHECK (moq > 0),
    CONSTRAINT ck_purchase_info_lead_time CHECK (lead_time_days >= 0),
    INDEX idx_purchase_info_latest (supplier_product_config_id, enabled, updated_at, id)
);

INSERT INTO sku_supplier_purchase_info (
    supplier_product_config_id, purchase_price, moq, lead_time_days,
    enabled, created_at, updated_at
)
SELECT id, purchase_price, moq, lead_time_days, enabled, created_at, updated_at
FROM sku_supplier_config;

ALTER TABLE purchase_order_item
    ADD COLUMN supplier_purchase_info_id BIGINT NULL,
    ADD CONSTRAINT fk_purchase_item_supplier_info
        FOREIGN KEY (supplier_purchase_info_id) REFERENCES sku_supplier_purchase_info(id);

ALTER TABLE procurement_suggestion_item
    ADD COLUMN supplier_purchase_info_id BIGINT NULL,
    ADD CONSTRAINT fk_suggestion_item_supplier_info
        FOREIGN KEY (supplier_purchase_info_id) REFERENCES sku_supplier_purchase_info(id);
