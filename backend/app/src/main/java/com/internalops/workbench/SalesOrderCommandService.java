package com.internalops.workbench;

import com.internalops.numbering.DocumentNumberService;
import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import com.internalops.customerfund.CustomerFundService;
import com.internalops.numbering.DocumentType;
import com.internalops.procurement.AutoProcurementSuggestionService;
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

@Service
public class SalesOrderCommandService {
    // 发货、库存锁定和开票状态必须由对应的业务动作推进，避免手工改状态绕过出入库流水。
    private static final Set<String> EDITABLE_STATUSES = Set.of("DRAFT", "PENDING_CUSTOMER_PAYMENT", "READY_TO_SHIP", "WAITING_STOCK", "SHIPPED");
    private static final Set<String> DELETABLE_STATUSES = Set.of("DRAFT", "PENDING_CUSTOMER_PAYMENT", "READY_TO_SHIP", "WAITING_STOCK");
    private static final Set<String> ORDER_TYPES = Set.of("\u5de5\u7a0b\u8ba2\u5355", "\u96f6\u552e\u8ba2\u5355", "\u524d\u7f6e\u8ba2\u5355");
    private final JdbcTemplate jdbc;
    private final InventoryAllocationService allocation;
    private final SupplyDemandQueryService supplyDemand;
    private final DocumentNumberService documentNumbers;
    private final AutoProcurementSuggestionService autoProcurement;
    private final CustomerFundService customerFunds;
    public SalesOrderCommandService(JdbcTemplate jdbc, InventoryAllocationService allocation,
                                    SupplyDemandQueryService supplyDemand, DocumentNumberService documentNumbers, AutoProcurementSuggestionService autoProcurement, CustomerFundService customerFunds) {
        this.jdbc = jdbc;
        this.allocation = allocation;
        this.supplyDemand = supplyDemand;
        this.documentNumbers = documentNumbers;
        this.autoProcurement = autoProcurement;
        this.customerFunds = customerFunds;
    }

    @Transactional
    public Map<String, Object> create(SalesOrderRequest request) {
        validate(request);
        String orderNo = documentNumbers.next(DocumentType.SALES_ORDER, request.orderDate());
        String orderContactName = firstNonBlank(request.orderContactName(), request.customerContact());
        String orderContactPhone = firstNonBlank(request.orderContactPhone(), request.customerPhone());
        long id = insert("INSERT INTO sales_order(order_no,external_order_no,customer_id,status,total_amount,order_date,order_type,salesperson,customer_contact,customer_phone,business_contact_name,business_contact_phone,order_contact_name,order_contact_phone,finance_contact_name,finance_contact_phone,order_remark,delivery_address,delivery_contact,delivery_phone,shipping_method) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                orderNo, blankToNull(request.externalOrderNo()), request.customerId(), status(request), total(request), request.orderDate(), trim(request.orderType()), trim(request.salesperson()), orderContactName, orderContactPhone,
                blankToNull(request.businessContactName()), blankToNull(request.businessContactPhone()), orderContactName, orderContactPhone, blankToNull(request.financeContactName()), blankToNull(request.financeContactPhone()),
                blankToNull(request.remark()), blankToNull(request.deliveryAddress()), blankToNull(request.deliveryContact()), blankToNull(request.deliveryPhone()), blankToNull(request.shippingMethod()));
        insertItems(id, request.items());
        if ("PENDING_CUSTOMER_PAYMENT".equals(status(request))) allocation.allocate(id);
        if (!"DRAFT".equals(status(request))) autoProcurement.requestRecalculation();
        return get(id);
    }

