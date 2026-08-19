ALTER TABLE sku DROP INDEX uk_sku_product_code;

ALTER TABLE sku ADD COLUMN business_unique_id VARCHAR(200) NULL AFTER product_code;

UPDATE sku
SET business_unique_id = CONCAT(
    UPPER(TRIM(product_code)),
    '::',
    UPPER(TRIM(customer_part_number))
)
WHERE NULLIF(TRIM(product_code), '') IS NOT NULL
  AND NULLIF(TRIM(customer_part_number), '') IS NOT NULL;

ALTER TABLE sku MODIFY business_unique_id VARCHAR(200) NOT NULL;
ALTER TABLE sku ADD CONSTRAINT uk_sku_business_unique_id UNIQUE (business_unique_id);
CREATE INDEX idx_sku_product_code ON sku(product_code);
