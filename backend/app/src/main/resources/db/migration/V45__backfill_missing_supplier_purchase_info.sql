UPDATE sku_supplier_config
SET moq = COALESCE(moq, 1),
    lead_time_days = COALESCE(lead_time_days, 0)
WHERE purchase_price IS NOT NULL
  AND (moq IS NULL OR lead_time_days IS NULL);

UPDATE sku_supplier_purchase_info
SET moq = COALESCE(moq, 1),
    lead_time_days = COALESCE(lead_time_days, 0)
WHERE purchase_price IS NOT NULL
  AND (moq IS NULL OR lead_time_days IS NULL);