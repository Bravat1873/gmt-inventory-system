-- 演示采购数据：只在存在供应商与产品、且该演示单尚未生成时追加。
-- 后续可直接用真实采购单替换，不会影响客户、产品或库存原始数据。
INSERT INTO purchase_order(
    purchase_no,suggestion_id,manual_entry,supplier_id,status,total_amount,
    expected_arrival_date,purchase_remark,created_by
)
SELECT
    CONCAT('DEMO-PO-', LPAD(s.id, 4, '0')),
    NULL,
    TRUE,
    supplier_row.id,
    'PENDING_SUPPLIER_PAYMENT',
    ROUND(COALESCE(s.current_cost, 100) * 20, 2),
    DATE_ADD(CURDATE(), INTERVAL 7 DAY),
    '系统生成的模拟采购记录，可在后续接入真实供应商与产品数据后替换。',
    1
FROM (
    SELECT id, current_cost
    FROM sku
    WHERE enabled=TRUE
    ORDER BY id
    LIMIT 3
) s
CROSS JOIN (
    SELECT id
    FROM supplier
    WHERE enabled=TRUE
    ORDER BY id
    LIMIT 1
) supplier_row
WHERE NOT EXISTS (
    SELECT 1 FROM purchase_order existing_order
    WHERE existing_order.purchase_no=CONCAT('DEMO-PO-', LPAD(s.id, 4, '0'))
);

INSERT INTO purchase_order_item(
    purchase_order_id,line_no,sku_id,quantity,received_quantity,purchase_price
)
SELECT
    po.id,
    1,
    s.id,
    20,
    0,
    COALESCE(s.current_cost, 100)
FROM purchase_order po
JOIN sku s ON po.purchase_no=CONCAT('DEMO-PO-', LPAD(s.id, 4, '0'))
WHERE po.purchase_no LIKE 'DEMO-PO-%'
  AND NOT EXISTS (
      SELECT 1 FROM purchase_order_item poi
      WHERE poi.purchase_order_id=po.id AND poi.line_no=1
  );
