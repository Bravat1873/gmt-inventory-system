-- 保留已有 STANLEY 规则的主键，避免破坏 sku.brand_rule_id 外键关联。
UPDATE product_code_rule
SET code = 'SXSEL', display_name = 'STANLEY', enabled = TRUE, updated_at = CURRENT_TIMESTAMP(3)
WHERE category = 'BRAND' AND display_name = 'STANLEY';

INSERT INTO product_code_rule(category, code, display_name, enabled, sort_order)
VALUES
    ('BRAND', 'SXSEL', 'STANLEY', TRUE, 10),
    ('BRAND', 'BR', 'BRAVAT', TRUE, 20),
    ('BRAND', 'G', 'GMT', TRUE, 30)
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    enabled = TRUE,
    sort_order = VALUES(sort_order),
    updated_at = CURRENT_TIMESTAMP(3);