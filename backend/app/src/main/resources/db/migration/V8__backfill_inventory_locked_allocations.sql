INSERT INTO inventory_locked_allocation(inventory_balance_id, lock_source, quantity)
SELECT b.id, '铭爱钧乔', COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(r.normalized_data, '$.lockedBreakdown1')) AS UNSIGNED), 0)
FROM import_batch ib
JOIN import_row r ON r.batch_id = ib.id AND r.row_status = 'VALID'
JOIN sku s ON s.sku_code = JSON_UNQUOTE(JSON_EXTRACT(r.normalized_data, '$.skuCode'))
JOIN inventory_balance b ON b.sku_id = s.id
WHERE ib.import_type = 'INVENTORY' AND ib.status = 'COMMITTED'
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);

INSERT INTO inventory_locked_allocation(inventory_balance_id, lock_source, quantity)
SELECT b.id, '博乐龙米', COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(r.normalized_data, '$.lockedBreakdown2')) AS UNSIGNED), 0)
FROM import_batch ib
JOIN import_row r ON r.batch_id = ib.id AND r.row_status = 'VALID'
JOIN sku s ON s.sku_code = JSON_UNQUOTE(JSON_EXTRACT(r.normalized_data, '$.skuCode'))
JOIN inventory_balance b ON b.sku_id = s.id
WHERE ib.import_type = 'INVENTORY' AND ib.status = 'COMMITTED'
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);

INSERT INTO inventory_locked_allocation(inventory_balance_id, lock_source, quantity)
SELECT b.id, '老挝', COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(r.normalized_data, '$.lockedBreakdown3')) AS UNSIGNED), 0)
FROM import_batch ib
JOIN import_row r ON r.batch_id = ib.id AND r.row_status = 'VALID'
JOIN sku s ON s.sku_code = JSON_UNQUOTE(JSON_EXTRACT(r.normalized_data, '$.skuCode'))
JOIN inventory_balance b ON b.sku_id = s.id
WHERE ib.import_type = 'INVENTORY' AND ib.status = 'COMMITTED'
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);

INSERT INTO inventory_locked_allocation(inventory_balance_id, lock_source, quantity)
SELECT b.id, '贝朗', COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(r.normalized_data, '$.lockedBreakdown4')) AS UNSIGNED), 0)
FROM import_batch ib
JOIN import_row r ON r.batch_id = ib.id AND r.row_status = 'VALID'
JOIN sku s ON s.sku_code = JSON_UNQUOTE(JSON_EXTRACT(r.normalized_data, '$.skuCode'))
JOIN inventory_balance b ON b.sku_id = s.id
WHERE ib.import_type = 'INVENTORY' AND ib.status = 'COMMITTED'
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);

INSERT INTO inventory_locked_allocation(inventory_balance_id, lock_source, quantity)
SELECT b.id, '马来西亚', COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(r.normalized_data, '$.lockedBreakdown5')) AS UNSIGNED), 0)
FROM import_batch ib
JOIN import_row r ON r.batch_id = ib.id AND r.row_status = 'VALID'
JOIN sku s ON s.sku_code = JSON_UNQUOTE(JSON_EXTRACT(r.normalized_data, '$.skuCode'))
JOIN inventory_balance b ON b.sku_id = s.id
WHERE ib.import_type = 'INVENTORY' AND ib.status = 'COMMITTED'
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);
