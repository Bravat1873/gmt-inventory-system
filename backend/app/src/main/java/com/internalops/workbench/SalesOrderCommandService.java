package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class SalesOrderCommandService {
    // 发货、库存锁定和开票状态必须由对应的业务动作推进，避免手工改状态绕过出入库流水。
    private static final Set<String> EDITABLE_STATUSES = Set.of("DRAFT", "PENDING_CUSTOMER_PAYMENT");
    private final JdbcTemplate jdbc;
    private final InventoryAllocationService allocation;
    public SalesOrderCommandService(JdbcTemplate jdbc, InventoryAllocationService allocation) { this.jdbc = jdbc; this.allocation = allocation; }

    @Transactional
    public Map<String, Object> create(SalesOrderRequest request) {
        validate(request);
        String orderNo = "SO" + java.time.LocalDate.now().toString().replace("-", "") + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        long id = insert("INSERT INTO sales_order(order_no,external_order_no,customer_id,status,total_amount,order_date,order_type,salesperson,customer_contact,customer_phone,order_remark,delivery_address,delivery_contact,delivery_phone,shipping_method) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                orderNo, blankToNull(request.externalOrderNo()), request.customerId(), status(request), total(request), request.orderDate(), trim(request.orderType()), trim(request.salesperson()), blankToNull(request.customerContact()), blankToNull(request.customerPhone()), blankToNull(request.remark()), blankToNull(request.deliveryAddress()), blankToNull(request.deliveryContact()), blankToNull(request.deliveryPhone()), blankToNull(request.shippingMethod()));
        insertItems(id, request.items());
        if ("PENDING_CUSTOMER_PAYMENT".equals(status(request))) allocation.allocate(id);
        return get(id);
    }

    public Map<String, Object> get(long id) {
        Map<String, Object> order = jdbc.queryForMap("SELECT id,order_no,external_order_no,customer_id,status,total_amount,order_date,order_type,salesperson,customer_contact,customer_phone,order_remark,delivery_address,delivery_contact,delivery_phone,shipping_method,receipt_confirmed_at,shipped_at,carrier,tracking_no,shipping_remark,created_at,version FROM sales_order WHERE id=?", id);
        List<Map<String, Object>> items = jdbc.query("SELECT i.id,i.line_no,i.sku_id,s.sku_code,s.product_name,s.model,s.configuration,s.unit,i.quantity,i.shipped_quantity,i.locked_quantity,i.uncovered_quantity,i.sale_price,i.cost_snapshot FROM sales_order_item i JOIN sku s ON s.id=i.sku_id WHERE i.sales_order_id=? ORDER BY i.line_no", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            int quantity = rs.getInt("quantity");
            int shipped = rs.getInt("shipped_quantity");
            m.put("id", rs.getLong("id")); m.put("lineNo", rs.getInt("line_no")); m.put("skuId", rs.getLong("sku_id"));
            m.put("skuCode", rs.getString("sku_code")); m.put("productName", rs.getString("product_name")); m.put("model", rs.getString("model")); m.put("configuration", rs.getString("configuration")); m.put("unit", rs.getString("unit"));
            m.put("quantity", quantity); m.put("shippedQuantity", shipped); m.put("remainingQuantity", quantity - shipped);
            m.put("lockedQuantity", rs.getInt("locked_quantity")); m.put("uncoveredQuantity", rs.getInt("uncovered_quantity")); m.put("salePrice", rs.getBigDecimal("sale_price")); m.put("costSnapshot", rs.getBigDecimal("cost_snapshot"));
            return m;
        }, id);
        for (Map<String, Object> item : items) {
            Integer available = jdbc.queryForObject("SELECT COALESCE((SELECT actual_quantity-locked_quantity FROM inventory_balance WHERE sku_id=? AND warehouse_id=(SELECT id FROM warehouse WHERE is_default=TRUE AND enabled=TRUE ORDER BY id LIMIT 1)),0)", Integer.class, item.get("skuId"));
            item.put("availableQuantity", available == null ? 0 : available);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        put(result, order, "id", "id", "order_no", "orderNo", "external_order_no", "externalOrderNo", "customer_id", "customerId", "status", "status", "total_amount", "totalAmount", "order_date", "orderDate", "order_type", "orderType", "salesperson", "salesperson", "customer_contact", "customerContact", "customer_phone", "customerPhone", "order_remark", "remark", "delivery_address", "deliveryAddress", "delivery_contact", "deliveryContact", "delivery_phone", "deliveryPhone", "shipping_method", "shippingMethod", "receipt_confirmed_at", "receiptConfirmedAt", "shipped_at", "shippedAt", "carrier", "carrier", "tracking_no", "trackingNo", "shipping_remark", "shippingRemark", "created_at", "createdAt", "version", "version");
        BigDecimal receivableAmount = items.stream()
                .map(item -> BigDecimal.valueOf(((Number) item.get("remainingQuantity")).longValue()).multiply((BigDecimal) item.get("salePrice")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal receivedAmount = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM customer_receipt WHERE sales_order_id=?", BigDecimal.class, id);
        result.put("items", items);
        result.put("receivableAmount", receivableAmount);
        result.put("receivedAmount", receivedAmount == null ? BigDecimal.ZERO : receivedAmount);
        return result;
    }

    public List<Map<String, Object>> skuOptions() {
        return jdbc.query("SELECT id,sku_code,product_name,model,configuration,product_version,color,lock_body,unit FROM sku WHERE enabled=TRUE ORDER BY sku_code", (rs, n) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id")); item.put("skuCode", rs.getString("sku_code")); item.put("productName", rs.getString("product_name"));
            item.put("model", rs.getString("model")); item.put("configuration", rs.getString("configuration"));
            item.put("productVersion", rs.getString("product_version")); item.put("color", rs.getString("color"));
            item.put("lockBody", rs.getString("lock_body")); item.put("unit", rs.getString("unit"));
            return item;
        });
    }

    public List<Map<String, Object>> customerOptions() {
        return jdbc.query("SELECT id,customer_code,customer_name,contact_name,phone,address FROM customer WHERE enabled=TRUE ORDER BY customer_name", (rs, n) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id")); item.put("customerCode", rs.getString("customer_code")); item.put("customerName", rs.getString("customer_name"));
            item.put("contactName", Objects.toString(rs.getString("contact_name"), "")); item.put("phone", Objects.toString(rs.getString("phone"), "")); item.put("address", Objects.toString(rs.getString("address"), ""));
            return item;
        });
    }

    @Transactional
    public Map<String, Object> update(long id, SalesOrderRequest request) {
        validate(request);
        if (request.version() == null) throw new IllegalArgumentException("缺少数据版本，请重新打开后再试");
        String currentStatus = jdbc.queryForObject("SELECT status FROM sales_order WHERE id=? FOR UPDATE", String.class, id);
        if (!"PENDING_CUSTOMER_PAYMENT".equals(currentStatus) && !"DRAFT".equals(currentStatus)) throw new IllegalStateException("确认收款后不可直接修改，只能走异常处理");
        int changed = jdbc.update("UPDATE sales_order SET external_order_no=?,customer_id=?,status=?,total_amount=?,order_date=?,order_type=?,salesperson=?,customer_contact=?,customer_phone=?,order_remark=?,delivery_address=?,delivery_contact=?,delivery_phone=?,shipping_method=?,version=version+1 WHERE id=? AND version=?",
                blankToNull(request.externalOrderNo()), request.customerId(), status(request), total(request), request.orderDate(), trim(request.orderType()), trim(request.salesperson()), blankToNull(request.customerContact()), blankToNull(request.customerPhone()), blankToNull(request.remark()), blankToNull(request.deliveryAddress()), blankToNull(request.deliveryContact()), blankToNull(request.deliveryPhone()), blankToNull(request.shippingMethod()), id, request.version());
        if (changed == 0) throw new IllegalStateException("数据已被其他操作修改，请重新打开后再试");
        jdbc.update("DELETE FROM sales_order_item WHERE sales_order_id=?", id);
        insertItems(id, request.items());
        if ("PENDING_CUSTOMER_PAYMENT".equals(status(request))) allocation.allocate(id);
        return get(id);
    }

    private void validate(SalesOrderRequest request) {
        if (request.customerId() == null || jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE id=? AND enabled=TRUE", Integer.class, request.customerId()) == 0) throw new IllegalArgumentException("请选择有效客户");
        if (request.orderDate() == null) throw new IllegalArgumentException("请选择订单日期");
        if (blankToNull(request.orderType()) == null) throw new IllegalArgumentException("请填写订单类型");
        if (blankToNull(request.salesperson()) == null) throw new IllegalArgumentException("请填写销售员");
        if (!EDITABLE_STATUSES.contains(status(request))) throw new IllegalArgumentException("不支持的订单状态");
        if (request.items() == null || request.items().isEmpty()) throw new IllegalArgumentException("订单至少需要一条产品明细");
        for (SalesOrderRequest.Item item : request.items()) {
            if (item.skuId() == null || jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE id=? AND enabled=TRUE", Integer.class, item.skuId()) == 0) throw new IllegalArgumentException("订单中存在无效产品");
            if (item.quantity() == null || item.quantity() <= 0) throw new IllegalArgumentException("产品数量必须为正数");
            if (item.salePrice() == null || item.salePrice().signum() < 0) throw new IllegalArgumentException("销售单价不能为负数");
        }
    }

    private BigDecimal total(SalesOrderRequest request) { return request.items().stream().map(item -> item.salePrice().multiply(BigDecimal.valueOf(item.quantity()))).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2); }
    private void insertItems(long orderId, List<SalesOrderRequest.Item> items) {
        int nextLine = 10000;
        for (SalesOrderRequest.Item item : items) {
            int line = item.lineNo() != null && item.lineNo() > 0 ? item.lineNo() : nextLine;
            nextLine = Math.max(nextLine, line + 10000);
            BigDecimal cost = jdbc.queryForObject("SELECT current_cost FROM sku WHERE id=?", BigDecimal.class, item.skuId());
            jdbc.update("INSERT INTO sales_order_item(sales_order_id,line_no,sku_id,quantity,locked_quantity,uncovered_quantity,sale_price,cost_snapshot) VALUES(?,?,?,?,0,0,?,?)", orderId, line, item.skuId(), item.quantity(), item.salePrice(), cost);
        }
    }
    private long insert(String sql, Object... params) { var keys = new GeneratedKeyHolder(); jdbc.update(connection -> { PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"}); for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]); return statement; }, keys); return Objects.requireNonNull(keys.getKey()).longValue(); }
    private Object value(Map<String, Object> source, String key) { for (var entry : source.entrySet()) if (entry.getKey().equalsIgnoreCase(key)) return entry.getValue(); return null; }
    private void put(Map<String, Object> target, Map<String, Object> source, String... pairs) { for (int i = 0; i < pairs.length; i += 2) target.put(pairs[i + 1], value(source, pairs[i])); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private String status(SalesOrderRequest request) { return blankToNull(request.status()) == null ? "PENDING_CUSTOMER_PAYMENT" : request.status().trim(); }
}
