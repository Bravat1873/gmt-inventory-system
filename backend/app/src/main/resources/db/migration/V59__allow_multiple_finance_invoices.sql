SET @idx_exists = (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'sales_invoice'
    AND index_name = 'idx_sales_invoice_order'
);
SET @sql = IF(
  @idx_exists = 0,
  'ALTER TABLE sales_invoice ADD INDEX idx_sales_invoice_order (sales_order_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'sales_invoice'
    AND index_name = 'uk_invoice_order'
);
SET @sql = IF(
  @idx_exists > 0,
  'ALTER TABLE sales_invoice DROP INDEX uk_invoice_order',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'purchase_invoice'
    AND index_name = 'idx_purchase_invoice_purchase'
);
SET @sql = IF(
  @idx_exists = 0,
  'ALTER TABLE purchase_invoice ADD INDEX idx_purchase_invoice_purchase (purchase_order_id)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists = (
  SELECT COUNT(*)
  FROM information_schema.statistics
  WHERE table_schema = DATABASE()
    AND table_name = 'purchase_invoice'
    AND index_name = 'uk_purchase_invoice_purchase'
);
SET @sql = IF(
  @idx_exists > 0,
  'ALTER TABLE purchase_invoice DROP INDEX uk_purchase_invoice_purchase',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
