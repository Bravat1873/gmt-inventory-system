-- 让系统生成的演示采购单可按“付款 → 到货入库”的正常流程继续操作。
-- 这三条演示单只在 V17 首次追加后写入一次在途库存与可追溯的库存流水。
INSERT INTO inventory_balance(
    warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity
)
SELECT
    w.id, poi.sku_id, 0, 0, poi.quantity
FROM purchase_order po
JOIN purchase_order_item poi ON poi.purchase_order_id=po.id
JOIN warehouse w ON w.is_default=TRUE AND w.enabled=TRUE
WHERE po.purchase_no LIKE 'DEMO-PO-%'
ON DUPLICATE KEY UPDATE
    in_transit_quantity=in_transit_quantity+VALUES(in_transit_quantity),
    version=inventory_balance.version+1;

INSERT INTO inventory_transaction(
    warehouse_id,sku_id,transaction_type,business_type,business_no,
    actual_delta,locked_delta,transit_delta,
    actual_before,actual_after,locked_before,locked_after,transit_before,transit_after,
    operated_by
)
SELECT
    b.warehouse_id, b.sku_id, 'PURCHASE_TRANSIT', 'PURCHASE_ORDER', po.purchase_no,
    0, 0, poi.quantity,
    b.actual_quantity, b.actual_quantity, b.locked_quantity, b.locked_quantity,
    b.in_transit_quantity-poi.quantity, b.in_transit_quantity,
    1
FROM purchase_order po
JOIN purchase_order_item poi ON poi.purchase_order_id=po.id
JOIN warehouse w ON w.is_default=TRUE AND w.enabled=TRUE
JOIN inventory_balance b ON b.warehouse_id=w.id AND b.sku_id=poi.sku_id
WHERE po.purchase_no LIKE 'DEMO-PO-%';
