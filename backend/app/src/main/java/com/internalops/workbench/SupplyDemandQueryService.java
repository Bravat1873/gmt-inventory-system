package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class SupplyDemandQueryService {
    private final JdbcTemplate jdbc;

    public SupplyDemandQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Long, SupplyDemandSnapshot> bySkuIds(Collection<Long> skuIds) {
        List<Long> ids = new LinkedHashSet<>(skuIds).stream().toList();
        if (ids.isEmpty()) return Map.of();

        Map<Long, MutableSnapshot> values = new LinkedHashMap<>();
        ids.forEach(id -> values.put(id, new MutableSnapshot()));
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));

        jdbc.queryForList("""
                SELECT b.sku_id,
                       COALESCE(SUM(b.actual_quantity),0) actual_quantity,
                       COALESCE(SUM(b.actual_quantity-b.locked_quantity),0) available_quantity,
                       COALESCE(SUM(b.in_transit_quantity),0) in_transit_quantity
                FROM inventory_balance b
                JOIN warehouse w ON w.id=b.warehouse_id
                WHERE w.is_default=TRUE AND w.enabled=TRUE
                  AND b.sku_id IN (%s)
                GROUP BY b.sku_id
                """.formatted(placeholders), ids.toArray()).forEach(row -> {
            MutableSnapshot value = values.get(number(row, "sku_id").longValue());
            value.actual = number(row, "actual_quantity").intValue();
            value.available = number(row, "available_quantity").intValue();
            value.inTransit = number(row, "in_transit_quantity").intValue();
        });

        jdbc.queryForList("""
                SELECT i.sku_id,
                       COALESCE(SUM(GREATEST(i.quantity-i.shipped_quantity,0)),0) pending_delivery_quantity
                FROM sales_order_item i
                JOIN sales_order o ON o.id=i.sales_order_id
                WHERE o.status<>'CANCELLED'
                  AND i.sku_id IN (%s)
                GROUP BY i.sku_id
                """.formatted(placeholders), ids.toArray()).forEach(row ->
                values.get(number(row, "sku_id").longValue()).pending =
                        number(row, "pending_delivery_quantity").intValue());

        Map<Long, SupplyDemandSnapshot> result = new LinkedHashMap<>();
        values.forEach((skuId, value) -> {
            int surplus = value.actual + value.inTransit - value.pending;
            result.put(skuId, new SupplyDemandSnapshot(
                    value.actual, value.available, value.inTransit, value.pending,
                    surplus, Math.max(-surplus, 0)));
        });
        return result;
    }

    private static Number number(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) return (Number) entry.getValue();
        }
        throw new IllegalArgumentException("缺少字段 " + key);
    }

    public record SupplyDemandSnapshot(
            int actualQuantity,
            int availableQuantity,
            int inTransitQuantity,
            int pendingDeliveryQuantity,
            int supplyDemandSurplus,
            int purchaseShortageQuantity) { }

    private static final class MutableSnapshot {
        private int actual;
        private int available;
        private int inTransit;
        private int pending;
    }
}


