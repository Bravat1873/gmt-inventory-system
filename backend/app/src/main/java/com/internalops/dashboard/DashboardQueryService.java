package com.internalops.dashboard;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardQueryService {
    private final JdbcTemplate jdbc;
    public DashboardQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public DashboardSummary summary() {
        return new DashboardSummary(
                count("SELECT COUNT(*) FROM sales_order WHERE status='PENDING_CUSTOMER_PAYMENT'"),
                count("SELECT COUNT(*) FROM sales_order WHERE status IN ('PENDING_STOCK','WAITING_STOCK')"),
                count("SELECT COUNT(*) FROM purchase_order WHERE status='PENDING_SUPPLIER_PAYMENT'"),
                count("SELECT COUNT(*) FROM sales_order WHERE status='READY_TO_SHIP'"));
    }

    public DashboardSnapshot snapshot(int requestedDays) {
        int days = requestedDays == 7 || requestedDays == 90 ? requestedDays : 30;
        LocalDate start = LocalDate.now().minusDays(days - 1L);
        List<ProductBalance> balances = productBalances();
        long shortageProducts = balances.stream().filter(value -> value.surplus() < 0).count();
        long shortageQuantity = balances.stream().mapToLong(value -> Math.max(-value.surplus(), 0)).sum();
        long actualInventory = balances.stream().mapToLong(ProductBalance::actual).sum();
        long lockedInventory = balances.stream().mapToLong(ProductBalance::locked).sum();
        long inTransitInventory = balances.stream().mapToLong(ProductBalance::inTransit).sum();
        long pendingDeliveryQuantity = balances.stream().mapToLong(ProductBalance::pending).sum();
        long supplyDemandSurplus = balances.stream().mapToLong(ProductBalance::surplus).sum();
        return new DashboardSnapshot(
                count("SELECT COUNT(*) FROM sales_order WHERE order_date=CURRENT_DATE"),
                decimal("SELECT COALESCE(SUM(total_amount),0) FROM sales_order WHERE order_date=CURRENT_DATE"),
                count("SELECT COUNT(*) FROM sales_order WHERE status='PENDING_CUSTOMER_PAYMENT'"),
                count("SELECT COUNT(*) FROM sales_order WHERE status IN ('PENDING_STOCK','WAITING_STOCK')"),
                count("SELECT COUNT(*) FROM sales_order WHERE status='READY_TO_SHIP'"),
                shortageProducts, shortageQuantity,
                count("SELECT COUNT(*) FROM purchase_order WHERE status='PENDING_SUPPLIER_PAYMENT'"),
                count("SELECT COUNT(*) FROM after_sales_order WHERE status NOT IN ('COMPLETED','CANCELLED')"),
                days, actualInventory, lockedInventory, inTransitInventory, pendingDeliveryQuantity, supplyDemandSurplus,
                trends(start, days), productExceptions(balances), Instant.now());
    }

    private List<DashboardSnapshot.TrendPoint> trends(LocalDate start, int days) {
        Map<LocalDate, long[]> counts = new HashMap<>();
        Map<LocalDate, BigDecimal> amounts = new HashMap<>();
        jdbc.query("SELECT order_date d, COUNT(*) c, COALESCE(SUM(total_amount),0) amount FROM sales_order WHERE order_date>=? GROUP BY order_date", (RowCallbackHandler) rs -> {
            LocalDate date = rs.getDate("d").toLocalDate();
            counts.computeIfAbsent(date, ignored -> new long[4])[0] = rs.getLong("c");
            amounts.put(date, rs.getBigDecimal("amount"));
        }, Date.valueOf(start));
        jdbc.query("SELECT CAST(po.created_at AS DATE) d, COALESCE(SUM(poi.quantity),0) c FROM purchase_order po JOIN purchase_order_item poi ON poi.purchase_order_id=po.id WHERE po.created_at>=? GROUP BY CAST(po.created_at AS DATE)", (RowCallbackHandler) rs -> counts.computeIfAbsent(rs.getDate("d").toLocalDate(), ignored -> new long[4])[1] = rs.getLong("c"), Date.valueOf(start));
        jdbc.query("SELECT CAST(ss.shipped_at AS DATE) d, COALESCE(SUM(ssi.quantity),0) c FROM sales_shipment ss JOIN sales_shipment_item ssi ON ssi.sales_shipment_id=ss.id WHERE ss.shipped_at>=? GROUP BY CAST(ss.shipped_at AS DATE)", (RowCallbackHandler) rs -> counts.computeIfAbsent(rs.getDate("d").toLocalDate(), ignored -> new long[4])[2] = rs.getLong("c"), Date.valueOf(start));
        jdbc.query("SELECT application_date d, COUNT(*) c FROM after_sales_order WHERE application_date>=? GROUP BY application_date", (RowCallbackHandler) rs -> counts.computeIfAbsent(rs.getDate("d").toLocalDate(), ignored -> new long[4])[3] = rs.getLong("c"), Date.valueOf(start));
        List<DashboardSnapshot.TrendPoint> result = new ArrayList<>();
        for (int index = 0; index < days; index++) {
            LocalDate date = start.plusDays(index); long[] values = counts.getOrDefault(date, new long[4]);
            result.add(new DashboardSnapshot.TrendPoint(date, values[0], amounts.getOrDefault(date, BigDecimal.ZERO), values[1], values[2], values[3]));
        }
        return result;
    }

    private List<ProductBalance> productBalances() {
        String sql = """
                SELECT s.id, s.customer_part_number, s.model, s.product_code,
+                       COALESCE(SUM(ib.actual_quantity),0) actual_quantity,
+                       COALESCE(SUM(ib.locked_quantity),0) locked_quantity,
+                       COALESCE(SUM(ib.in_transit_quantity),0) in_transit_quantity,
+                       COALESCE((SELECT SUM(soi.quantity-soi.shipped_quantity) FROM sales_order_item soi JOIN sales_order so ON so.id=soi.sales_order_id WHERE soi.sku_id=s.id AND so.status<>'SHIPPED'),0) pending_quantity,
+                       COALESCE((SELECT COUNT(DISTINCT soi.sales_order_id) FROM sales_order_item soi JOIN sales_order so ON so.id=soi.sales_order_id WHERE soi.sku_id=s.id AND so.status<>'SHIPPED'),0) business_count
+                FROM sku s LEFT JOIN inventory_balance ib ON ib.sku_id=s.id WHERE s.enabled=TRUE
+                GROUP BY s.id,s.customer_part_number,s.model,s.product_code
+                """.replace("+", "");
        return jdbc.query(sql, (rs, row) -> new ProductBalance(rs.getLong("id"), rs.getString("customer_part_number"), rs.getString("model"), rs.getString("product_code"), rs.getLong("actual_quantity"), rs.getLong("locked_quantity"), rs.getLong("in_transit_quantity"), rs.getLong("pending_quantity"), rs.getLong("business_count")));
    }

    private List<DashboardSnapshot.ProductException> productExceptions(List<ProductBalance> balances) {
        List<DashboardSnapshot.ProductException> result = new ArrayList<>();
        for (ProductBalance value : balances) {
            if (value.surplus() < 0) result.add(value.toException("DEMAND"));
            if (value.pending() > 0 && value.actual() - value.locked() <= 0) result.add(value.toException("INVENTORY"));
            if (value.pending() > 0 && value.inTransit() > 0) result.add(value.toException("SUPPLY"));
        }
        return result.stream().sorted((left, right) -> Long.compare(right.shortageQuantity(), left.shortageQuantity())).limit(30).toList();
    }

    private BigDecimal decimal(String sql) { BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class); return value == null ? BigDecimal.ZERO : value; }
    private long count(String sql) { Long value = jdbc.queryForObject(sql, Long.class); return value == null ? 0 : value; }

    private record ProductBalance(long id, String customerPartNumber, String model, String productCode, long actual, long locked, long inTransit, long pending, long businessCount) {
        long surplus() { return actual + inTransit - pending; }
        DashboardSnapshot.ProductException toException(String category) {
            return new DashboardSnapshot.ProductException(id, category, customerPartNumber, model, productCode, actual, inTransit, pending, surplus(), Math.max(-surplus(), 0), businessCount);
        }
    }
}







