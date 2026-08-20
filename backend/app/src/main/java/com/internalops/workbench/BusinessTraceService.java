package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only business view that links the operational records created by the workflow. */
@Service
public class BusinessTraceService {
    private final JdbcTemplate jdbc;

    public BusinessTraceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> trace(String type, long id) {
        return switch (type) {
            case "order" -> orderTrace(id);
            case "purchase" -> purchaseTrace(id);
            default -> throw new IllegalArgumentException("不支持的业务查看类型");
        };
    }

    private Map<String, Object> orderTrace(long id) {
        Map<String, Object> header = one("SELECT o.id, o.order_no AS orderNo, c.customer_name AS counterparty, o.status, "
                + "o.total_amount AS totalAmount, o.order_date AS orderDate, o.salesperson, o.customer_contact AS customerContact, "
                + "o.customer_phone AS customerPhone, o.delivery_address AS deliveryAddress, o.delivery_contact AS deliveryContact, "
                + "o.delivery_phone AS deliveryPhone, o.shipping_method AS shippingMethod, o.carrier, o.tracking_no AS trackingNo, "
                + "o.created_at AS createdAt, o.updated_at AS updatedAt FROM sales_order o JOIN customer c ON c.id=o.customer_id WHERE o.id=?", id);
        List<Map<String, Object>> details = jdbc.queryForList("SELECT i.line_no AS lineNo, i.sku_id AS skuId, s.product_code AS productCode, s.customer_part_number AS customerPartNumber, s.product_name AS productName, s.model, s.configuration, s.unit, "
                + "i.quantity, i.shipped_quantity AS shippedQuantity, i.locked_quantity AS lockedQuantity, i.uncovered_quantity AS uncoveredQuantity, i.sale_price AS salePrice "
                + "FROM sales_order_item i JOIN sku s ON s.id=i.sku_id WHERE i.sales_order_id=? ORDER BY i.line_no", id);
        enrichOrderDetails(details);
        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(event(header.get("createdAt"), "订单创建", "销售订单 " + header.get("orderNo") + " 已创建", "order", id));
        jdbc.queryForList("SELECT amount, payment_method AS paymentMethod, received_at AS occurredAt FROM customer_receipt WHERE sales_order_id=?", id)
                .forEach(row -> timeline.add(event(row.get("occurredAt"), "客户收款", "已确认收款 ¥" + row.get("amount") + "（" + row.get("paymentMethod") + "）", null, null)));
        jdbc.queryForList("SELECT invoice_no AS invoiceNo, invoice_date AS occurredAt, tax_inclusive_amount AS amount FROM sales_invoice WHERE sales_order_id=?", id)
                .forEach(row -> timeline.add(event(row.get("occurredAt"), "销售发票", "发票号 " + row.get("invoiceNo") + "，金额 ¥" + row.get("amount"), null, null)));
        inventoryEvents(timeline, "SALES_ORDER", String.valueOf(id), "ALLOCATE", "库存锁定");
        inventoryEvents(timeline, "SALES_SHIPMENT", String.valueOf(id), "SALES_SHIPMENT", "订单发货出库");
        jdbc.queryForList("SELECT DISTINCT po.id, po.purchase_no AS purchaseNo, po.status, po.created_at AS occurredAt FROM shortage_coverage sc "
                        + "JOIN sales_order_item soi ON soi.id=sc.sales_order_item_id JOIN procurement_suggestion_item psi ON psi.id=sc.suggestion_item_id "
                        + "JOIN purchase_order po ON po.suggestion_id=psi.suggestion_id WHERE soi.sales_order_id=? AND sc.active=TRUE", id)
                .forEach(row -> timeline.add(event(row.get("occurredAt"), "关联采购", "采购单 " + row.get("purchaseNo") + "（" + status(row.get("status")) + "）", "purchase", number(row.get("id")))));
        sort(timeline);
        return result("order", "订单业务全景", header, details, timeline);
    }

    private Map<String, Object> purchaseTrace(long id) {
        Map<String, Object> header = one("SELECT po.id, po.purchase_no AS purchaseNo, sp.supplier_name AS counterparty, po.status, po.total_amount AS totalAmount, "
                + "po.expected_arrival_date AS expectedArrivalDate, po.created_at AS createdAt, po.updated_at AS updatedAt "
                + "FROM purchase_order po JOIN supplier sp ON sp.id=po.supplier_id WHERE po.id=?", id);
        List<Map<String, Object>> details = jdbc.queryForList("SELECT poi.line_no AS lineNo, s.product_code AS productCode, s.customer_part_number AS customerPartNumber, s.product_name AS productName, s.model, s.configuration, s.unit, "
                + "poi.quantity, poi.received_quantity AS receivedQuantity, poi.purchase_price AS purchasePrice "
                + "FROM purchase_order_item poi JOIN sku s ON s.id=poi.sku_id WHERE poi.purchase_order_id=? ORDER BY poi.line_no", id);
        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(event(header.get("createdAt"), "采购单创建", "采购单 " + header.get("purchaseNo") + " 已创建", "purchase", id));
        jdbc.queryForList("SELECT amount, payment_method AS paymentMethod, paid_at AS occurredAt FROM supplier_payment WHERE purchase_order_id=?", id)
                .forEach(row -> timeline.add(event(row.get("occurredAt"), "供应商付款", "已登记付款 ¥" + row.get("amount") + "（" + row.get("paymentMethod") + "）", null, null)));
        jdbc.queryForList("SELECT receipt_no AS receiptNo, received_at AS occurredAt, status FROM goods_receipt WHERE purchase_order_id=?", id)
                .forEach(row -> timeline.add(event(row.get("occurredAt"), "采购到货入库", "入库单 " + row.get("receiptNo") + "（" + status(row.get("status")) + "）", null, null)));
        inventoryEvents(timeline, "PURCHASE_ORDER", String.valueOf(header.get("purchaseNo")), "PURCHASE_TRANSIT", "采购在途");
        inventoryEvents(timeline, "PURCHASE_ORDER", String.valueOf(header.get("purchaseNo")), "PURCHASE_RECEIPT", "采购入库");
        jdbc.queryForList("SELECT DISTINCT o.id, o.order_no AS orderNo, o.status, o.created_at AS occurredAt FROM purchase_order po "
                        + "JOIN procurement_suggestion_item psi ON psi.suggestion_id=po.suggestion_id JOIN shortage_coverage sc ON sc.suggestion_item_id=psi.id "
                        + "JOIN sales_order_item soi ON soi.id=sc.sales_order_item_id JOIN sales_order o ON o.id=soi.sales_order_id WHERE po.id=? AND sc.active=TRUE", id)
                .forEach(row -> timeline.add(event(row.get("occurredAt"), "关联销售订单", "订单 " + row.get("orderNo") + "（" + status(row.get("status")) + "）", "order", number(row.get("id")))));
        sort(timeline);
        return result("purchase", "采购业务全景", header, details, timeline);
    }

