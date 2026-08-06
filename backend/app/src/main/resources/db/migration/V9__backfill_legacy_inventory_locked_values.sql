INSERT INTO inventory_locked_allocation(inventory_balance_id, lock_source, quantity)
SELECT b.id, '铭爱钧乔', CASE s.sku_code
    WHEN 'SXSEL_D51YZH70WGMS-A' THEN 3
    WHEN 'SXSEL_D51YZH70WGSE-A' THEN 10
    WHEN 'SXSEL_D61YZH70WGMS-A' THEN 135
END
FROM inventory_balance b
JOIN sku s ON s.id = b.sku_id
WHERE s.sku_code IN ('SXSEL_D51YZH70WGMS-A', 'SXSEL_D51YZH70WGSE-A', 'SXSEL_D61YZH70WGMS-A')
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);

INSERT INTO inventory_locked_allocation(inventory_balance_id, lock_source, quantity)
SELECT b.id, '博乐龙米', CASE s.sku_code
    WHEN 'G1BJYS0A01' THEN 100
    WHEN 'G1BJYS0A02' THEN 100
END
FROM inventory_balance b
JOIN sku s ON s.id = b.sku_id
WHERE s.sku_code IN ('G1BJYS0A01', 'G1BJYS0A02')
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);

INSERT INTO inventory_locked_allocation(inventory_balance_id, lock_source, quantity)
SELECT b.id, '贝朗', 12
FROM inventory_balance b
JOIN sku s ON s.id = b.sku_id
WHERE s.sku_code = 'BR_D51YZH7OWPSS'
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);

INSERT INTO inventory_locked_allocation(inventory_balance_id, lock_source, quantity)
SELECT b.id, '马来西亚', CASE s.sku_code
    WHEN 'G8P50HS003' THEN 21
    WHEN 'G8P50FG001' THEN 23
    WHEN 'G8P90FG001' THEN 9
    WHEN 'SXSEL_S70HGT70WPSE-A' THEN 12
    WHEN 'G8F50HS001' THEN 8
    WHEN 'SXSEL_F50YZH70WPSE-A' THEN 20
    WHEN 'SXSEL_D51YZH7068MS-A' THEN 12
END
FROM inventory_balance b
JOIN sku s ON s.id = b.sku_id
WHERE s.sku_code IN ('G8P50HS003', 'G8P50FG001', 'G8P90FG001', 'SXSEL_S70HGT70WPSE-A', 'G8F50HS001', 'SXSEL_F50YZH70WPSE-A', 'SXSEL_D51YZH7068MS-A')
ON DUPLICATE KEY UPDATE quantity = VALUES(quantity);
