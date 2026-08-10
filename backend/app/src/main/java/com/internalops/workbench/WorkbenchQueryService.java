package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkbenchQueryService {
    private static final Map<String, String> CAMEL_KEYS = Map.ofEntries(
            Map.entry("customercode", "customerCode"), Map.entry("customername", "customerName"),
            Map.entry("contactname", "contactName"), Map.entry("displayname", "displayName"),
            Map.entry("updatedat", "updatedAt"), Map.entry("skucode", "skuCode"),
            Map.entry("productname", "productName"), Map.entry("lockbody", "lockBody"),
            Map.entry("productversion", "productVersion"), Map.entry("currentcost", "currentCost"),
            Map.entry("imagecount", "imageCount"), Map.entry("primaryimageid", "primaryImageId"),
            Map.entry("factoryprice", "factoryPrice"), Map.entry("pricedifference", "priceDifference"), Map.entry("remark", "remark"),
            Map.entry("supplierid", "supplierId"), Map.entry("suppliername", "supplierName"),
            Map.entry("suppliercode", "supplierCode"), Map.entry("bankaccount", "bankAccount"),
            Map.entry("manufacturercategory", "manufacturerCategory"), Map.entry("manufacturertype", "manufacturerType"),
            Map.entry("supplierlocation", "supplierLocation"), Map.entry("productattribute", "productAttribute"),
            Map.entry("shortname", "shortName"), Map.entry("contacttitle", "contactTitle"),
            Map.entry("taxregistrationno", "taxRegistrationNo"), Map.entry("bankaddress", "bankAddress"),
            Map.entry("productcount", "productCount"),
            Map.entry("purchaseprice", "purchasePrice"), Map.entry("leadtimedays", "leadTimeDays"),
            Map.entry("orderno", "orderNo"), Map.entry("externalorderno", "externalOrderNo"),
            Map.entry("totalamount", "totalAmount"), Map.entry("receiptconfirmedat", "receiptConfirmedAt"),
            Map.entry("shippedat", "shippedAt"), Map.entry("trackingno", "trackingNo"),
            Map.entry("createdat", "createdAt"), Map.entry("skuid", "skuId"),
            Map.entry("actualquantity", "actualQuantity"), Map.entry("lockedquantity", "lockedQuantity"),
            Map.entry("availablequantity", "availableQuantity"), Map.entry("intransitquantity", "inTransitQuantity"),
            Map.entry("lockedmingaijunqiao", "lockedMingAiJunQiao"), Map.entry("lockedbolelongmi", "lockedBoLeLongMi"),
            Map.entry("lockedlaos", "lockedLaos"), Map.entry("lockedbeilang", "lockedBeiLang"),
            Map.entry("lockedmalaysia", "lockedMalaysia"), Map.entry("movementcount", "movementCount"),
            Map.entry("transactiontype", "transactionType"), Map.entry("actualdelta", "actualDelta"),
            Map.entry("operatedat", "operatedAt"),
            Map.entry("sourcesuppliername", "sourceSupplierName"), Map.entry("inventoryremark", "inventoryRemark"),
            Map.entry("purchaseno", "purchaseNo"), Map.entry("expectedarrivaldate", "expectedArrivalDate"),
            Map.entry("recordtype", "recordType"),
            Map.entry("businesstype", "businessType"), Map.entry("businessno", "businessNo"),
            Map.entry("actiontype", "actionType"), Map.entry("cashdirection", "cashDirection"),
            Map.entry("settledamount", "settledAmount"), Map.entry("outstandingamount", "outstandingAmount"),
            Map.entry("paidamount", "paidAmount"), Map.entry("paymentstatus", "paymentStatus"),
            Map.entry("orderedquantity", "orderedQuantity"), Map.entry("receivedquantity", "receivedQuantity"),
            Map.entry("remainingquantity", "remainingQuantity"), Map.entry("receiptstatus", "receiptStatus"),
            Map.entry("productids", "productIds"), Map.entry("productsummary", "productSummary")
    );
    private final JdbcTemplate jdbc;
    private final Map<String, ModuleSpec> modules = new LinkedHashMap<>();

    public WorkbenchQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        registerModules();
    }

    public PageResult<Map<String, Object>> query(String module, ListQuery query) {
        ModuleSpec spec = modules.get(module);
        if (spec == null) {
            throw new IllegalArgumentException("不支持的业务模块");
        }
        String sortColumn;
        if (query.sort().isBlank()) {
            sortColumn = spec.defaultSort();
        } else {
            sortColumn = spec.sortColumns().get(query.sort());
            if (sortColumn == null) {
                throw new IllegalArgumentException("不支持的排序字段");
            }
        }
        List<Object> parameters = new ArrayList<>();
        String where = "";
        if (!query.keyword().isBlank()) {
            where = " WHERE " + spec.keywordPredicate();
            for (int i = 0; i < spec.keywordParameterCount(); i++) {
                parameters.add(query.keyword());
            }
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) " + spec.fromClause() + where,
                Long.class, parameters.toArray());
        List<Object> itemParameters = new ArrayList<>(parameters);
        itemParameters.add(ListQuery.PAGE_SIZE);
        itemParameters.add(query.offset());
        String sql = spec.selectClause() + " " + spec.fromClause() + where
                + " ORDER BY " + sortColumn + " " + query.direction() + ", " + spec.tieBreaker()
                + " LIMIT ? OFFSET ?";
        List<Map<String, Object>> items = jdbc.queryForList(sql, itemParameters.toArray()).stream()
                .map(this::normalizeKeys).toList();
        if ("inventory".equals(module)) {
            items = items.stream().map(row -> {
                Map<String, Object> item = new LinkedHashMap<>(row);
                item.put("movementSummary", inventoryMovementSummary(((Number) item.get("id")).longValue()));
                return item;
            }).toList();
        }
        if ("product".equals(module)) {
            items = items.stream().map(row -> {
                Map<String, Object> item = new LinkedHashMap<>(row);
                Object primaryImageId = item.get("primaryImageId");
                item.put("primaryImageUrl", primaryImageId == null
                        ? null : "/api/product-images/" + primaryImageId + "/content");
                return item;
            }).toList();
        }
        return PageResult.of(items, total == null ? 0 : total, query.page());
    }

    private Map<String, Object> normalizeKeys(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(CAMEL_KEYS.getOrDefault(key.toLowerCase(), key), value));
        return normalized;
    }

    public List<Map<String, Object>> inventoryMovements(long inventoryId) {
        String sql = "SELECT tx.operated_at AS `operatedAt`, tx.transaction_type AS `transactionType`, "
                + "tx.actual_delta AS `actualDelta`, tx.business_no AS `businessNo` "
                + "FROM inventory_transaction tx JOIN inventory_balance b "
                + "ON b.warehouse_id=tx.warehouse_id AND b.sku_id=tx.sku_id "
                + "WHERE b.id=? AND tx.transaction_type IN ('EXCEL_INBOUND','EXCEL_OUTBOUND','MANUAL_INBOUND','MANUAL_OUTBOUND') "
                + "ORDER BY tx.operated_at ASC, tx.id ASC";
        return jdbc.queryForList(sql, inventoryId).stream().map(this::normalizeKeys).map(row -> {
            Map<String, Object> movement = new LinkedHashMap<>();
            Object operatedAt = row.get("operatedAt");
            String date = operatedAt instanceof Timestamp timestamp
                    ? timestamp.toLocalDateTime().toLocalDate().toString() : String.valueOf(operatedAt).substring(0, 10);
            String transactionType = String.valueOf(row.get("transactionType"));
            String businessNo = String.valueOf(row.get("businessNo"));
            int firstSeparator = businessNo.indexOf(':');
            movement.put("date", date);
            movement.put("direction", Set.of("EXCEL_INBOUND", "MANUAL_INBOUND").contains(transactionType) ? "入库" : "出库");
            movement.put("quantity", Math.abs(((Number) row.get("actualDelta")).intValue()));
            movement.put("sourceColumn", firstSeparator < 0 ? businessNo : businessNo.substring(firstSeparator + 1));
            return movement;
        }).toList();
    }

    public List<Map<String, Object>> supplierOptions(String keyword) {
        String search = keyword == null ? "" : keyword.trim();
        return jdbc.queryForList("""
                        SELECT id,supplier_code AS `supplierCode`,supplier_name AS `supplierName`,
                               contact_name AS `contactName`,phone
                        FROM supplier
                        WHERE enabled=TRUE
                          AND (LOCATE(?,COALESCE(supplier_name,''))>0
                               OR LOCATE(?,COALESCE(supplier_code,''))>0
                               OR LOCATE(?,COALESCE(contact_name,''))>0)
                        ORDER BY supplier_name,id
                        LIMIT 30
                        """, search, search, search).stream().map(this::normalizeKeys).toList();
    }

    public List<Map<String, Object>> supplierProducts(long supplierId, String keyword) {
        String search = keyword == null ? "" : keyword.trim();
        return jdbc.queryForList("""
                        SELECT s.id,s.sku_code AS `skuCode`,s.product_name AS `productName`,s.model,
                               s.configuration,s.unit,ssc.purchase_price AS `purchasePrice`,
                               ssc.moq,ssc.lead_time_days AS `leadTimeDays`
                        FROM sku_supplier_config ssc
                        JOIN sku s ON s.id=ssc.sku_id
                        WHERE ssc.supplier_id=? AND ssc.enabled=TRUE AND s.enabled=TRUE
                          AND (LOCATE(?,COALESCE(s.sku_code,''))>0
                               OR LOCATE(?,COALESCE(s.product_name,''))>0
                               OR LOCATE(?,COALESCE(s.model,''))>0)
                        ORDER BY s.sku_code,s.id
                        LIMIT 50
                        """, supplierId, search, search, search).stream().map(this::normalizeKeys).toList();
    }

    public Map<String, Object> supplierDetail(long supplierId) {
        List<Map<String, Object>> suppliers = jdbc.queryForList("""
                        SELECT id,supplier_code AS `supplierCode`,supplier_name AS `supplierName`,
                               manufacturer_category AS `manufacturerCategory`,manufacturer_type AS `manufacturerType`,
                               supplier_location AS `supplierLocation`,product_attribute AS `productAttribute`,
                               short_name AS `shortName`,contact_name AS `contactName`,contact_title AS `contactTitle`,
                               phone,address,currency,tax_registration_no AS `taxRegistrationNo`,
                               bank_account AS `bankAccount`,bank_address AS `bankAddress`,
                               enabled,version,updated_at AS `updatedAt`
                        FROM supplier WHERE id=?
                        """, supplierId).stream().map(this::normalizeKeys).toList();
        if (suppliers.isEmpty()) {
            throw new IllegalArgumentException("供应商不存在");
        }
        Map<String, Object> supplier = new LinkedHashMap<>(suppliers.get(0));
        supplier.put("products", jdbc.queryForList("""
                        SELECT s.id AS `skuId`,s.sku_code AS `skuCode`,s.product_name AS `productName`,
                               s.model,s.configuration,s.unit,ssc.purchase_price AS `purchasePrice`,
                               ssc.moq,ssc.lead_time_days AS `leadTimeDays`
                        FROM sku_supplier_config ssc
                        JOIN sku s ON s.id=ssc.sku_id
                        WHERE ssc.supplier_id=? AND ssc.enabled=TRUE
                        ORDER BY s.sku_code,s.id
                        """, supplierId).stream().map(this::normalizeKeys).toList());
        return supplier;
    }

    private String inventoryMovementSummary(long inventoryId) {
        List<Map<String, Object>> movements = inventoryMovements(inventoryId);
        if (movements.isEmpty()) return "—";
        StringBuilder summary = new StringBuilder();
        int visible = Math.min(3, movements.size());
        for (int index = 0; index < visible; index++) {
            Map<String, Object> movement = movements.get(index);
            if (index > 0) summary.append('；');
            String date = String.valueOf(movement.get("date"));
            summary.append(date.length() >= 10 ? date.substring(5).replace('-', '/') : date)
                    .append(' ').append(movement.get("direction"))
                    .append(' ').append(movement.get("quantity"));
        }
        if (movements.size() > visible) summary.append("；…");
        return summary.toString();
    }

    private void registerModules() {
        modules.put("customer", new ModuleSpec(
                "SELECT c.id, c.customer_code AS `customerCode`, c.customer_name AS `customerName`, "
                        + "c.contact_name AS `contactName`, c.phone, c.address, c.enabled, c.updated_at AS `updatedAt`, c.version",
                "FROM customer c", "LOCATE(?, COALESCE(c.customer_code,''))>0 OR LOCATE(?, COALESCE(c.customer_name,''))>0 OR LOCATE(?, COALESCE(c.contact_name,''))>0 OR LOCATE(?, COALESCE(c.phone,''))>0", 4,
                sorts("id", "c.id", "customerCode", "c.customer_code", "customerName", "c.customer_name", "updatedAt", "c.updated_at"),
                "c.updated_at", "c.id DESC"));
        modules.put("user", new ModuleSpec(
                "SELECT u.id, u.username, u.display_name AS `displayName`, u.phone, u.enabled, u.role, u.updated_at AS `updatedAt`, u.version",
                "FROM sys_user u", "LOCATE(?, COALESCE(u.username,''))>0 OR LOCATE(?, COALESCE(u.display_name,''))>0 OR LOCATE(?, COALESCE(u.phone,''))>0", 3,
                sorts("id", "u.id", "username", "u.username", "displayName", "u.display_name", "updatedAt", "u.updated_at"),
                "u.updated_at", "u.id DESC"));
        modules.put("product", new ModuleSpec(
                "SELECT s.id, s.sku_code AS `skuCode`, s.model, s.product_name AS `productName`, s.color, "
                        + "s.lock_body AS `lockBody`, s.product_version AS `productVersion`, s.configuration, s.unit, "
                        + "s.current_cost AS `currentCost`, s.factory_price AS `factoryPrice`, (s.factory_price-s.current_cost) AS `priceDifference`, s.product_remark AS remark, s.enabled, s.updated_at AS `updatedAt`, s.version, "
                        + "(SELECT COUNT(*) FROM product_image pi WHERE pi.product_id=s.id) AS `imageCount`, "
                        + "(SELECT pi.id FROM product_image pi WHERE pi.product_id=s.id AND pi.is_primary=TRUE LIMIT 1) AS `primaryImageId`, "
                        + "ssc.supplier_id AS `supplierId`, sp.supplier_name AS `supplierName`, ssc.purchase_price AS `purchasePrice`, "
                        + "ssc.moq, ssc.lead_time_days AS `leadTimeDays`",
                "FROM sku s LEFT JOIN sku_supplier_config ssc ON ssc.sku_id=s.id AND ssc.enabled=TRUE "
                        + "LEFT JOIN supplier sp ON sp.id=ssc.supplier_id",
                "LOCATE(?, COALESCE(s.sku_code,''))>0 OR LOCATE(?, COALESCE(s.model,''))>0 OR LOCATE(?, COALESCE(s.product_name,''))>0 OR LOCATE(?, COALESCE(s.configuration,''))>0", 4,
                sorts("id", "s.id", "skuCode", "s.sku_code", "model", "s.model", "productName", "s.product_name", "currentCost", "s.current_cost", "factoryPrice", "s.factory_price", "priceDifference", "(s.factory_price-s.current_cost)", "updatedAt", "s.updated_at"),
                "s.updated_at", "s.id DESC"));
        modules.put("supplier", new ModuleSpec(
                "SELECT sp.id,sp.supplier_code AS `supplierCode`,sp.supplier_name AS `supplierName`,"
                        + "sp.manufacturer_category AS `manufacturerCategory`,sp.manufacturer_type AS `manufacturerType`,"
                        + "sp.supplier_location AS `supplierLocation`,sp.product_attribute AS `productAttribute`,"
                        + "sp.short_name AS `shortName`,sp.contact_name AS `contactName`,sp.contact_title AS `contactTitle`,"
                        + "sp.phone,sp.address,sp.currency,sp.tax_registration_no AS `taxRegistrationNo`,"
                        + "sp.bank_account AS `bankAccount`,sp.bank_address AS `bankAddress`,sp.enabled,"
                        + "(SELECT COUNT(*) FROM sku_supplier_config cfg WHERE cfg.supplier_id=sp.id AND cfg.enabled=TRUE) AS `productCount`,"
                        + "sp.updated_at AS `updatedAt`,sp.version",
                "FROM supplier sp",
                "LOCATE(?,COALESCE(sp.supplier_code,''))>0 OR LOCATE(?,COALESCE(sp.supplier_name,''))>0 OR LOCATE(?,COALESCE(sp.contact_name,''))>0 OR LOCATE(?,COALESCE(sp.phone,''))>0", 4,
                sorts("id", "sp.id", "supplierName", "sp.supplier_name", "contactName", "sp.contact_name", "phone", "sp.phone", "updatedAt", "sp.updated_at"),
                "sp.updated_at", "sp.id DESC"));
        modules.put("order", new ModuleSpec(
                "SELECT o.id, o.order_no AS `orderNo`, o.external_order_no AS `externalOrderNo`, c.customer_name AS `customerName`, "
                        + "o.total_amount AS `totalAmount`, o.order_date AS `orderDate`, o.order_type AS `orderType`, o.salesperson, o.status, o.receipt_confirmed_at AS `receiptConfirmedAt`, "
                        + "o.shipped_at AS `shippedAt`, o.carrier, o.tracking_no AS `trackingNo`, o.created_at AS `createdAt`, o.updated_at AS `updatedAt`, o.version",
                "FROM sales_order o JOIN customer c ON c.id=o.customer_id",
                "LOCATE(?, COALESCE(o.order_no,''))>0 OR LOCATE(?, COALESCE(o.external_order_no,''))>0 OR LOCATE(?, COALESCE(c.customer_name,''))>0 OR LOCATE(?, COALESCE(o.status,''))>0", 4,
                sorts("id", "o.id", "orderNo", "o.order_no", "customerName", "c.customer_name", "totalAmount", "o.total_amount", "status", "o.status", "createdAt", "o.created_at", "updatedAt", "o.updated_at"),
                "o.updated_at", "o.id DESC"));
        modules.put("inventory", new ModuleSpec(
                "SELECT b.id, b.sku_id AS `skuId`, s.sku_code AS `skuCode`, s.model, s.configuration, s.product_version AS `productVersion`, s.color, s.lock_body AS `lockBody`, s.unit, "
                        + "b.actual_quantity AS `actualQuantity`, b.locked_quantity AS `lockedQuantity`, "
                        + "COALESCE((SELECT a.quantity FROM inventory_locked_allocation a WHERE a.inventory_balance_id=b.id AND a.lock_source='铭爱钧乔'),0) AS `lockedMingAiJunQiao`, "
                        + "COALESCE((SELECT a.quantity FROM inventory_locked_allocation a WHERE a.inventory_balance_id=b.id AND a.lock_source='博乐龙米'),0) AS `lockedBoLeLongMi`, "
                        + "COALESCE((SELECT a.quantity FROM inventory_locked_allocation a WHERE a.inventory_balance_id=b.id AND a.lock_source='老挝'),0) AS `lockedLaos`, "
                        + "COALESCE((SELECT a.quantity FROM inventory_locked_allocation a WHERE a.inventory_balance_id=b.id AND a.lock_source='贝朗'),0) AS `lockedBeiLang`, "
                        + "COALESCE((SELECT a.quantity FROM inventory_locked_allocation a WHERE a.inventory_balance_id=b.id AND a.lock_source='马来西亚'),0) AS `lockedMalaysia`, "
                        + "(b.actual_quantity+b.in_transit_quantity-b.locked_quantity) AS `availableQuantity`, b.in_transit_quantity AS `inTransitQuantity`, "
                        + "(SELECT COUNT(*) FROM inventory_transaction tx WHERE tx.warehouse_id=b.warehouse_id AND tx.sku_id=b.sku_id AND tx.business_type='EXCEL_IMPORT_HISTORY') AS `movementCount`, "
                        + "b.source_supplier_name AS `sourceSupplierName`, b.inventory_remark AS `inventoryRemark`, b.updated_at AS `updatedAt`, b.version",
                "FROM inventory_balance b JOIN sku s ON s.id=b.sku_id",
                "LOCATE(?, COALESCE(s.sku_code,''))>0 OR LOCATE(?, COALESCE(s.model,''))>0 OR LOCATE(?, COALESCE(s.configuration,''))>0 OR LOCATE(?, COALESCE(b.source_supplier_name,''))>0", 4,
                sorts("id", "b.id", "skuCode", "s.sku_code", "model", "s.model", "actualQuantity", "b.actual_quantity", "availableQuantity", "(b.actual_quantity+b.in_transit_quantity-b.locked_quantity)", "updatedAt", "b.updated_at"),
                "b.updated_at", "b.id DESC"));
        String purchaseView = "FROM ("
                + "SELECT po.id, 'PURCHASE' AS record_type, po.purchase_no, po.supplier_id, sp.supplier_name, "
                + "GROUP_CONCAT(poi.sku_id ORDER BY poi.line_no SEPARATOR ', ') AS product_ids, "
                + "GROUP_CONCAT(COALESCE(NULLIF(s.product_name,''), '未命名产品') ORDER BY poi.line_no SEPARATOR '；') AS product_summary, "
                + "po.status, po.total_amount, "
                + "COALESCE((SELECT SUM(pay.amount) FROM supplier_payment pay WHERE pay.purchase_order_id=po.id),0) AS paid_amount, "
                + "COALESCE(SUM(poi.quantity),0) AS ordered_quantity, COALESCE(SUM(poi.received_quantity),0) AS received_quantity, "
                + "po.expected_arrival_date, po.created_at, po.updated_at, po.version "
                + "FROM purchase_order po JOIN supplier sp ON sp.id=po.supplier_id "
                + "LEFT JOIN purchase_order_item poi ON poi.purchase_order_id=po.id LEFT JOIN sku s ON s.id=poi.sku_id "
                + "GROUP BY po.id,po.purchase_no,po.supplier_id,sp.supplier_name,po.status,po.total_amount,po.expected_arrival_date,po.created_at,po.updated_at,po.version "
                + "UNION ALL "
                + "SELECT ps.id, 'SUGGESTION', ps.suggestion_no, psi.supplier_id, sp.supplier_name, "
                + "GROUP_CONCAT(psi.sku_id ORDER BY psi.id SEPARATOR ', '), "
                + "GROUP_CONCAT(COALESCE(NULLIF(s.product_name,''), '未命名产品') ORDER BY psi.id SEPARATOR '；'), "
                + "ps.status, SUM(psi.suggested_quantity*psi.purchase_price), 0, SUM(psi.suggested_quantity), 0, MAX(psi.expected_arrival_date), ps.created_at, ps.updated_at, ps.version "
                + "FROM procurement_suggestion ps JOIN procurement_suggestion_item psi ON psi.suggestion_id=ps.id "
                + "JOIN supplier sp ON sp.id=psi.supplier_id JOIN sku s ON s.id=psi.sku_id "
                + "WHERE ps.status='DRAFT' "
                + "GROUP BY ps.id,ps.suggestion_no,psi.supplier_id,sp.supplier_name,ps.status,ps.created_at,ps.updated_at,ps.version) p";
        modules.put("purchase", new ModuleSpec(
                "SELECT p.id, p.record_type AS `recordType`, p.purchase_no AS `purchaseNo`, "
                        + "p.supplier_name AS `supplierName`, p.product_summary AS `productSummary`, p.status, p.total_amount AS `totalAmount`, "
                        + "p.paid_amount AS `paidAmount`, GREATEST(p.total_amount-p.paid_amount,0) AS `outstandingAmount`, "
                        + "CASE WHEN p.paid_amount<=0 THEN 'UNPAID' WHEN p.paid_amount>=p.total_amount THEN 'PAID' ELSE 'PARTIALLY_PAID' END AS `paymentStatus`, "
                        + "p.ordered_quantity AS `orderedQuantity`, p.received_quantity AS `receivedQuantity`, GREATEST(p.ordered_quantity-p.received_quantity,0) AS `remainingQuantity`, "
                        + "CASE WHEN p.received_quantity<=0 THEN 'UNRECEIVED' WHEN p.received_quantity>=p.ordered_quantity THEN 'RECEIVED' ELSE 'PARTIALLY_RECEIVED' END AS `receiptStatus`, "
                        + "p.expected_arrival_date AS `expectedArrivalDate`, p.created_at AS `createdAt`, p.updated_at AS `updatedAt`, p.version",
                purchaseView,
                "LOCATE(?, COALESCE(p.purchase_no,''))>0 OR LOCATE(?, COALESCE(p.supplier_name,''))>0 OR LOCATE(?, COALESCE(p.product_summary,''))>0 OR LOCATE(?, COALESCE(p.status,''))>0", 4,
                sorts("id", "p.id", "purchaseNo", "p.purchase_no", "supplierName", "p.supplier_name", "totalAmount", "p.total_amount", "status", "p.status", "createdAt", "p.created_at", "updatedAt", "p.updated_at"),
                "p.updated_at", "p.record_type, p.id DESC"));
        String financeView = "FROM ("
                + "SELECT o.id, 'RECEIVABLE' AS cash_direction, '销售订单' AS business_type, o.order_no AS business_no, c.customer_name AS counterparty, "
                + "COALESCE((SELECT SUM((i.quantity-i.shipped_quantity)*i.sale_price) FROM sales_order_item i WHERE i.sales_order_id=o.id),0) AS amount, "
                + "COALESCE((SELECT SUM(cr.amount) FROM customer_receipt cr WHERE cr.sales_order_id=o.id),0) AS settled_amount, "
                + "o.created_at, o.updated_at FROM sales_order o JOIN customer c ON c.id=o.customer_id WHERE o.status<>'DRAFT' "
                + "UNION ALL "
                + "SELECT p.id, 'PAYABLE', '采购订单', p.purchase_no, sp.supplier_name, p.total_amount, "
                + "COALESCE((SELECT SUM(spay.amount) FROM supplier_payment spay WHERE spay.purchase_order_id=p.id),0), "
                + "p.created_at, p.updated_at FROM purchase_order p JOIN supplier sp ON sp.id=p.supplier_id) f";
        modules.put("finance", new ModuleSpec(
                "SELECT f.id, f.cash_direction AS `cashDirection`, f.business_type AS `businessType`, f.business_no AS `businessNo`, f.counterparty, f.amount, f.settled_amount AS `settledAmount`, "
                        + "GREATEST(f.amount-f.settled_amount,0) AS `outstandingAmount`, "
                        + "CASE WHEN f.settled_amount>=f.amount THEN CASE WHEN f.cash_direction='RECEIVABLE' THEN '已收清' ELSE '已付清' END "
                        + "WHEN f.cash_direction='RECEIVABLE' THEN '待收款' ELSE '待付款' END AS status, "
                        + "CASE WHEN f.cash_direction='RECEIVABLE' THEN '登记收款' ELSE '登记付款' END AS `actionType`, f.created_at AS `createdAt`, f.updated_at AS `updatedAt`",
                financeView, "LOCATE(?, COALESCE(f.business_no,''))>0 OR LOCATE(?, COALESCE(f.counterparty,''))>0 OR LOCATE(?, COALESCE(f.cash_direction,''))>0", 3,
                sorts("id", "f.id", "businessNo", "f.business_no", "counterparty", "f.counterparty", "amount", "f.amount", "settledAmount", "f.settled_amount", "outstandingAmount", "(f.amount-f.settled_amount)", "updatedAt", "f.updated_at"),
                "f.updated_at", "f.cash_direction, f.id DESC"));
    }

    private Map<String, String> sorts(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(values[i], values[i + 1]);
        }
        return Map.copyOf(result);
    }

    private record ModuleSpec(String selectClause, String fromClause, String keywordPredicate,
                              int keywordParameterCount, Map<String, String> sortColumns,
                              String defaultSort, String tieBreaker) {
    }
}
