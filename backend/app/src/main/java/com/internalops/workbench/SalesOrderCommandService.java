package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
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
    private static final Set<String> ORDER_TYPES = Set.of("\u5de5\u7a0b\u8ba2\u5355", "\u96f6\u552e\u8ba2\u5355", "\u524d\u7f6e\u8ba2\u5355");
    private final JdbcTemplate jdbc;
    private final InventoryAllocationService allocation;
    private final SupplyDemandQueryService supplyDemand;
    public SalesOrderCommandService(JdbcTemplate jdbc, InventoryAllocationService allocation,
                                    SupplyDemandQueryService supplyDemand) {
        this.jdbc = jdbc;
        this.allocation = allocation;
        this.supplyDemand = supplyDemand;
    }

    @Transactional
    public Map<String, Object> create(SalesOrderRequest request) {
        validate(request);
        String orderNo = "SO" + java.time.LocalDate.now().toString().replace("-", "") + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String orderContactName = firstNonBlank(request.orderContactName(), request.customerContact());
        String orderContactPhone = firstNonBlank(request.orderContactPhone(), request.customerPhone());
        long id = insert("INSERT INTO sales_order(order_no,external_order_no,customer_id,status,total_amount,order_date,order_type,salesperson,customer_contact,customer_phone,business_contact_name,business_contact_phone,order_contact_name,order_contact_phone,finance_contact_name,finance_contact_phone,order_remark,delivery_address,delivery_contact,delivery_phone,shipping_method) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                orderNo, blankToNull(request.externalOrderNo()), request.customerId(), status(request), total(request), request.orderDate(), trim(request.orderType()), trim(request.salesperson()), orderContactName, orderContactPhone,
                blankToNull(request.businessContactName()), blankToNull(request.businessContactPhone()), orderContactName, orderContactPhone, blankToNull(request.financeContactName()), blankToNull(request.financeContactPhone()),
                blankToNull(request.remark()), blankToNull(request.deliveryAddress()), blankToNull(request.deliveryContact()), blankToNull(request.deliveryPhone()), blankToNull(request.shippingMethod()));
        insertItems(id, request.items());
        if ("PENDING_CUSTOMER_PAYMENT".equals(status(request))) allocation.allocate(id);
        return get(id);
    }

    public Map<String, Object> get(long id) {
        Map<String, Object> order = jdbc.queryForMap("SELECT id,order_no,external_order_no,customer_id,status,total_amount,order_date,order_type,salesperson,customer_contact,customer_phone,business_contact_name,business_contact_phone,order_contact_name,order_contact_phone,finance_contact_name,finance_contact_phone,order_remark,delivery_address,delivery_contact,delivery_phone,shipping_method,receipt_confirmed_at,shipped_at,carrier,tracking_no,shipping_remark,created_at,version FROM sales_order WHERE id=?", id);
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
        put(result, order, "id", "id", "order_no", "orderNo", "external_order_no", "externalOrderNo", "customer_id", "customerId", "status", "status", "total_amount", "totalAmount", "order_date", "orderDate", "order_type", "orderType", "salesperson", "salesperson", "customer_contact", "customerContact", "customer_phone", "customerPhone", "business_contact_name", "businessContactName", "business_contact_phone", "businessContactPhone", "order_contact_name", "orderContactName", "order_contact_phone", "orderContactPhone", "finance_contact_name", "financeContactName", "finance_contact_phone", "financeContactPhone", "order_remark", "remark", "delivery_address", "deliveryAddress", "delivery_contact", "deliveryContact", "delivery_phone", "deliveryPhone", "shipping_method", "shippingMethod", "receipt_confirmed_at", "receiptConfirmedAt", "shipped_at", "shippedAt", "carrier", "carrier", "tracking_no", "trackingNo", "shipping_remark", "shippingRemark", "created_at", "createdAt", "version", "version");
        result.put("orderContactName", firstNonBlank(Objects.toString(value(order, "order_contact_name"), null), Objects.toString(value(order, "customer_contact"), null)));
        result.put("orderContactPhone", firstNonBlank(Objects.toString(value(order, "order_contact_phone"), null), Objects.toString(value(order, "customer_phone"), null)));
        BigDecimal receivableAmount = items.stream()
                .map(item -> BigDecimal.valueOf(((Number) item.get("remainingQuantity")).longValue()).multiply((BigDecimal) item.get("salePrice")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal receivedAmount = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM customer_receipt WHERE sales_order_id=?", BigDecimal.class, id);
        String defaultShipmentAddress = blankToNull(Objects.toString(value(order, "delivery_address"), null));
        if (defaultShipmentAddress == null) {
            defaultShipmentAddress = blankToNull(jdbc.queryForObject("SELECT address FROM customer WHERE id=?", String.class, value(order, "customer_id")));
        }
        if (defaultShipmentAddress == null) defaultShipmentAddress = "";
        result.put("items", items);
        result.put("defaultShipmentAddress", defaultShipmentAddress);
        result.put("shipments", shipmentHistory(id));
        result.put("receivableAmount", receivableAmount);
        result.put("receivedAmount", receivedAmount == null ? BigDecimal.ZERO : receivedAmount);
        return result;
    }

    private List<Map<String, Object>> shipmentHistory(long orderId) {
        List<Map<String, Object>> shipments = jdbc.query("""
                SELECT s.id,s.shipment_no,s.delivery_address,s.operator_name,s.shipped_at,s.remark,
                       COALESCE(SUM(si.quantity),0) total_quantity
                FROM sales_shipment s
                LEFT JOIN sales_shipment_item si ON si.sales_shipment_id=s.id
                WHERE s.sales_order_id=?
                GROUP BY s.id,s.shipment_no,s.delivery_address,s.operator_name,s.shipped_at,s.remark
                ORDER BY s.shipped_at DESC,s.id DESC
                """, (rs, n) -> {
            Map<String, Object> shipment = new LinkedHashMap<>();
            shipment.put("id", rs.getLong("id"));
            shipment.put("shipmentNo", rs.getString("shipment_no"));
            shipment.put("deliveryAddress", rs.getString("delivery_address"));
            shipment.put("operatorName", rs.getString("operator_name"));
            shipment.put("shippedAt", rs.getTimestamp("shipped_at").toLocalDateTime());
            shipment.put("remark", rs.getString("remark"));
            shipment.put("totalQuantity", rs.getInt("total_quantity"));
            return shipment;
        }, orderId);
        Map<Long, List<Map<String, Object>>> itemsByShipment = new HashMap<>();
        if (!shipments.isEmpty()) {
            List<Map<String, Object>> shipmentItems = jdbc.query("""
                    SELECT si.sales_shipment_id,si.id,si.sales_order_item_id,oi.line_no,oi.sku_id,
                           sku.sku_code,sku.product_name,sku.model,sku.unit,si.quantity
                    FROM sales_shipment_item si
                    JOIN sales_shipment shipment ON shipment.id=si.sales_shipment_id
                    JOIN sales_order_item oi ON oi.id=si.sales_order_item_id
                    JOIN sku ON sku.id=oi.sku_id
                    WHERE shipment.sales_order_id=?
                    ORDER BY si.sales_shipment_id,oi.line_no,si.id
                    """, (rs, n) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("shipmentId", rs.getLong("sales_shipment_id"));
                item.put("id", rs.getLong("id"));
                item.put("salesOrderItemId", rs.getLong("sales_order_item_id"));
                item.put("lineNo", rs.getInt("line_no"));
                item.put("skuId", rs.getLong("sku_id"));
                item.put("skuCode", rs.getString("sku_code"));
                item.put("productName", rs.getString("product_name"));
                item.put("model", rs.getString("model"));
                item.put("unit", rs.getString("unit"));
                item.put("quantity", rs.getInt("quantity"));
                return item;
            }, orderId);
            for (Map<String, Object> item : shipmentItems) {
                long shipmentId = ((Number) item.remove("shipmentId")).longValue();
                itemsByShipment.computeIfAbsent(shipmentId, ignored -> new ArrayList<>()).add(item);
            }
        }
        for (Map<String, Object> shipment : shipments) {
            long shipmentId = ((Number) shipment.get("id")).longValue();
            shipment.put("items", itemsByShipment.getOrDefault(shipmentId, List.of()));
        }
        return shipments;
    }

    public List<Map<String, Object>> skuOptions() {
        List<Map<String, Object>> items = jdbc.query("""
                SELECT s.id,s.product_code,s.sku_code,s.product_name,s.model,s.product_type,s.product_configuration,s.configuration,s.product_version,s.color,s.lock_body,s.unit,s.sales_minimum_order_quantity,s.current_cost,s.factory_price,
                       (SELECT pi.id FROM product_image pi
                        WHERE pi.product_id=s.id AND pi.is_primary=TRUE
                        ORDER BY pi.sort_order,pi.id LIMIT 1) AS primary_image_id
                FROM sku s
                WHERE s.enabled=TRUE
                ORDER BY s.product_code
                """, (rs, n) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id")); item.put("productCode", rs.getString("product_code")); item.put("skuCode", rs.getString("sku_code")); item.put("productName", rs.getString("product_name"));
            item.put("model", rs.getString("model")); item.put("productType", rs.getString("product_type")); item.put("productConfiguration", rs.getString("product_configuration")); item.put("configuration", rs.getString("configuration"));
            item.put("productVersion", rs.getString("product_version")); item.put("color", rs.getString("color"));
            item.put("lockBody", rs.getString("lock_body")); item.put("unit", rs.getString("unit"));
            item.put("salesMinimumOrderQuantity", rs.getInt("sales_minimum_order_quantity"));
            item.put("currentCost", rs.getBigDecimal("current_cost"));
            item.put("factoryPrice", rs.getBigDecimal("factory_price"));
            item.put("primaryImageId", rs.getObject("primary_image_id", Long.class));
            return item;
        });
        Map<Long, SupplyDemandQueryService.SupplyDemandSnapshot> snapshots = supplyDemand.bySkuIds(
                items.stream().map(item -> ((Number) item.get("id")).longValue()).toList());
        items.forEach(item -> {
            var snapshot = snapshots.get(((Number) item.get("id")).longValue());
            item.put("actualQuantity", snapshot.actualQuantity());
            item.put("availableQuantity", snapshot.availableQuantity());
            item.put("inTransitQuantity", snapshot.inTransitQuantity());
            item.put("pendingDeliveryQuantity", snapshot.pendingDeliveryQuantity());
            item.put("supplyDemandBalance", snapshot.supplyDemandBalance());
            item.put("purchaseShortageQuantity", snapshot.purchaseShortageQuantity());
        });
        return items;
    }
    public List<Map<String, Object>> customerOptions() {
        return jdbc.query("""
                SELECT id,customer_code,customer_name,contact_name,phone,address,
                       business_contact_name,business_contact_phone,order_contact_name,order_contact_phone,
                       finance_contact_name,finance_contact_phone,
                       COALESCE((SELECT balance FROM customer_fund_account f WHERE f.customer_id=c.id),0) fund_balance
                FROM customer c
                WHERE c.enabled=TRUE
                  AND (NOT EXISTS (SELECT 1 FROM customer_contract all_contracts WHERE all_contracts.customer_id=c.id AND all_contracts.enabled=TRUE)
                       OR EXISTS (SELECT 1 FROM customer_contract active_contract WHERE active_contract.customer_id=c.id AND active_contract.enabled=TRUE AND CURRENT_DATE BETWEEN active_contract.start_date AND active_contract.end_date))
                ORDER BY customer_name
                """, (rs, n) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id")); item.put("customerCode", rs.getString("customer_code")); item.put("customerName", rs.getString("customer_name"));
            item.put("contactName", Objects.toString(rs.getString("contact_name"), "")); item.put("phone", Objects.toString(rs.getString("phone"), "")); item.put("address", Objects.toString(rs.getString("address"), ""));
            item.put("businessContactName", Objects.toString(rs.getString("business_contact_name"), ""));
            item.put("businessContactPhone", Objects.toString(rs.getString("business_contact_phone"), ""));
            item.put("orderContactName", Objects.toString(rs.getString("order_contact_name"), ""));
            item.put("orderContactPhone", Objects.toString(rs.getString("order_contact_phone"), ""));
            item.put("financeContactName", Objects.toString(rs.getString("finance_contact_name"), ""));
            item.put("financeContactPhone", Objects.toString(rs.getString("finance_contact_phone"), ""));
            item.put("fundBalance", rs.getBigDecimal("fund_balance"));
            return item;
        });
    }

    public Map<String, Object> contractPrice(long customerId, long skuId) {
        List<BigDecimal> prices = jdbc.queryForList("""
                SELECT p.sale_price
                FROM customer_contract_price p
                JOIN customer_contract c ON c.id=p.contract_id
                WHERE c.customer_id=? AND p.sku_id=? AND c.enabled=TRUE
                  AND CURRENT_DATE BETWEEN c.start_date AND c.end_date
                ORDER BY c.start_date DESC,c.id DESC
                LIMIT 1
                """, BigDecimal.class, customerId, skuId);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("salePrice", prices.isEmpty() ? null : prices.get(0));
        return result;
    }

    @Transactional
    public Map<String, Object> update(long id, SalesOrderRequest request) {
        validate(request);
        if (request.version() == null) throw new IllegalArgumentException("缺少数据版本，请重新打开后再试");
        String currentStatus = jdbc.queryForObject("SELECT status FROM sales_order WHERE id=? FOR UPDATE", String.class, id);
        if (!Set.of("DRAFT", "PENDING_CUSTOMER_PAYMENT", "READY_TO_SHIP", "WAITING_STOCK").contains(currentStatus)) throw new IllegalStateException("确认收款后不可直接修改，只能走异常处理");
        allocation.releaseAll(id, "ORDER_EDIT_RELEASE");
        String orderContactName = firstNonBlank(request.orderContactName(), request.customerContact());
        String orderContactPhone = firstNonBlank(request.orderContactPhone(), request.customerPhone());
        int changed = jdbc.update("UPDATE sales_order SET external_order_no=?,customer_id=?,status=?,total_amount=?,order_date=?,order_type=?,salesperson=?,customer_contact=?,customer_phone=?,business_contact_name=?,business_contact_phone=?,order_contact_name=?,order_contact_phone=?,finance_contact_name=?,finance_contact_phone=?,order_remark=?,delivery_address=?,delivery_contact=?,delivery_phone=?,shipping_method=?,version=version+1 WHERE id=? AND version=?",
                blankToNull(request.externalOrderNo()), request.customerId(), status(request), total(request), request.orderDate(), trim(request.orderType()), trim(request.salesperson()), orderContactName, orderContactPhone,
                blankToNull(request.businessContactName()), blankToNull(request.businessContactPhone()), orderContactName, orderContactPhone, blankToNull(request.financeContactName()), blankToNull(request.financeContactPhone()),
                blankToNull(request.remark()), blankToNull(request.deliveryAddress()), blankToNull(request.deliveryContact()), blankToNull(request.deliveryPhone()), blankToNull(request.shippingMethod()), id, request.version());
        if (changed == 0) throw new IllegalStateException("数据已被其他操作修改，请重新打开后再试");
        jdbc.update("DELETE FROM sales_order_item WHERE sales_order_id=?", id);
        insertItems(id, request.items());
        if ("PENDING_CUSTOMER_PAYMENT".equals(status(request))) allocation.allocate(id);
        return get(id);
    }

    private void validate(SalesOrderRequest request) {
        if (request.customerId() == null || jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE id=? AND enabled=TRUE", Integer.class, request.customerId()) == 0) throw new IllegalArgumentException("请选择有效客户");
        if (request.orderDate() == null) throw new IllegalArgumentException("请选择订单日期");
        if (!ORDER_TYPES.contains(trim(request.orderType()))) throw new IllegalArgumentException("\u8ba2\u5355\u7c7b\u578b\u53ea\u80fd\u9009\u62e9\u5de5\u7a0b\u8ba2\u5355\u3001\u96f6\u552e\u8ba2\u5355\u6216\u524d\u7f6e\u8ba2\u5355");
        if (blankToNull(request.salesperson()) == null) throw new IllegalArgumentException("请填写销售员");
        if (!EDITABLE_STATUSES.contains(status(request))) throw new IllegalArgumentException("不支持的订单状态");
        if (request.items() == null || request.items().isEmpty()) throw new IllegalArgumentException("订单至少需要一条产品明细");
        for (SalesOrderRequest.Item item : request.items()) {
            if (item.skuId() == null || jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE id=? AND enabled=TRUE", Integer.class, item.skuId()) == 0) throw new IllegalArgumentException("订单中存在无效产品");
            if (item.quantity() == null || item.quantity() <= 0) throw new IllegalArgumentException("产品数量必须为正数");
            Map<String, Object> sku = jdbc.queryForMap("SELECT sku_code,sales_minimum_order_quantity FROM sku WHERE id=?", item.skuId());
            int minimum = ((Number) sku.get("sales_minimum_order_quantity")).intValue();
            if (item.quantity() < minimum) throw new IllegalArgumentException("产品 " + sku.get("sku_code") + " 的订单数量不能小于销售最小起订量 " + minimum);
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
    private String firstNonBlank(String preferred, String fallback) {
        String value = blankToNull(preferred);
        return value == null ? blankToNull(fallback) : value;
    }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private String status(SalesOrderRequest request) { return blankToNull(request.status()) == null ? "PENDING_CUSTOMER_PAYMENT" : request.status().trim(); }
}
