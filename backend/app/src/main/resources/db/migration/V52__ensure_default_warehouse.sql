INSERT INTO warehouse (warehouse_code, warehouse_name, is_default, enabled)
SELECT 'DEFAULT', '默认仓库', TRUE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM warehouse WHERE is_default = TRUE);