    private void inventoryEvents(List<Map<String, Object>> timeline, String businessType, String businessNo, String transactionType, String title) {
        jdbc.queryForList("SELECT t.operated_at AS occurredAt, t.actual_delta AS actualDelta, t.locked_delta AS lockedDelta, t.transit_delta AS transitDelta, s.customer_part_number AS customerPartNumber, s.product_name AS productName "
                        + "FROM inventory_transaction t JOIN sku s ON s.id=t.sku_id WHERE t.business_type=? AND t.business_no=? AND t.transaction_type=? ORDER BY t.operated_at", businessType, businessNo, transactionType)
                .forEach(row -> timeline.add(event(row.get("occurredAt"), title, "物料 " + row.get("customerPartNumber") + " " + row.get("productName") + "；实际 " + signed(row.get("actualDelta")) + "，锁定 " + signed(row.get("lockedDelta")) + "，在途 " + signed(row.get("transitDelta")), null, null)));
    }

    private void enrichOrderDetails(List<Map<String, Object>> details) {
        for (Map<String, Object> detail : details) {
            int quantity = number(detail.get("quantity")).intValue();
            int shipped = number(detail.get("shippedQuantity")).intValue();
            List<Map<String, Object>> balances = jdbc.queryForList("""
                    SELECT actual_quantity,in_transit_quantity,actual_quantity-locked_quantity AS available_quantity
                    FROM inventory_balance
                    WHERE sku_id=? AND warehouse_id=(
                        SELECT id FROM warehouse WHERE is_default=TRUE AND enabled=TRUE ORDER BY id LIMIT 1
                    )
                    """, detail.get("skuId"));
            Map<String, Object> balance = balances.isEmpty() ? Map.of() : balances.get(0);
            detail.put("remainingQuantity", quantity - shipped);
            detail.put("actualQuantity", balance.getOrDefault("actual_quantity", 0));
            detail.put("inTransitQuantity", balance.getOrDefault("in_transit_quantity", 0));
            detail.put("pendingDeliveryQuantity", jdbc.queryForObject("""
                    SELECT COALESCE(SUM(GREATEST(i.quantity-i.shipped_quantity, 0)), 0)
                    FROM sales_order_item i
                    JOIN sales_order o ON o.id=i.sales_order_id
                    WHERE i.sku_id=? AND o.status<>'CANCELLED'
                    """, Long.class, detail.get("skuId")));
            detail.put("availableQuantity", balance.getOrDefault("available_quantity", 0));
        }
    }

    private Map<String, Object> one(String sql, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("未找到对应业务记录");
        return rows.get(0);
    }
    private Map<String, Object> result(String type, String title, Map<String, Object> header, List<Map<String, Object>> details, List<Map<String, Object>> timeline) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type); result.put("title", title); result.put("header", header); result.put("details", details); result.put("timeline", timeline); return result;
    }
    private Map<String, Object> event(Object occurredAt, String title, String description, String linkType, Long linkId) {
        Map<String, Object> item = new LinkedHashMap<>(); item.put("occurredAt", occurredAt); item.put("title", title); item.put("description", description); item.put("linkType", linkType); item.put("linkId", linkId); return item;
    }
    private void sort(List<Map<String, Object>> rows) { rows.sort(Comparator.comparing(row -> String.valueOf(row.get("occurredAt")), Comparator.nullsLast(String::compareTo))); }
    private Long number(Object value) { return value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value)); }
    private String signed(Object value) { if (value == null || "0".equals(String.valueOf(value))) return "0"; return number(value) > 0 ? "+" + value : String.valueOf(value); }
    private String status(Object value) {
        return switch (String.valueOf(value)) {
            case "DRAFT" -> "待确认建议";
            case "PENDING_CUSTOMER_PAYMENT" -> "待确认收款";
            case "PENDING_SALES_INVOICE" -> "待开销售发票";
            case "WAITING_STOCK" -> "等待齐货";
            case "READY_TO_SHIP" -> "等待发货";
            case "SHIPPED" -> "已发货";
            case "PENDING_SUPPLIER_PAYMENT" -> "待登记付款";
            case "EXECUTING" -> "采购执行中";
            case "RECEIVED" -> "已入库";
            default -> String.valueOf(value);
        };
    }
}
