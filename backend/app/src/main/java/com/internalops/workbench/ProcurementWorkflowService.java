package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProcurementWorkflowService {
    private static final BigDecimal MAX_PURCHASE_PRICE = new BigDecimal("99999999999999.9999");
    private static final BigDecimal MAX_PURCHASE_TOTAL = new BigDecimal("9999999999999999.99");

    private final JdbcTemplate jdbc;
    private final InventoryAllocationService allocation;

    public ProcurementWorkflowService(JdbcTemplate jdbc, InventoryAllocationService allocation) {
        this.jdbc = jdbc;
        this.allocation = allocation;
    }

    @Transactional
    public Map<String, Object> generate() {
        var missing = jdbc.queryForList("""
                SELECT i.id AS item_id, i.sku_id,
                       i.uncovered_quantity-COALESCE(c.covered_quantity,0) AS uncovered_quantity,
                       ssc.supplier_id, ssc.purchase_price, ssc.moq, ssc.lead_time_days
                FROM sales_order_item i
                JOIN sales_order o ON o.id=i.sales_order_id
                LEFT JOIN (
                    SELECT sales_order_item_id,SUM(covered_quantity) AS covered_quantity
                    FROM shortage_coverage WHERE active=TRUE GROUP BY sales_order_item_id
                ) c ON c.sales_order_item_id=i.id
                LEFT JOIN sku_supplier_config ssc ON ssc.sku_id=i.sku_id AND ssc.enabled=TRUE
                WHERE o.status='WAITING_STOCK'
                  AND i.uncovered_quantity-COALESCE(c.covered_quantity,0)>0
                ORDER BY ssc.supplier_id,i.sku_id,i.id
                """);
        if (missing.isEmpty()) {
            throw new IllegalStateException("当前没有需要采购的缺口");
        }
        for (var row : missing) {
            if (val(row, "supplier_id") == null) {
                throw new IllegalStateException("存在未绑定供应商的缺货产品，请先完善产品资料");
            }
        }

        Map<Long, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (var row : missing) {
            groups.computeIfAbsent(num(row, "supplier_id"), ignored -> new ArrayList<>()).add(row);
        }

        List<Long> suggestions = new ArrayList<>();
        for (var group : groups.entrySet()) {
            String stamp = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            long suggestionId = insert(
                    "INSERT INTO procurement_suggestion(suggestion_no,status,created_by) VALUES(?,'DRAFT',1)",
                    "PS" + stamp);

            Map<Long, List<Map<String, Object>>> bySku = new LinkedHashMap<>();
            for (var row : group.getValue()) {
                bySku.computeIfAbsent(num(row, "sku_id"), ignored -> new ArrayList<>()).add(row);
            }
            for (var sku : bySku.entrySet()) {
                int shortage = sku.getValue().stream()
                        .mapToInt(row -> (int) num(row, "uncovered_quantity"))
                        .sum();
                var first = sku.getValue().get(0);
                int moq = (int) num(first, "moq");
                int quantity = Math.max(shortage, moq);
                BigDecimal price = (BigDecimal) val(first, "purchase_price");
                LocalDate eta = LocalDate.now().plusDays((int) num(first, "lead_time_days"));
                long suggestionItemId = insert("""
                                INSERT INTO procurement_suggestion_item(
                                    suggestion_id,sku_id,supplier_id,shortage_quantity,suggested_quantity,
                                    confirmed_quantity,purchase_price,expected_arrival_date)
                                VALUES(?,?,?,?,?,NULL,?,?)
                                """,
                        suggestionId, sku.getKey(), group.getKey(), shortage, quantity, price, eta);
                for (var item : sku.getValue()) {
                    jdbc.update("""
                                    INSERT INTO shortage_coverage(
                                        sales_order_item_id,suggestion_item_id,covered_quantity,active)
                                    VALUES(?,?,?,TRUE)
                                    """,
                            num(item, "item_id"), suggestionItemId, num(item, "uncovered_quantity"));
                }
            }
            suggestions.add(suggestionId);
        }
        return Map.of("suggestionIds", suggestions, "count", suggestions.size());
    }

    @Transactional
    public Map<String, Object> confirm(long suggestionId) {
        var suggestion = jdbc.queryForMap(
                "SELECT suggestion_no,status FROM procurement_suggestion WHERE id=? FOR UPDATE", suggestionId);
        if (!"DRAFT".equals(str(suggestion, "status"))) {
            throw new IllegalStateException("当前采购建议不能重复确认");
        }
        var lines = jdbc.queryForList("""
                SELECT sku_id,supplier_id,suggested_quantity,purchase_price,expected_arrival_date
                FROM procurement_suggestion_item WHERE suggestion_id=? ORDER BY id
                """, suggestionId);
        if (lines.isEmpty()) {
            throw new IllegalStateException("采购建议没有可确认的明细");
        }
        long supplierId = num(lines.get(0), "supplier_id");
        for (var line : lines) {
            if (num(line, "supplier_id") != supplierId) {
                throw new IllegalStateException("同一采购建议只能绑定一个供应商");
            }
            if (num(line, "suggested_quantity") <= 0) {
                throw new IllegalStateException("确认采购数量必须大于零");
            }
        }

        BigDecimal total = lines.stream()
                .map(line -> ((BigDecimal) val(line, "purchase_price"))
                        .multiply(BigDecimal.valueOf(num(line, "suggested_quantity"))))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        LocalDate eta = lines.stream()
                .map(line -> localDate(val(line, "expected_arrival_date")))
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        String purchaseNo = "PO" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        long purchaseId = insert("""
                        INSERT INTO purchase_order(
                            purchase_no,suggestion_id,supplier_id,status,total_amount,expected_arrival_date,created_by)
                        VALUES(?,?,?,'PENDING_SUPPLIER_PAYMENT',?,?,1)
                        """,
                purchaseNo, suggestionId, supplierId, total, eta);

        int lineNo = 1;
        for (var line : lines) {
            long skuId = num(line, "sku_id");
            int quantity = (int) num(line, "suggested_quantity");
            BigDecimal price = (BigDecimal) val(line, "purchase_price");
            jdbc.update("""
                            INSERT INTO purchase_order_item(
                                purchase_order_id,line_no,sku_id,quantity,received_quantity,purchase_price)
                            VALUES(?,?,?,?,0,?)
                            """,
                    purchaseId, lineNo++, skuId, quantity, price);
            increaseTransit(skuId, quantity, purchaseNo);
        }
        jdbc.update("UPDATE procurement_suggestion_item SET confirmed_quantity=suggested_quantity WHERE suggestion_id=?",
                suggestionId);
        jdbc.update("""
                        UPDATE procurement_suggestion
                        SET status='CONFIRMED',confirmed_by=1,confirmed_at=?,version=version+1
                        WHERE id=?
                        """,
                LocalDateTime.now(), suggestionId);
        return Map.of(
                "suggestionId", suggestionId,
                "purchaseId", purchaseId,
                "purchaseNo", purchaseNo,
                "status", "PENDING_SUPPLIER_PAYMENT");
    }

    @Transactional
    public Map<String, Object> manual(ManualPurchaseRequest request) {
        if (request.quantity() <= 0) {
            throw new IllegalArgumentException("采购数量必须大于零");
        }
        BigDecimal purchasePrice = manualPurchasePrice(request.purchasePrice());
        requireExists("supplier", request.supplierId(), "供应商不存在");
        requireExists("sku", request.skuId(), "产品不存在");
        Integer relationCount = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM sku_supplier_config
                        WHERE supplier_id=? AND sku_id=? AND enabled=TRUE
                        """, Integer.class, request.supplierId(), request.skuId());
        if (relationCount == null || relationCount == 0) {
            throw new IllegalArgumentException("所选产品未配置给该供应商");
        }

        BigDecimal total = purchasePrice
                .multiply(BigDecimal.valueOf(request.quantity()))
                .setScale(2, RoundingMode.HALF_UP);
        if (total.signum() <= 0 || total.compareTo(MAX_PURCHASE_TOTAL) > 0) {
            throw new IllegalArgumentException("采购总额必须为大于0且不超过数据库金额上限的金额");
        }
        String purchaseNo = "PO" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        long purchaseId = insert("""
                        INSERT INTO purchase_order(
                            purchase_no,suggestion_id,manual_entry,supplier_id,status,total_amount,
                            expected_arrival_date,purchase_remark,created_by)
                        VALUES(?,NULL,TRUE,?,'PENDING_SUPPLIER_PAYMENT',?,?,?,1)
                        """,
                purchaseNo, request.supplierId(), total, request.expectedArrivalDate(), request.remark());
        jdbc.update("""
                        INSERT INTO purchase_order_item(
                            purchase_order_id,line_no,sku_id,quantity,received_quantity,purchase_price)
                        VALUES(?,1,?,?,0,?)
                        """,
                purchaseId, request.skuId(), request.quantity(), purchasePrice);
        increaseTransit(request.skuId(), request.quantity(), purchaseNo);
        return Map.of(
                "purchaseId", purchaseId,
                "purchaseNo", purchaseNo,
                "status", "PENDING_SUPPLIER_PAYMENT");
    }

    @Transactional
    public Map<String, Object> payment(long id, FinanceActionRequest request) {
        var purchase = jdbc.queryForMap("SELECT status,total_amount FROM purchase_order WHERE id=? FOR UPDATE", id);
        if (!"PENDING_SUPPLIER_PAYMENT".equals(str(purchase, "status"))) {
            throw new IllegalStateException("当前采购单不能登记付款");
        }
        BigDecimal total = (BigDecimal) val(purchase, "total_amount");
        if (request.amount() == null || request.amount().compareTo(total) != 0) {
            throw new IllegalArgumentException("付款金额必须等于采购单总额");
        }
        jdbc.update("""
                        INSERT INTO supplier_payment(
                            purchase_order_id,amount,payment_method,payment_remark,invoice_no,invoice_date,paid_at,confirmed_by)
                        VALUES(?,?,?,?,?,?,?,1)
                        """,
                id, request.amount(), request.paymentMethod() == null ? "银行转账" : request.paymentMethod(),
                request.paymentRemark(), request.invoiceNo(), request.invoiceDate(), LocalDateTime.now());
        jdbc.update("UPDATE purchase_order SET status='EXECUTING',version=version+1 WHERE id=?", id);
        return Map.of("id", id, "status", "EXECUTING");
    }

    @Transactional
    public Map<String, Object> paymentByPurchaseNo(String purchaseNo, FinanceActionRequest request) {
        if (purchaseNo == null || purchaseNo.isBlank()) {
            throw new IllegalArgumentException("采购单号不能为空");
        }
        List<Long> purchaseIds = jdbc.queryForList(
                "SELECT id FROM purchase_order WHERE purchase_no=?", Long.class, purchaseNo);
        if (purchaseIds.isEmpty()) {
            throw new IllegalArgumentException("采购单不存在");
        }
        return payment(purchaseIds.get(0), request);
    }

    @Transactional
    public Map<String, Object> receive(long id) {
        var purchase = jdbc.queryForMap("SELECT purchase_no,status FROM purchase_order WHERE id=? FOR UPDATE", id);
        if (!"EXECUTING".equals(str(purchase, "status"))) {
            throw new IllegalStateException("采购单付款后才能到货入库");
        }
        String purchaseNo = str(purchase, "purchase_no");
        long receipt = insert("""
                        INSERT INTO goods_receipt(
                            receipt_no,purchase_order_id,received_at,received_by,status)
                        VALUES(?,?,?,1,'COMPLETED')
                        """,
                "GR" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), id, LocalDateTime.now());
        var lines = jdbc.queryForList("""
                SELECT id,sku_id,quantity,received_quantity,purchase_price
                FROM purchase_order_item WHERE purchase_order_id=?
                """, id);
        for (var line : lines) {
            int quantity = (int) (num(line, "quantity") - num(line, "received_quantity"));
            if (quantity <= 0) {
                continue;
            }
            long skuId = num(line, "sku_id");
            BigDecimal price = (BigDecimal) val(line, "purchase_price");
            receiveStock(skuId, quantity, purchaseNo);
            jdbc.update("UPDATE purchase_order_item SET received_quantity=quantity WHERE id=?", num(line, "id"));
            jdbc.update("""
                            INSERT INTO goods_receipt_item(
                                goods_receipt_id,purchase_order_item_id,accepted_quantity,rejected_quantity)
                            VALUES(?,?,?,0)
                            """,
                    receipt, num(line, "id"), quantity);
            BigDecimal oldCost = jdbc.queryForObject("SELECT current_cost FROM sku WHERE id=?", BigDecimal.class, skuId);
            jdbc.update("UPDATE sku SET current_cost=?,version=version+1 WHERE id=?", price, skuId);
            jdbc.update("""
                            INSERT INTO sku_cost_history(
                                sku_id,old_cost,new_cost,source_type,source_no,effective_at,operated_by)
                            VALUES(?,?,?,'PURCHASE_RECEIPT',?,?,1)
                            """,
                    skuId, oldCost, price, purchaseNo, LocalDateTime.now());
        }
        jdbc.update("UPDATE purchase_order SET status='RECEIVED',version=version+1 WHERE id=?", id);
        allocation.reallocateWaiting();
        return Map.of("id", id, "status", "RECEIVED");
    }

    private void increaseTransit(long skuId, int quantity, String purchaseNo) {
        long warehouseId = warehouse();
        jdbc.update("""
                        INSERT INTO inventory_balance(
                            warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity)
                        VALUES(?,?,0,0,0) ON DUPLICATE KEY UPDATE sku_id=VALUES(sku_id)
                        """,
                warehouseId, skuId);
        var balance = jdbc.queryForMap("""
                SELECT id,actual_quantity,locked_quantity,in_transit_quantity
                FROM inventory_balance WHERE warehouse_id=? AND sku_id=? FOR UPDATE
                """, warehouseId, skuId);
        int transitBefore = (int) num(balance, "in_transit_quantity");
        jdbc.update("""
                        UPDATE inventory_balance
                        SET in_transit_quantity=in_transit_quantity+?,version=version+1 WHERE id=?
                        """,
                quantity, num(balance, "id"));
        allocation.tx(warehouseId, skuId, "PURCHASE_TRANSIT", "PURCHASE_ORDER", purchaseNo,
                0, 0, quantity,
                (int) num(balance, "actual_quantity"), (int) num(balance, "actual_quantity"),
                (int) num(balance, "locked_quantity"), (int) num(balance, "locked_quantity"),
                transitBefore, transitBefore + quantity);
    }

    private void receiveStock(long skuId, int quantity, String purchaseNo) {
        long warehouseId = warehouse();
        var balance = jdbc.queryForMap("""
                SELECT id,actual_quantity,locked_quantity,in_transit_quantity
                FROM inventory_balance WHERE warehouse_id=? AND sku_id=? FOR UPDATE
                """, warehouseId, skuId);
        int actual = (int) num(balance, "actual_quantity");
        int locked = (int) num(balance, "locked_quantity");
        int transit = (int) num(balance, "in_transit_quantity");
        jdbc.update("""
                        UPDATE inventory_balance
                        SET actual_quantity=actual_quantity+?,
                            in_transit_quantity=GREATEST(0,in_transit_quantity-?),version=version+1
                        WHERE id=?
                        """,
                quantity, quantity, num(balance, "id"));
        allocation.tx(warehouseId, skuId, "PURCHASE_RECEIPT", "PURCHASE_ORDER", purchaseNo,
                quantity, 0, -Math.min(transit, quantity),
                actual, actual + quantity, locked, locked, transit, Math.max(0, transit - quantity));
    }

    private long warehouse() {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT id FROM warehouse WHERE is_default=TRUE AND enabled=TRUE ORDER BY id LIMIT 1
                """, Long.class));
    }

    private BigDecimal manualPurchasePrice(BigDecimal purchasePrice) {
        if (purchasePrice == null || purchasePrice.signum() <= 0) {
            throw new IllegalArgumentException("采购单价必须大于零");
        }
        try {
            BigDecimal databaseScalePrice = purchasePrice.setScale(4, RoundingMode.UNNECESSARY);
            if (databaseScalePrice.compareTo(MAX_PURCHASE_PRICE) > 0) {
                throw new IllegalArgumentException("采购单价超出数据库金额范围");
            }
            return databaseScalePrice;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("采购单价最多只能保留4位小数", exception);
        }
    }

    private void requireExists(String table, long id, String message) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=?", Integer.class, id);
        if (count == null || count == 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private long insert(String sql, Object... parameters) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            return statement;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    private Object val(Map<String, Object> values, String key) {
        for (var entry : values.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private long num(Map<String, Object> values, String key) {
        return ((Number) Objects.requireNonNull(val(values, key))).longValue();
    }

    private String str(Map<String, Object> values, String key) {
        return String.valueOf(val(values, key));
    }

    private LocalDate localDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