    public Map<String, Object> get(long id) {
        Map<String, Object> order = jdbc.queryForMap("SELECT id,order_no,external_order_no,customer_id,status,total_amount,order_date,order_type,salesperson,customer_contact,customer_phone,business_contact_name,business_contact_phone,order_contact_name,order_contact_phone,finance_contact_name,finance_contact_phone,order_remark,delivery_address,delivery_contact,delivery_phone,shipping_method,receipt_confirmed_at,shipped_at,carrier,tracking_no,shipping_remark,created_at,version FROM sales_order WHERE id=?", id);
        List<Map<String, Object>> items = jdbc.query("SELECT i.id,i.line_no,i.sku_id,s.product_code,s.customer_part_number,s.product_name,s.model,s.product_type,s.product_configuration,s.color,s.configuration,s.unit,i.quantity,i.shipped_quantity,i.locked_quantity,i.uncovered_quantity,i.sale_price,i.cost_snapshot,i.item_remark FROM sales_order_item i JOIN sku s ON s.id=i.sku_id WHERE i.sales_order_id=? ORDER BY i.line_no", (rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            int quantity = rs.getInt("quantity");
            int shipped = rs.getInt("shipped_quantity");
            m.put("id", rs.getLong("id")); m.put("lineNo", rs.getInt("line_no")); m.put("skuId", rs.getLong("sku_id"));
            m.put("remark", rs.getString("item_remark"));
            m.put("productCode", rs.getString("product_code")); m.put("customerPartNumber", rs.getString("customer_part_number")); m.put("productName", rs.getString("product_name")); m.put("model", rs.getString("model")); m.put("productType", rs.getString("product_type")); m.put("productConfiguration", rs.getString("product_configuration")); m.put("color", rs.getString("color")); m.put("configuration", rs.getString("configuration")); m.put("unit", rs.getString("unit"));
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
        Map<String, Object> customer = jdbc.queryForMap("SELECT customer_code,customer_name FROM customer WHERE id=?", value(order, "customer_id"));
        result.put("customerCode", customer.get("customer_code"));
        result.put("customerName", customer.get("customer_name"));
        BigDecimal receivableAmount = items.stream()
                .map(item -> BigDecimal.valueOf(((Number) item.get("quantity")).longValue()).multiply((BigDecimal) item.get("salePrice")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal receivedAmount = jdbc.queryForObject("SELECT COALESCE(SUM(COALESCE(confirmed_amount,amount)), 0) FROM customer_receipt WHERE sales_order_id=? AND COALESCE(review_status,'APPROVED')='APPROVED'", BigDecimal.class, id);
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
                SELECT s.id,s.shipment_no,s.delivery_address,s.operator_name,s.shipped_at,s.remark,s.logistics_company,s.logistics_no,s.logistics_remark,s.logistics_updated_at,
                       COALESCE(SUM(si.quantity),0) total_quantity
                FROM sales_shipment s
                LEFT JOIN sales_shipment_item si ON si.sales_shipment_id=s.id
                WHERE s.sales_order_id=?
                GROUP BY s.id,s.shipment_no,s.delivery_address,s.operator_name,s.shipped_at,s.remark,s.logistics_company,s.logistics_no,s.logistics_remark,s.logistics_updated_at
                ORDER BY s.shipped_at DESC,s.id DESC
                """, (rs, n) -> {
            Map<String, Object> shipment = new LinkedHashMap<>();
            shipment.put("id", rs.getLong("id"));
            shipment.put("shipmentNo", rs.getString("shipment_no"));
            shipment.put("deliveryAddress", rs.getString("delivery_address"));
            shipment.put("operatorName", rs.getString("operator_name"));
            shipment.put("shippedAt", rs.getTimestamp("shipped_at").toLocalDateTime());
            shipment.put("remark", rs.getString("remark"));
            shipment.put("logisticsCompany", rs.getString("logistics_company"));
            shipment.put("logisticsNo", rs.getString("logistics_no"));
            shipment.put("logisticsRemark", rs.getString("logistics_remark"));
            var logisticsUpdatedAt = rs.getTimestamp("logistics_updated_at");
            shipment.put("logisticsUpdatedAt", logisticsUpdatedAt == null ? null : logisticsUpdatedAt.toLocalDateTime());
            shipment.put("totalQuantity", rs.getInt("total_quantity"));
            return shipment;
        }, orderId);
        Map<Long, List<Map<String, Object>>> itemsByShipment = new HashMap<>();
        if (!shipments.isEmpty()) {
            List<Map<String, Object>> shipmentItems = jdbc.query("""
                    SELECT si.sales_shipment_id,si.id,si.sales_order_item_id,oi.line_no,oi.sku_id,
                           sku.product_code,sku.customer_part_number,sku.product_name,sku.model,sku.unit,si.quantity
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
                item.put("productCode", rs.getString("product_code")); item.put("customerPartNumber", rs.getString("customer_part_number"));
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
                SELECT s.id,s.product_code,s.customer_part_number,s.product_name,s.model,s.product_type,s.product_configuration,s.configuration,s.product_version,s.color,s.lock_body,s.unit,s.sales_minimum_order_quantity,s.current_cost,s.factory_price,
                       (SELECT pi.id FROM product_image pi
                        WHERE pi.product_id=s.id AND pi.is_primary=TRUE
                        ORDER BY pi.sort_order,pi.id LIMIT 1) AS primary_image_id
                FROM sku s
                WHERE s.enabled=TRUE
                ORDER BY s.product_code
                """, (rs, n) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getLong("id")); item.put("productCode", rs.getString("product_code")); item.put("customerPartNumber", rs.getString("customer_part_number")); item.put("productName", rs.getString("product_name"));
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
            item.put("supplyDemandSurplus", snapshot.supplyDemandSurplus());
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
        if (!EDITABLE_STATUSES.contains(currentStatus)) throw new IllegalStateException("确认收款后不可直接修改，只能走异常处理");
        int version = jdbc.queryForObject("SELECT version FROM sales_order WHERE id=?", Integer.class, id);
        if (version != request.version()) throw new IllegalStateException("数据已被其他操作修改，请重新打开后再试");
        long customerId = jdbc.queryForObject("SELECT customer_id FROM sales_order WHERE id=?", Long.class, id);
        if (!"DRAFT".equals(currentStatus) && customerId != request.customerId()) throw new IllegalArgumentException("正式订单不能更换客户，请保留原客户以确保发货和资金归属一致");
        if (!"DRAFT".equals(currentStatus) && "DRAFT".equals(request.status())) throw new IllegalArgumentException("正式订单不能改回草稿");
        int shipped = jdbc.queryForObject("SELECT COALESCE(SUM(shipped_quantity),0) FROM sales_order_item WHERE sales_order_id=?", Integer.class, id);
        if ((shipped > 0 || "SHIPPED".equals(currentStatus)) && request.receiptConfirmation() == null) throw new IllegalArgumentException("请再次确认已收金额是否需要修改");
        if (!"DRAFT".equals(currentStatus)) allocation.releaseAll(id, "ORDER_EDIT_RELEASE");
        String orderContactName = firstNonBlank(request.orderContactName(), request.customerContact());
        String orderContactPhone = firstNonBlank(request.orderContactPhone(), request.customerPhone());
        int changed = jdbc.update("UPDATE sales_order SET external_order_no=?,customer_id=?,status=?,total_amount=?,order_date=?,order_type=?,salesperson=?,customer_contact=?,customer_phone=?,business_contact_name=?,business_contact_phone=?,order_contact_name=?,order_contact_phone=?,finance_contact_name=?,finance_contact_phone=?,order_remark=?,delivery_address=?,delivery_contact=?,delivery_phone=?,shipping_method=?,version=version+1 WHERE id=? AND version=?",
                blankToNull(request.externalOrderNo()), request.customerId(), requestedStatusOrCurrent(request, currentStatus), total(request), request.orderDate(), trim(request.orderType()), trim(request.salesperson()), orderContactName, orderContactPhone,
                blankToNull(request.businessContactName()), blankToNull(request.businessContactPhone()), orderContactName, orderContactPhone, blankToNull(request.financeContactName()), blankToNull(request.financeContactPhone()),
                blankToNull(request.remark()), blankToNull(request.deliveryAddress()), blankToNull(request.deliveryContact()), blankToNull(request.deliveryPhone()), blankToNull(request.shippingMethod()), id, request.version());
        if (changed == 0) throw new IllegalStateException("数据已被其他操作修改，请重新打开后再试");
        updateItems(id, request.items());
        confirmReceipt(id, request.receiptConfirmation());
        if (!"DRAFT".equals(requestedStatusOrCurrent(request, currentStatus))) {
            allocation.allocate(id);
            reconcileCoverage(id);
            autoProcurement.requestRecalculation();
        }
        return get(id);
    }

    // Keep line IDs so shipment batches, after-sales and confirmed procurement remain traceable.
    private void updateItems(long orderId, List<SalesOrderRequest.Item> items) {
        Map<Integer, Map<String, Object>> existing = new HashMap<>();
        for (var row : jdbc.queryForList("SELECT id,line_no,sku_id,quantity,shipped_quantity FROM sales_order_item WHERE sales_order_id=? FOR UPDATE", orderId)) {
            existing.put(((Number)value(row,"line_no")).intValue(), row);
        }
        Set<Integer> used = new java.util.HashSet<>();
        int nextLine = 10000;
        for (var item : items) {
            int line = item.lineNo() == null ? nextLine : item.lineNo();
            nextLine = Math.max(nextLine, line + 10000);
            if (line <= 0 || !used.add(line)) throw new IllegalArgumentException("订单明细行号必须为不重复的正数");
            var old = existing.remove(line);
            if (old == null) {
                insertItems(orderId, List.of(new SalesOrderRequest.Item(line,item.skuId(),item.quantity(),item.salePrice(),item.remark())));
                continue;
            }
            long itemId = ((Number)value(old,"id")).longValue();
            int shipped = ((Number)value(old,"shipped_quantity")).intValue();
            boolean skuChanged = ((Number)value(old,"sku_id")).longValue() != item.skuId();
            if (shipped > 0 && (skuChanged || item.quantity() < shipped)) throw new IllegalArgumentException("已发货明细不能更换产品，订单数量不能小于已发货数量");
            if (skuChanged) {
                clearUnconfirmedCoverage(itemId);
                jdbc.update("UPDATE sales_order_item SET cost_snapshot=(SELECT current_cost FROM sku WHERE id=?) WHERE id=?", item.skuId(), itemId);
            }
            jdbc.update("UPDATE sales_order_item SET sku_id=?,quantity=?,sale_price=?,item_remark=?,version=version+1 WHERE id=?",
                    item.skuId(),item.quantity(),item.salePrice(),blankToNull(item.remark()),itemId);
        }
        for (var old : existing.values()) {
            if (((Number)value(old,"shipped_quantity")).intValue() > 0) throw new IllegalArgumentException("已发货明细不能删除");
            long itemId = ((Number)value(old,"id")).longValue();
            clearUnconfirmedCoverage(itemId);
            jdbc.update("DELETE FROM sales_order_item WHERE id=?", itemId);
        }
    }

    private void reconcileCoverage(long orderId) {
        for (var item : jdbc.queryForList("SELECT id,uncovered_quantity FROM sales_order_item WHERE sales_order_id=?",orderId)) {
            int remaining = ((Number)value(item,"uncovered_quantity")).intValue();
            var coverages = jdbc.queryForList("SELECT sc.id,sc.covered_quantity FROM shortage_coverage sc JOIN procurement_suggestion_item psi ON psi.id=sc.suggestion_item_id JOIN procurement_suggestion ps ON ps.id=psi.suggestion_id WHERE sc.sales_order_item_id=? AND sc.active=TRUE ORDER BY CASE WHEN ps.status='CONFIRMED' THEN 0 ELSE 1 END,sc.id FOR UPDATE",value(item,"id"));
            for (var coverage : coverages) {
                int quantity = Math.min(remaining,((Number)value(coverage,"covered_quantity")).intValue());
                if (quantity == 0) jdbc.update("UPDATE shortage_coverage SET active=FALSE WHERE id=?",value(coverage,"id"));
                else jdbc.update("UPDATE shortage_coverage SET covered_quantity=? WHERE id=?",quantity,value(coverage,"id"));
                remaining -= quantity;
            }
        }
    }

    private void clearUnconfirmedCoverage(long itemId) {
        int confirmed = jdbc.queryForObject("SELECT COUNT(*) FROM shortage_coverage sc JOIN procurement_suggestion_item psi ON psi.id=sc.suggestion_item_id JOIN procurement_suggestion ps ON ps.id=psi.suggestion_id WHERE sc.sales_order_item_id=? AND ps.status='CONFIRMED'",Integer.class,itemId);
        if (confirmed > 0) throw new IllegalArgumentException("已关联采购单的明细不能删除或更换产品，可调整数量和价格");
        jdbc.update("DELETE FROM shortage_coverage WHERE sales_order_item_id=?",itemId);
    }

    private void confirmReceipt(long orderId, SalesOrderRequest.ReceiptConfirmation confirmation) {
        if (confirmation == null) return;
        BigDecimal current = jdbc.queryForObject("SELECT COALESCE(SUM(COALESCE(confirmed_amount,amount)),0) FROM customer_receipt WHERE sales_order_id=? AND COALESCE(review_status,'APPROVED')='APPROVED'",BigDecimal.class,orderId);
        if (confirmation.originalAmount() == null || confirmation.originalAmount().compareTo(current) != 0) throw new IllegalStateException("已收金额已变化，请重新打开订单确认");
        BigDecimal target = confirmation.amount();
        if (target == null || target.signum() < 0 || target.stripTrailingZeros().scale() > 2 || target.precision()-target.scale() > 16) throw new IllegalArgumentException("已收金额必须为非负数，最多两位小数");
        BigDecimal delta = target.subtract(current);
        if (delta.signum() == 0) return;
        CurrentUser user = CurrentUser.required();
        if (user.role() != UserRole.ADMIN && user.role() != UserRole.FINANCE) throw new IllegalStateException("仅管理员或财务可调整已收金额");
        String reason = blankToNull(confirmation.reason());
        if (reason == null || reason.length() > 500) throw new IllegalArgumentException("请填写已收金额调整原因，最多500字");
        String status = jdbc.queryForObject("SELECT status FROM sales_order WHERE id=?",String.class,orderId);
        if ("DRAFT".equals(status)) throw new IllegalArgumentException("草稿订单不能调整已收金额");
        BigDecimal total = jdbc.queryForObject("SELECT total_amount FROM sales_order WHERE id=?",BigDecimal.class,orderId);
        if (delta.signum() > 0 && target.compareTo(total.max(BigDecimal.ZERO)) > 0) throw new IllegalArgumentException("调整后的已收金额不能超过订单金额");
        String auditReason = "订单修改：已收金额 " + current.toPlainString() + " → " + target.toPlainString() + "；" + reason;
        // Append the signed difference; never overwrite previously reviewed receipts.
        if (delta.signum() > 0) customerFunds.debitForOrder(orderId,delta,auditReason);
        else customerFunds.creditForOrder(orderId,delta.abs(),auditReason);
        jdbc.update("INSERT INTO customer_receipt(sales_order_id,amount,confirmed_amount,payment_method,payment_remark,received_at,confirmed_by,review_status,reviewed_by,reviewed_at,review_remark) VALUES(?,?,?,'订单调整',?,CURRENT_TIMESTAMP,?,'APPROVED',?,CURRENT_TIMESTAMP,?)",
                orderId,delta,delta,reason,user.id(),user.id(),reason);
        jdbc.update("UPDATE sales_order SET receipt_confirmed_at=CASE WHEN ?>0 THEN CURRENT_TIMESTAMP ELSE NULL END WHERE id=?",target,orderId);
    }

    @Transactional
    public Map<String, Object> review(long id) {
        String currentStatus = jdbc.queryForObject("SELECT status FROM sales_order WHERE id=? FOR UPDATE", String.class, id);
        if (!"DRAFT".equals(currentStatus)) throw new IllegalStateException("仅草稿订单可以复核");
        allocation.allocate(id);
        autoProcurement.requestRecalculation();
        return get(id);
    }

    @Transactional
    public void delete(long id) {
        String currentStatus = jdbc.queryForObject("SELECT status FROM sales_order WHERE id=? FOR UPDATE", String.class, id);
        if (!DELETABLE_STATUSES.contains(currentStatus)) throw new IllegalStateException("当前订单状态不可删除");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM customer_receipt WHERE sales_order_id=? AND COALESCE(review_status, 'APPROVED')='APPROVED'", Integer.class, id) > 0) {
            throw new IllegalStateException("已确认收款的订单不可删除");
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sales_shipment WHERE sales_order_id=?", Integer.class, id) > 0) {
            throw new IllegalStateException("已发货的订单不可删除");
        }
        if (jdbc.queryForObject("""
                SELECT COUNT(*) FROM shortage_coverage sc
                JOIN procurement_suggestion_item psi ON psi.id=sc.suggestion_item_id
                JOIN procurement_suggestion ps ON ps.id=psi.suggestion_id
                WHERE sc.active=TRUE AND ps.status='CONFIRMED'
                  AND sc.sales_order_item_id IN (SELECT id FROM sales_order_item WHERE sales_order_id=?)
                """, Integer.class, id) > 0) {
            throw new IllegalStateException("已生成采购单的订单不可删除");
        }
        if (!"DRAFT".equals(currentStatus)) allocation.releaseAll(id, "ORDER_DELETE_RELEASE");
        deleteShortageCoverage(id);
        jdbc.update("DELETE FROM customer_receipt WHERE sales_order_id=? AND review_status='PENDING'", id);
        jdbc.update("DELETE FROM sales_order_item WHERE sales_order_id=?", id);
        jdbc.update("DELETE FROM sales_order WHERE id=?", id);
        autoProcurement.requestRecalculation();
    }

    private void deleteShortageCoverage(long salesOrderId) {
        jdbc.update("DELETE FROM shortage_coverage WHERE sales_order_item_id IN (SELECT id FROM sales_order_item WHERE sales_order_id=?)", salesOrderId);
    }

    private void validate(SalesOrderRequest request) {
        if (request.customerId() == null || jdbc.queryForObject("SELECT COUNT(*) FROM customer WHERE id=? AND enabled=TRUE", Integer.class, request.customerId()) == 0) throw new IllegalArgumentException("请选择有效客户");
        if (request.orderDate() == null) throw new IllegalArgumentException("请选择订单日期");
        if (!ORDER_TYPES.contains(trim(request.orderType()))) throw new IllegalArgumentException("\u8ba2\u5355\u7c7b\u578b\u53ea\u80fd\u9009\u62e9\u5de5\u7a0b\u8ba2\u5355\u3001\u96f6\u552e\u8ba2\u5355\u6216\u524d\u7f6e\u8ba2\u5355");
        if (blankToNull(request.salesperson()) == null) throw new IllegalArgumentException("请填写销售员");
        if (!Set.of("DRAFT", "PENDING_CUSTOMER_PAYMENT").contains(status(request))) throw new IllegalArgumentException("不支持的订单状态");
        if (request.items() == null || request.items().isEmpty()) throw new IllegalArgumentException("订单至少需要一条产品明细");
        for (SalesOrderRequest.Item item : request.items()) {
            if (item.skuId() == null || jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE id=? AND enabled=TRUE", Integer.class, item.skuId()) == 0) throw new IllegalArgumentException("订单中存在无效产品");
            if (item.quantity() == null || item.quantity() == 0) throw new IllegalArgumentException("产品数量不能为零；退货请填写负数");
            if (item.salePrice() == null || item.salePrice().signum() < 0) throw new IllegalArgumentException("销售单价不能为负数");
            if (item.remark() != null && item.remark().length() > 1000) throw new IllegalArgumentException("明细备注不能超过1000个字符");
        }
    }

    private BigDecimal total(SalesOrderRequest request) { return request.items().stream().map(item -> item.salePrice().multiply(BigDecimal.valueOf(item.quantity()))).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2); }
    private void insertItems(long orderId, List<SalesOrderRequest.Item> items) {
        int nextLine = 10000;
        for (SalesOrderRequest.Item item : items) {
            int line = item.lineNo() != null && item.lineNo() > 0 ? item.lineNo() : nextLine;
            nextLine = Math.max(nextLine, line + 10000);
            BigDecimal cost = jdbc.queryForObject("SELECT current_cost FROM sku WHERE id=?", BigDecimal.class, item.skuId());
            jdbc.update("INSERT INTO sales_order_item(sales_order_id,line_no,sku_id,quantity,locked_quantity,uncovered_quantity,sale_price,cost_snapshot,item_remark) VALUES(?,?,?,?,0,0,?,?,?)", orderId, line, item.skuId(), item.quantity(), item.salePrice(), cost, blankToNull(item.remark()));
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
    private String requestedStatusOrCurrent(SalesOrderRequest request, String currentStatus) {
        return blankToNull(request.status()) == null ? currentStatus : request.status().trim();
    }
    private String status(SalesOrderRequest request) { return blankToNull(request.status()) == null ? "DRAFT" : request.status().trim(); }
}



