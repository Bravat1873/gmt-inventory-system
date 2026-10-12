package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads current sales requirements through the purchase's actual shortage coverage. */
final class PurchaseSalesRequirements {
    private PurchaseSalesRequirements() {}

    static void enrich(JdbcTemplate jdbc, long purchaseId, Map<String, Object> header,
                       List<Map<String, Object>> items) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DISTINCT o.id,o.order_no,o.delivery_contact,o.delivery_phone,o.delivery_address,
                       o.order_remark,soi.id AS sales_item_id,soi.line_no,soi.sku_id,soi.item_remark
                FROM purchase_order po
                JOIN procurement_suggestion_item psi ON psi.suggestion_id=po.suggestion_id
                JOIN purchase_order_item poi ON poi.purchase_order_id=po.id AND poi.sku_id=psi.sku_id
                JOIN shortage_coverage sc ON sc.suggestion_item_id=psi.id
                JOIN sales_order_item soi ON soi.id=sc.sales_order_item_id AND soi.sku_id=poi.sku_id
                JOIN sales_order o ON o.id=soi.sales_order_id
                WHERE po.id=? AND poi.quantity>0 AND sc.active=TRUE AND sc.covered_quantity>0
                ORDER BY o.order_no,soi.line_no,soi.id
                """, purchaseId);
        Set<String> contacts = new LinkedHashSet<>();
        Set<String> addresses = new LinkedHashSet<>();
        Set<String> remarks = new LinkedHashSet<>();
        Map<Long, Set<String>> lineRemarks = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String prefix = text(row, "order_no") + "：";
            String contact = text(row, "delivery_contact");
            String phone = text(row, "delivery_phone");
            String contactAndPhone = contact.isBlank() ? phone : phone.isBlank() ? contact : contact + " / " + phone;
            if (!contactAndPhone.isBlank()) contacts.add(prefix + contactAndPhone);
            if (!text(row, "delivery_address").isBlank()) addresses.add(prefix + text(row, "delivery_address"));
            if (!text(row, "order_remark").isBlank()) remarks.add(prefix + text(row, "order_remark"));
            if (!text(row, "item_remark").isBlank()) {
                long skuId = ((Number) row.get("sku_id")).longValue();
                lineRemarks.computeIfAbsent(skuId, key -> new LinkedHashSet<>())
                        .add(text(row, "order_no") + " 第" + row.get("line_no") + "行：" + text(row, "item_remark"));
            }
        }
        header.put("salesDeliveryContact", String.join("\n", contacts));
        header.put("salesDeliveryAddress", String.join("\n", addresses));
        header.put("salesOrderRemark", String.join("\n", remarks));
        for (Map<String, Object> item : items) {
            long skuId = ((Number) item.get("skuId")).longValue();
            item.put("remark", String.join("\n", lineRemarks.getOrDefault(skuId, Set.of())));
        }
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? "" : value.toString();
    }
}
