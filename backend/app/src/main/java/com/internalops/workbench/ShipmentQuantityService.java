package com.internalops.workbench;

import com.internalops.auth.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Applies only the delta between the previous and requested cumulative shipment quantity. */
@Service
public class ShipmentQuantityService {
    private final JdbcTemplate jdbc;
    private final InventoryAllocationService allocation;

    public ShipmentQuantityService(JdbcTemplate jdbc, InventoryAllocationService allocation) {
        this.jdbc = jdbc;
        this.allocation = allocation;
    }

    @Transactional
    public Map<String, Object> update(long orderId, ShipmentQuantityRequest request) {
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new IllegalArgumentException("请至少填写一条订单明细的已发货数量");
        }
        String status = jdbc.queryForObject("SELECT status FROM sales_order WHERE id=? FOR UPDATE", String.class, orderId);
        if ("DRAFT".equals(status) || "SHIPPED".equals(status)) {
            throw new IllegalStateException("当前订单不能调整发货数量");
        }
        Map<Integer, Integer> requested = new HashMap<>();
        for (ShipmentQuantityRequest.Item item : request.items()) {
            if (item.lineNo() == null || item.shippedQuantity() == null || item.shippedQuantity() < 0) {
                throw new IllegalArgumentException("已发货数量必须是非负整数");
            }
            if (requested.put(item.lineNo(), item.shippedQuantity()) != null) {
                throw new IllegalArgumentException("订单明细行号重复");
            }
        }
        List<Map<String, Object>> lines = jdbc.queryForList("SELECT i.id,i.line_no,i.sku_id,s.sku_code,s.product_name,i.quantity,i.shipped_quantity,i.locked_quantity "
                + "FROM sales_order_item i JOIN sku s ON s.id=i.sku_id WHERE i.sales_order_id=? ORDER BY i.line_no FOR UPDATE", orderId);
        if (lines.isEmpty()) throw new IllegalArgumentException("订单没有可发货明细");
        List<ShipmentDelta> positiveDeltas = new ArrayList<>();
        for (Map<String, Object> line : lines) {
            int lineNo = (int) InventoryAllocationService.num(line, "line_no");
            int ordered = (int) InventoryAllocationService.num(line, "quantity");
            int current = (int) InventoryAllocationService.num(line, "shipped_quantity");
            int target = requested.getOrDefault(lineNo, current);
            if (target > ordered) throw new IllegalArgumentException("第 " + lineNo + " 行的已发货数量不能超过订单数量");
            int delta = target - current;
            if (delta > 0) positiveDeltas.add(new ShipmentDelta(InventoryAllocationService.num(line, "id"), delta));
        }
        String deliveryAddress = request.deliveryAddress() == null ? null : request.deliveryAddress().strip();
        if (!positiveDeltas.isEmpty()) {
            if (deliveryAddress == null || deliveryAddress.isBlank()) throw new IllegalArgumentException("发货地址不能为空");
            if (deliveryAddress.length() > 500) throw new IllegalArgumentException("发货地址不能超过500个字符");
        }
        long warehouseId = Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM warehouse WHERE is_default=TRUE AND enabled=TRUE ORDER BY id LIMIT 1", Long.class));
        for (Map<String, Object> line : lines) {
            long lineId = InventoryAllocationService.num(line, "id");
            int lineNo = (int) InventoryAllocationService.num(line, "line_no");
            int ordered = (int) InventoryAllocationService.num(line, "quantity");
            int current = (int) InventoryAllocationService.num(line, "shipped_quantity");
            int target = requested.getOrDefault(lineNo, current);
            int delta = target - current;
            int locked = (int) InventoryAllocationService.num(line, "locked_quantity");
            int newLocked = locked - delta;
            if (delta != 0) {
                long skuId = InventoryAllocationService.num(line, "sku_id");
                Map<String, Object> balance = jdbc.queryForMap("SELECT id,actual_quantity,locked_quantity,in_transit_quantity FROM inventory_balance WHERE warehouse_id=? AND sku_id=? FOR UPDATE", warehouseId, skuId);
                int actual = (int) InventoryAllocationService.num(balance, "actual_quantity");
                int balanceLocked = (int) InventoryAllocationService.num(balance, "locked_quantity");
                int transit = (int) InventoryAllocationService.num(balance, "in_transit_quantity");
                if (delta > 0 && (balanceLocked < delta || actual < delta || locked < delta)) {
                    String skuCode = String.valueOf(line.getOrDefault("sku_code", ""));
                    String productName = String.valueOf(line.getOrDefault("product_name", ""));
                    String material = !skuCode.isBlank() && !"null".equals(skuCode) ? skuCode
                            : (!productName.isBlank() && !"null".equals(productName) ? productName : "该物料");
                    throw new IllegalStateException("物料 " + material + " 本单已备货 " + locked
                            + "，本次最多可增加发货 " + Math.max(0, locked)
                            + "；请先完成采购到货或补充库存");
                }
                jdbc.update("UPDATE inventory_balance SET actual_quantity=actual_quantity-?, locked_quantity=locked_quantity-?, version=version+1 WHERE id=?", delta, delta, InventoryAllocationService.num(balance, "id"));
                allocation.tx(warehouseId, skuId, "SALES_SHIPMENT", "SALES_ORDER", String.valueOf(orderId), -delta, -delta, 0,
                        actual, actual - delta, balanceLocked, balanceLocked - delta, transit, transit);
            }
            int uncovered = Math.max(0, ordered - target - newLocked);
            jdbc.update("UPDATE sales_order_item SET shipped_quantity=?, uncovered_quantity=?, version=version+1 WHERE id=?", target, uncovered, lineId);
        }
        if (!positiveDeltas.isEmpty()) {
            long shipmentId = insertShipment(orderId, deliveryAddress, currentOperatorName(), trimToNull(request.remark()));
            for (ShipmentDelta delta : positiveDeltas) {
                jdbc.update("INSERT INTO sales_shipment_item(sales_shipment_id,sales_order_item_id,quantity) VALUES(?,?,?)",
                        shipmentId, delta.salesOrderItemId(), delta.quantity());
            }
        }
        Integer remaining = jdbc.queryForObject("SELECT COALESCE(SUM(quantity-shipped_quantity),0) FROM sales_order_item WHERE sales_order_id=?", Integer.class, orderId);
        Integer uncovered = jdbc.queryForObject("SELECT COALESCE(SUM(uncovered_quantity),0) FROM sales_order_item WHERE sales_order_id=?", Integer.class, orderId);
        String nextStatus = remaining != null && remaining == 0 ? "SHIPPED" : (uncovered != null && uncovered > 0 ? "WAITING_STOCK" : "READY_TO_SHIP");
        jdbc.update("UPDATE sales_order SET status=?, shipped_at=?, version=version+1 WHERE id=?", nextStatus, "SHIPPED".equals(nextStatus) ? LocalDateTime.now() : null, orderId);
        return Map.of("id", orderId, "status", nextStatus, "remainingQuantity", remaining == null ? 0 : remaining);
    }

    private long insertShipment(long orderId, String deliveryAddress, String operatorName, String remark) {
        String shipmentNo = "SH" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sales_shipment(shipment_no,sales_order_id,delivery_address,operator_name,shipped_at,remark) VALUES(?,?,?,?,?,?)",
                    new String[]{"id"});
            statement.setString(1, shipmentNo);
            statement.setLong(2, orderId);
            statement.setString(3, deliveryAddress);
            statement.setString(4, operatorName);
            statement.setObject(5, LocalDateTime.now());
            statement.setString(6, remark);
            return statement;
        }, keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private String currentOperatorName() {
        CurrentUser user = CurrentUser.get();
        return user == null ? "system" : user.displayName();
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private record ShipmentDelta(long salesOrderItemId, int quantity) {}
}
