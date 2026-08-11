package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderAllocationService {
    private final JdbcTemplate jdbc;
    private final InventoryAllocationService allocation;

    public OrderAllocationService(JdbcTemplate jdbc, InventoryAllocationService allocation) {
        this.jdbc = jdbc;
        this.allocation = allocation;
    }

    public Map<String, Object> view(long orderId) {
        Map<String, Object> order = jdbc.queryForMap("SELECT id,status,version FROM sales_order WHERE id=?", orderId);
        long warehouseId = defaultWarehouse();
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT i.line_no,s.sku_code,s.product_name,i.quantity,i.shipped_quantity,
                       i.locked_quantity,i.uncovered_quantity,
                       COALESCE(b.actual_quantity,0) AS actual_quantity,
                       COALESCE(b.actual_quantity-b.locked_quantity,0) AS available_quantity
                FROM sales_order_item i
                JOIN sku s ON s.id=i.sku_id
                LEFT JOIN inventory_balance b ON b.sku_id=i.sku_id AND b.warehouse_id=?
                WHERE i.sales_order_id=? ORDER BY i.line_no
                """, warehouseId, orderId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", orderId);
        result.put("version", number(order, "version"));
        result.put("status", value(order, "status"));
        result.put("adjustable", items.stream().allMatch(item -> number(item, "shipped_quantity") == 0));
        result.put("items", items.stream().map(this::allocationItem).toList());
        return result;
    }

    @Transactional
    public Map<String, Object> update(long orderId, OrderAllocationRequest request) {
        if (request == null || request.version() == null || request.items() == null) throw new IllegalArgumentException("缺少库存分配数据");
        Map<String, Object> order = jdbc.queryForMap("SELECT id,status,version FROM sales_order WHERE id=? FOR UPDATE", orderId);
        if (number(order, "version") != request.version()) throw new IllegalStateException("数据已被其他操作修改，请重新打开后再试");
        List<Map<String, Object>> lines = jdbc.queryForList("SELECT id,line_no,sku_id,quantity,shipped_quantity,locked_quantity FROM sales_order_item WHERE sales_order_id=? ORDER BY line_no FOR UPDATE", orderId);
        if (lines.stream().anyMatch(line -> number(line, "shipped_quantity") > 0)) throw new IllegalStateException("订单已有发货记录，不能调整库存分配");
        Map<Integer, OrderAllocationRequest.Item> targets;
        try {
            targets = request.items().stream().collect(Collectors.toMap(OrderAllocationRequest.Item::lineNo, Function.identity()));
        } catch (RuntimeException duplicate) {
            throw new IllegalArgumentException("订单明细行号重复");
        }
        Set<Integer> lineNumbers = lines.stream().map(line -> (int) number(line, "line_no")).collect(Collectors.toSet());
        if (!targets.keySet().equals(lineNumbers)) throw new IllegalArgumentException("库存分配明细与订单不一致");
        long warehouseId = defaultWarehouse();
        boolean ready = true;
        for (Map<String, Object> line : lines) {
            int lineNo = (int) number(line, "line_no");
            int quantity = (int) number(line, "quantity");
            int shipped = (int) number(line, "shipped_quantity");
            int current = (int) number(line, "locked_quantity");
            Integer requestedTarget = targets.get(lineNo).lockedQuantity();
            if (requestedTarget == null || requestedTarget < 0 || requestedTarget > quantity - shipped) throw new IllegalArgumentException("分配数量必须在 0 和未发货数量之间");
            long skuId = number(line, "sku_id");
            jdbc.update("INSERT INTO inventory_balance(warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity) VALUES(?,?,0,0,0) ON DUPLICATE KEY UPDATE sku_id=VALUES(sku_id)", warehouseId, skuId);
            Map<String, Object> balance = jdbc.queryForMap("SELECT id,actual_quantity,locked_quantity,in_transit_quantity FROM inventory_balance WHERE warehouse_id=? AND sku_id=? FOR UPDATE", warehouseId, skuId);
            int actual = (int) number(balance, "actual_quantity");
            int balanceLocked = (int) number(balance, "locked_quantity");
            int transit = (int) number(balance, "in_transit_quantity");
            int delta = requestedTarget - current;
            if (delta > 0 && delta > actual - balanceLocked) throw new IllegalArgumentException("可用库存不足，无法增加分配数量");
            if (delta != 0) {
                jdbc.update("UPDATE inventory_balance SET locked_quantity=locked_quantity+?,version=version+1 WHERE id=?", delta, number(balance, "id"));
                String type = delta > 0 ? "MANUAL_ALLOCATE" : "MANUAL_RELEASE";
                allocation.tx(warehouseId, skuId, type, "SALES_ORDER", String.valueOf(orderId), 0, delta, 0,
                        actual, actual, balanceLocked, balanceLocked + delta, transit, transit);
            }
            int uncovered = quantity - shipped - requestedTarget;
            jdbc.update("UPDATE sales_order_item SET locked_quantity=?,uncovered_quantity=?,version=version+1 WHERE id=?", requestedTarget, uncovered, number(line, "id"));
            if (uncovered > 0) ready = false;
        }
        jdbc.update("UPDATE sales_order SET status=?,version=version+1 WHERE id=?", ready ? "READY_TO_SHIP" : "WAITING_STOCK", orderId);
        return view(orderId);
    }

    private Map<String, Object> allocationItem(Map<String, Object> row) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("lineNo", number(row, "line_no"));
        item.put("skuCode", value(row, "sku_code"));
        item.put("productName", value(row, "product_name"));
        item.put("quantity", number(row, "quantity"));
        item.put("shippedQuantity", number(row, "shipped_quantity"));
        item.put("lockedQuantity", number(row, "locked_quantity"));
        item.put("uncoveredQuantity", number(row, "uncovered_quantity"));
        item.put("actualQuantity", number(row, "actual_quantity"));
        item.put("availableQuantity", number(row, "available_quantity"));
        return item;
    }

    private long defaultWarehouse() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM warehouse WHERE is_default=TRUE AND enabled=TRUE ORDER BY id LIMIT 1", Long.class));
    }
    private Object value(Map<String, Object> map, String key) { return map.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(key)).map(Map.Entry::getValue).findFirst().orElse(null); }
    private long number(Map<String, Object> map, String key) { return ((Number) Objects.requireNonNull(value(map, key), "缺少字段" + key)).longValue(); }
}
