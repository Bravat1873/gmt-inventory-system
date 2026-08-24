package com.internalops.workbench;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class WorkbenchQueryService {
    private static final Set<String> FIFO_INBOUND_TRANSACTION_TYPES = Set.of(
            "EXCEL_INBOUND", "MANUAL_INBOUND", "PURCHASE_RECEIPT");
    private static final Set<String> FIFO_OUTBOUND_TRANSACTION_TYPES = Set.of(
            "EXCEL_OUTBOUND", "MANUAL_OUTBOUND", "SALES_SHIPMENT");
    private static final Set<String> FIFO_SIGNED_TRANSACTION_TYPES = Set.of("MANUAL_ADJUST");
    private static final Set<String> FIFO_IGNORED_TRANSACTION_TYPES = Set.of(
            "INITIAL_IMPORT", "ALLOCATE", "PURCHASE_TRANSIT");
    private static final Set<String> INVENTORY_MOVEMENT_TRANSACTION_TYPES = Set.of(
            "EXCEL_INBOUND", "EXCEL_OUTBOUND", "MANUAL_INBOUND", "MANUAL_OUTBOUND");
    private static final Set<String> INVENTORY_MOVEMENT_INBOUND_TYPES = Set.of("EXCEL_INBOUND", "MANUAL_INBOUND");
    private static final Map<String, String> CAMEL_KEYS = Map.ofEntries(
            Map.entry("customercode", "customerCode"), Map.entry("customername", "customerName"),
            Map.entry("contactname", "contactName"), Map.entry("displayname", "displayName"),
            Map.entry("businesscontactname", "businessContactName"), Map.entry("businesscontactphone", "businessContactPhone"),
            Map.entry("ordercontactname", "orderContactName"), Map.entry("ordercontactphone", "orderContactPhone"),
            Map.entry("financecontactname", "financeContactName"), Map.entry("financecontactphone", "financeContactPhone"),
            Map.entry("invoicetitle", "invoiceTitle"), Map.entry("taxpayerid", "taxpayerId"),
            Map.entry("invoiceaddress", "invoiceAddress"), Map.entry("invoicephone", "invoicePhone"), Map.entry("bankname", "bankName"),
            Map.entry("contractstatus", "contractStatus"), Map.entry("contractenddate", "contractEndDate"),
            Map.entry("updatedat", "updatedAt"), Map.entry("customerpartnumber", "customerPartNumber"), Map.entry("productcode", "productCode"),
            Map.entry("producttype", "productType"), Map.entry("productconfiguration", "productConfiguration"),
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
            Map.entry("relationid", "relationId"), Map.entry("purchaseprice", "purchasePrice"), Map.entry("leadtimedays", "leadTimeDays"),
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
            Map.entry("productids", "productIds"), Map.entry("productsummary", "productSummary"),
            Map.entry("aftersalesno", "afterSalesNo"), Map.entry("aftersalestype", "afterSalesType"),
            Map.entry("returnquantity", "returnQuantity"), Map.entry("replacementquantity", "replacementQuantity"),
            Map.entry("applicationdate", "applicationDate")
    );
    private final JdbcTemplate jdbc;
    private final SupplyDemandQueryService supplyDemand;
    private final InventoryAgeCalculator inventoryAgeCalculator = new InventoryAgeCalculator();
    private final Map<String, ModuleSpec> modules = new LinkedHashMap<>();

    public WorkbenchQueryService(JdbcTemplate jdbc, SupplyDemandQueryService supplyDemand) {
        this.jdbc = jdbc;
        this.supplyDemand = supplyDemand;
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
        String where = "supplier".equals(module) ? " WHERE sp.enabled=TRUE" : "";
        if (!query.keyword().isBlank()) {
            where += where.isBlank() ? " WHERE " : " AND ";
            where += spec.keywordPredicate();
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
            Map<Long, List<InventoryTransactionRow>> transactionsByInventoryId = inventoryTransactions(items);
            Map<Long, List<Map<String, Object>>> allocationsByInventoryId = inventoryLockedAllocations(items);
            Map<Long, SupplyDemandQueryService.SupplyDemandSnapshot> supplyBySku = supplyDemand.bySkuIds(
                    items.stream().map(item -> ((Number) item.get("skuId")).longValue()).toList());
            items = items.stream().map(row -> {
                Map<String, Object> item = new LinkedHashMap<>(row);
                long inventoryId = ((Number) item.get("id")).longValue();
                List<InventoryTransactionRow> transactions = transactionsByInventoryId.getOrDefault(inventoryId, List.of());
                item.put("lockedAllocations", allocationsByInventoryId.getOrDefault(inventoryId, List.of()));
                item.put("movementSummary", inventoryMovementSummary(transactions));
                var supply = supplyBySku.get(((Number) item.get("skuId")).longValue());
                int pending = supply.pendingDeliveryQuantity();
                int balance = ((Number) item.get("actualQuantity")).intValue()
                        + ((Number) item.get("inTransitQuantity")).intValue() - pending;
                item.put("pendingDeliveryQuantity", pending);
                item.put("supplyDemandSurplus", balance);
                item.put("purchaseShortageQuantity", Math.max(-balance, 0));
                inventoryAge(transactions, ((Number) item.get("actualQuantity")).intValue()).ifPresentOrElse(
                        age -> {
                            item.put("oldestStockDate", age.oldestStockDate());
                            item.put("inventoryAgeDays", age.inventoryAgeDays());
                        },
                        () -> {
                            item.put("oldestStockDate", null);
                            item.put("inventoryAgeDays", null);
                        });
                return item;
            }).toList();
        }
        if ("product".equals(module)) {
            Map<Long, List<Map<String, Object>>> quotesBySku = supplierQuotes(items);
            items = items.stream().map(row -> {
                Map<String, Object> item = new LinkedHashMap<>(row);
                Object primaryImageId = item.get("primaryImageId");
                item.put("primaryImageUrl", primaryImageId == null
                        ? null : "/api/product-images/" + primaryImageId + "/content");
                List<Map<String, Object>> quotes = quotesBySku.getOrDefault(((Number) item.get("id")).longValue(), List.of());
                item.put("supplierQuotes", quotes);
                if (!quotes.isEmpty()) {
                    Map<String, Object> first = quotes.get(0);
                    item.put("supplierId", first.get("supplierId"));
                    item.put("supplierName", first.get("supplierName"));
                    item.put("purchasePrice", first.get("purchasePrice"));
                    item.put("moq", first.get("moq"));
                    item.put("leadTimeDays", first.get("leadTimeDays"));
                }
                return item;
            }).toList();
        }
        return PageResult.of(items, total == null ? 0 : total, query.page());
    }

    private Map<Long, List<Map<String, Object>>> supplierQuotes(List<Map<String, Object>> products) {
        Map<Long, List<Map<String, Object>>> result = new LinkedHashMap<>();
        List<Long> skuIds = products.stream().map(item -> ((Number) item.get("id")).longValue()).toList();
        if (skuIds.isEmpty()) return result;
        String placeholders = String.join(",", skuIds.stream().map(id -> "?").toList());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT cfg.sku_id AS `skuId`,sp.id AS `supplierId`,sp.supplier_name AS `supplierName`,
                       cfg.purchase_price AS `purchasePrice`,cfg.moq,cfg.lead_time_days AS `leadTimeDays`
                FROM sku_supplier_config cfg
                JOIN supplier sp ON sp.id=cfg.supplier_id
                JOIN sku s ON s.id=cfg.sku_id
                WHERE cfg.enabled=TRUE AND sp.enabled=TRUE AND s.enabled=TRUE
                  AND cfg.sku_id IN (%s)
                ORDER BY sp.supplier_name,sp.id
                """.formatted(placeholders), skuIds.toArray()).stream().map(this::normalizeKeys).toList();
        for (Map<String, Object> row : rows) {
            long skuId = ((Number) row.remove("skuId")).longValue();
            result.computeIfAbsent(skuId, ignored -> new ArrayList<>()).add(row);
        }
        return result;
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
            movement.put("direction", INVENTORY_MOVEMENT_INBOUND_TYPES.contains(transactionType) ? "入库" : "出库");
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
                        SELECT s.id,s.customer_part_number AS `customerPartNumber`,s.model,
                               s.product_code AS `productCode`,
                               s.configuration,s.unit,ssc.purchase_price AS `purchasePrice`,
                               ssc.moq,ssc.lead_time_days AS `leadTimeDays`
                        FROM sku_supplier_config ssc
                        JOIN sku s ON s.id=ssc.sku_id
                        WHERE ssc.supplier_id=? AND ssc.enabled=TRUE AND s.enabled=TRUE
                          AND (LOCATE(?,COALESCE(s.customer_part_number,''))>0
                               OR LOCATE(?,COALESCE(s.model,''))>0
                               OR LOCATE(?,COALESCE(s.product_code,''))>0)
                        ORDER BY s.customer_part_number,s.id
                        LIMIT 50
                        """, supplierId, search, search, search).stream().map(this::normalizeKeys).toList();
    }

    public List<Map<String, Object>> productSuppliers(long skuId, String keyword) {
        String search = keyword == null ? "" : keyword.trim();
        return jdbc.queryForList("""
                SELECT cfg.id AS `relationId`,sp.id AS `supplierId`,sp.supplier_code AS `supplierCode`,
                       sp.supplier_name AS `supplierName`,sp.contact_name AS `contactName`,sp.phone
                FROM sku_supplier_config cfg
                JOIN supplier sp ON sp.id=cfg.supplier_id
                JOIN sku s ON s.id=cfg.sku_id
                WHERE cfg.sku_id=? AND cfg.enabled=TRUE AND sp.enabled=TRUE AND s.enabled=TRUE
                  AND (LOCATE(?,COALESCE(sp.supplier_name,''))>0
                       OR LOCATE(?,COALESCE(sp.supplier_code,''))>0
                       OR LOCATE(?,COALESCE(sp.contact_name,''))>0)
                ORDER BY sp.supplier_name,sp.id LIMIT 30
                """, skuId, search, search, search).stream().map(this::normalizeKeys).map(supplier -> {
                    Map<String, Object> result = new LinkedHashMap<>(supplier);
                    long relationId = ((Number) result.remove("relationId")).longValue();
                    List<Map<String, Object>> infos = jdbc.queryForList("""
                            SELECT id,purchase_price AS `purchasePrice`,moq,
                                   lead_time_days AS `leadTimeDays`,updated_at AS `updatedAt`
                            FROM sku_supplier_purchase_info
                            WHERE supplier_product_config_id=? AND enabled=TRUE
                              AND purchase_price IS NOT NULL
                              AND moq IS NOT NULL
                              AND lead_time_days IS NOT NULL
                            ORDER BY updated_at DESC,id DESC
                            """, relationId).stream().map(this::normalizeKeys).toList();
                    result.put("purchaseInfos", infos);
                    result.put("latestPurchaseInfo", infos.isEmpty() ? null : infos.get(0));
                    if (!infos.isEmpty()) {
                        result.put("purchasePrice", infos.get(0).get("purchasePrice"));
                        result.put("moq", infos.get(0).get("moq"));
                        result.put("leadTimeDays", infos.get(0).get("leadTimeDays"));
                    }
                    return result;
                }).filter(result -> !((List<?>) result.get("purchaseInfos")).isEmpty()).toList();
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
        List<Map<String, Object>> products = jdbc.queryForList("""
                SELECT ssc.id AS `relationId`,s.id AS `skuId`,s.customer_part_number AS `customerPartNumber`,
                       s.product_code AS `productCode`,s.product_name AS `productName`,s.model,s.configuration,s.unit,
                       ssc.purchase_price AS `purchasePrice`,ssc.moq,
                       ssc.lead_time_days AS `leadTimeDays`
                FROM sku_supplier_config ssc
                JOIN sku s ON s.id=ssc.sku_id
                WHERE ssc.supplier_id=? AND ssc.enabled=TRUE
                ORDER BY s.customer_part_number,s.id
                """, supplierId).stream().map(this::normalizeKeys).map(product -> {
                    Map<String, Object> result = new LinkedHashMap<>(product);
                    long relationId = ((Number) result.remove("relationId")).longValue();
                    List<Map<String, Object>> infos = jdbc.queryForList("""
                            SELECT id,purchase_price AS `purchasePrice`,moq,
                                   lead_time_days AS `leadTimeDays`,updated_at AS `updatedAt`,version
                            FROM sku_supplier_purchase_info
                            WHERE supplier_product_config_id=? AND enabled=TRUE
                            ORDER BY updated_at DESC,id DESC
                            """, relationId).stream().map(this::normalizeKeys).toList();
                    result.put("purchaseInfos", infos);
                    if (!infos.isEmpty()) {
                        result.put("purchasePrice", infos.get(0).get("purchasePrice"));
                        result.put("moq", infos.get(0).get("moq"));
                        result.put("leadTimeDays", infos.get(0).get("leadTimeDays"));
                    }
                    return result;
                }).toList();
        supplier.put("products", products);
        return supplier;
    }

    private String inventoryMovementSummary(List<InventoryTransactionRow> transactions) {
        List<InventoryTransactionRow> movements = transactions.stream()
                .filter(transaction -> INVENTORY_MOVEMENT_TRANSACTION_TYPES.contains(transaction.transactionType()))
                .toList();
        if (movements.isEmpty()) return "—";
        StringBuilder summary = new StringBuilder();
        int visible = Math.min(3, movements.size());
        for (int index = 0; index < visible; index++) {
            InventoryTransactionRow movement = movements.get(index);
            if (index > 0) summary.append('；');
            String date = movement.operatedAt().toLocalDate().toString();
            summary.append(date.length() >= 10 ? date.substring(5).replace('-', '/') : date)
                    .append(' ').append(INVENTORY_MOVEMENT_INBOUND_TYPES.contains(movement.transactionType()) ? "入库" : "出库")
                    .append(' ').append(Math.abs(movement.actualDelta()));
        }
        if (movements.size() > visible) summary.append("；…");
        return summary.toString();
    }

    private Map<Long, List<Map<String, Object>>> inventoryLockedAllocations(List<Map<String, Object>> items) {
        if (items.isEmpty()) return Map.of();
        List<Long> inventoryIds = items.stream().map(item -> ((Number) item.get("id")).longValue()).toList();
        String placeholders = String.join(",", inventoryIds.stream().map(ignored -> "?").toList());
        List<Map<String, Object>> rows = jdbc.query(("SELECT inventory_balance_id,lock_source,quantity FROM inventory_locked_allocation "
                + "WHERE inventory_balance_id IN (%s) ORDER BY inventory_balance_id,id").formatted(placeholders),
                (resultSet, rowNumber) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("inventoryBalanceId", resultSet.getLong("inventory_balance_id"));
                    row.put("lockSource", resultSet.getString("lock_source"));
                    row.put("quantity", resultSet.getInt("quantity"));
                    return row;
                }, inventoryIds.toArray());
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long inventoryId = ((Number) row.get("inventoryBalanceId")).longValue();
            Map<String, Object> allocation = new LinkedHashMap<>();
            allocation.put("lockSource", row.get("lockSource"));
            allocation.put("quantity", row.get("quantity"));
            grouped.computeIfAbsent(inventoryId, ignored -> new ArrayList<>()).add(allocation);
        }
        return grouped;
    }
    private Map<Long, List<InventoryTransactionRow>> inventoryTransactions(List<Map<String, Object>> items) {
        if (items.isEmpty()) return Map.of();
        List<Long> inventoryIds = items.stream()
                .map(item -> ((Number) item.get("id")).longValue())
                .toList();
        String placeholders = String.join(",", inventoryIds.stream().map(ignored -> "?").toList());
        List<InventoryTransactionRow> transactions = jdbc.query("""
                        SELECT b.id AS inventory_id,tx.id,tx.operated_at,tx.transaction_type,tx.actual_delta
                        FROM inventory_balance b
                        JOIN inventory_transaction tx
                          ON tx.warehouse_id=b.warehouse_id AND tx.sku_id=b.sku_id
                        WHERE b.id IN (%s)
                        ORDER BY b.id,tx.operated_at,tx.id
                        """.formatted(placeholders),
                (resultSet, rowNumber) -> new InventoryTransactionRow(
                        resultSet.getLong("inventory_id"), resultSet.getLong("id"),
                        resultSet.getTimestamp("operated_at").toLocalDateTime(),
                        resultSet.getString("transaction_type"), resultSet.getInt("actual_delta")),
                inventoryIds.toArray());
        Map<Long, List<InventoryTransactionRow>> grouped = new LinkedHashMap<>();
        for (InventoryTransactionRow transaction : transactions) {
            grouped.computeIfAbsent(transaction.inventoryId(), ignored -> new ArrayList<>()).add(transaction);
        }
        return grouped;
    }

    private java.util.Optional<InventoryAgeCalculator.InventoryAge> inventoryAge(
            List<InventoryTransactionRow> transactions, int actualQuantity) {
        List<InventoryAgeCalculator.Movement> movements = transactions.stream()
                .filter(transaction -> transaction.actualDelta() != 0)
                .map(transaction -> new InventoryAgeCalculator.Movement(
                        transaction.id(), transaction.operatedAt(),
                        fifoActualDelta(transaction.transactionType(), transaction.actualDelta())))
                .filter(movement -> movement.actualDelta() != 0)
                .toList();
        return inventoryAgeCalculator.calculate(movements, actualQuantity, LocalDate.now());
    }

    private int fifoActualDelta(String transactionType, int actualDelta) {
        if (FIFO_INBOUND_TRANSACTION_TYPES.contains(transactionType)) return Math.abs(actualDelta);
        if (FIFO_OUTBOUND_TRANSACTION_TYPES.contains(transactionType)) return -Math.abs(actualDelta);
        if (FIFO_SIGNED_TRANSACTION_TYPES.contains(transactionType)) return actualDelta;
        if (FIFO_IGNORED_TRANSACTION_TYPES.contains(transactionType)) return 0;
        return 0;
    }

    private void registerModules() {
        modules.put("customer", new ModuleSpec(
                "SELECT c.id, c.customer_code AS `customerCode`, c.customer_name AS `customerName`, c.address, "
                        + "c.business_contact_name AS `businessContactName`, c.business_contact_phone AS `businessContactPhone`, "
                        + "c.order_contact_name AS `orderContactName`, c.order_contact_phone AS `orderContactPhone`, "
                        + "c.finance_contact_name AS `financeContactName`, c.finance_contact_phone AS `financeContactPhone`, "
                        + "c.invoice_title AS `invoiceTitle`, c.taxpayer_id AS `taxpayerId`, c.invoice_address AS `invoiceAddress`, "
                        + "c.invoice_phone AS `invoicePhone`, c.bank_name AS `bankName`, c.bank_account AS `bankAccount`, "
                        + "CASE WHEN NOT EXISTS(SELECT 1 FROM customer_contract cc0 WHERE cc0.customer_id=c.id AND cc0.enabled=TRUE) THEN '未设置合同' "
                        + "WHEN EXISTS(SELECT 1 FROM customer_contract cc1 WHERE cc1.customer_id=c.id AND cc1.enabled=TRUE AND CURRENT_DATE BETWEEN cc1.start_date AND cc1.end_date) THEN '有效' ELSE '已到期' END AS `contractStatus`, "
                        + "(SELECT MAX(cc2.end_date) FROM customer_contract cc2 WHERE cc2.customer_id=c.id AND cc2.enabled=TRUE) AS `contractEndDate`, "
                        + "c.enabled, c.updated_at AS `updatedAt`, c.version",
                "FROM customer c", "LOCATE(?, COALESCE(c.customer_code,''))>0 OR LOCATE(?, COALESCE(c.customer_name,''))>0 OR LOCATE(?, COALESCE(c.order_contact_name,''))>0 OR LOCATE(?, COALESCE(c.order_contact_phone,''))>0", 4,
                sorts("id", "c.id", "customerCode", "c.customer_code", "customerName", "c.customer_name", "orderContactName", "c.order_contact_name", "orderContactPhone", "c.order_contact_phone", "contractStatus", "CASE WHEN NOT EXISTS(SELECT 1 FROM customer_contract cc0 WHERE cc0.customer_id=c.id AND cc0.enabled=TRUE) THEN 10 WHEN EXISTS(SELECT 1 FROM customer_contract cc1 WHERE cc1.customer_id=c.id AND cc1.enabled=TRUE AND CURRENT_DATE BETWEEN cc1.start_date AND cc1.end_date) THEN 20 ELSE 30 END", "contractEndDate", "(SELECT MAX(cc2.end_date) FROM customer_contract cc2 WHERE cc2.customer_id=c.id AND cc2.enabled=TRUE)", "updatedAt", "c.updated_at"),
                "c.updated_at", "c.id DESC"));
        modules.put("user", new ModuleSpec(
                "SELECT u.id, u.username, u.display_name AS `displayName`, u.phone, u.enabled, u.role, u.updated_at AS `updatedAt`, u.version",
                "FROM sys_user u", "LOCATE(?, COALESCE(u.username,''))>0 OR LOCATE(?, COALESCE(u.display_name,''))>0 OR LOCATE(?, COALESCE(u.phone,''))>0", 3,
                sorts("id", "u.id", "username", "u.username", "displayName", "u.display_name", "role", "CASE u.role WHEN 'ADMIN' THEN 10 WHEN 'FINANCE' THEN 20 WHEN 'USER' THEN 30 ELSE 999 END", "updatedAt", "u.updated_at"),
                "u.updated_at", "u.id DESC"));
        modules.put("product", new ModuleSpec(
                "SELECT s.id, s.product_code AS `productCode`, s.code_suffix AS `codeSuffix`, s.ean_code AS `eanCode`, s.customer_part_number AS `customerPartNumber`, s.model, s.product_name AS `productName`, s.color, "
                        + "s.lock_body AS `lockBody`, s.product_version AS `productVersion`, s.configuration, s.product_configuration AS `productConfiguration`, s.unit, "
                        + "s.brand_rule_id AS `brandRuleId`,s.series_rule_id AS `seriesRuleId`,s.body_color_rule_id AS `bodyColorRuleId`,s.lock_type_rule_id AS `lockTypeRuleId`,"
                        + "s.connectivity_rule_id AS `connectivityRuleId`,s.sales_channel_rule_id AS `salesChannelRuleId`,s.operating_entity_rule_id AS `operatingEntityRuleId`,s.language_rule_id AS `languageRuleId`,"
                        + "s.product_type AS `productType`,s.material_type AS `materialType`,s.door_model_rule_id AS `doorModelRuleId`,s.security_grade_rule_id AS `securityGradeRuleId`,s.base_material_rule_id AS `baseMaterialRuleId`,s.thickness_rule_id AS `thicknessRuleId`,s.finish_color_rule_id AS `finishColorRuleId`,"
                        + "br.display_name AS brand,COALESCE(sr.display_name,dmr.display_name) AS series,bcr.display_name AS `bodyColor`,ltr.display_name AS `lockType`,cr.display_name AS connectivity,scr.display_name AS `salesChannel`,oer.display_name AS `operatingEntity`,lr.display_name AS language,"
                        + "s.current_cost AS `currentCost`, s.factory_price AS `factoryPrice`, s.sales_minimum_order_quantity AS `salesMinimumOrderQuantity`, (s.factory_price-s.current_cost) AS `priceDifference`, s.product_remark AS remark, s.enabled, s.updated_at AS `updatedAt`, s.version, "
                        + "COALESCE(ib.actual_quantity,0) AS `actualQuantity`, COALESCE(ib.locked_quantity,0) AS `lockedQuantity`, COALESCE(ib.in_transit_quantity,0) AS `inTransitQuantity`, COALESCE(ib.source_supplier_name,'') AS `sourceSupplierName`, COALESCE(ib.inventory_remark,'') AS `inventoryRemark`, "
                        + "(SELECT COUNT(*) FROM product_image pi WHERE pi.product_id=s.id) AS `imageCount`, "
                        + "(SELECT pi.id FROM product_image pi WHERE pi.product_id=s.id AND pi.is_primary=TRUE LIMIT 1) AS `primaryImageId`, "
                        + "NULL AS `supplierId`, NULL AS `supplierName`, NULL AS `purchasePrice`, NULL AS moq, NULL AS `leadTimeDays`",
                "FROM sku s "
                        + "LEFT JOIN product_code_rule br ON br.id=s.brand_rule_id LEFT JOIN product_code_rule sr ON sr.id=s.series_rule_id "
                        + "LEFT JOIN product_code_rule bcr ON bcr.id=s.body_color_rule_id LEFT JOIN product_code_rule ltr ON ltr.id=s.lock_type_rule_id "
                        + "LEFT JOIN product_code_rule cr ON cr.id=s.connectivity_rule_id LEFT JOIN product_code_rule scr ON scr.id=s.sales_channel_rule_id "
                        + "LEFT JOIN product_code_rule oer ON oer.id=s.operating_entity_rule_id LEFT JOIN product_code_rule lr ON lr.id=s.language_rule_id LEFT JOIN product_code_rule dmr ON dmr.id=s.door_model_rule_id "
                        + "LEFT JOIN inventory_balance ib ON ib.sku_id=s.id AND ib.warehouse_id=(SELECT id FROM warehouse WHERE is_default=TRUE ORDER BY id LIMIT 1)",
                "LOCATE(?, COALESCE(s.customer_part_number,''))>0 OR LOCATE(?, COALESCE(s.model,''))>0 OR LOCATE(?, COALESCE(s.product_code,''))>0", 3,
                sorts("id", "s.id", "productCode", "s.product_code", "customerPartNumber", "s.customer_part_number", "brand", "br.display_name", "model", "s.model", "productName", "s.product_name", "productType", "CASE s.product_type WHEN 'SMART_LOCK' THEN 10 WHEN 'ENTRY_DOOR' THEN 20 ELSE 999 END", "materialType", "CASE s.material_type WHEN 'FINISHED_PRODUCT' THEN 10 WHEN 'PART' THEN 20 ELSE 999 END", "salesMinimumOrderQuantity", "s.sales_minimum_order_quantity", "updatedAt", "s.updated_at"),
                "s.updated_at", "s.id DESC"));        modules.put("supplier", new ModuleSpec(
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
                sorts("id", "sp.id", "manufacturerCategory", "sp.manufacturer_category", "manufacturerType", "sp.manufacturer_type", "supplierLocation", "sp.supplier_location", "productAttribute", "sp.product_attribute", "shortName", "sp.short_name", "supplierName", "sp.supplier_name", "contactName", "sp.contact_name", "contactTitle", "sp.contact_title", "phone", "sp.phone", "currency", "sp.currency", "taxRegistrationNo", "sp.tax_registration_no", "bankAddress", "sp.bank_address", "bankAccount", "sp.bank_account", "productCount", "(SELECT COUNT(*) FROM sku_supplier_config cfg WHERE cfg.supplier_id=sp.id AND cfg.enabled=TRUE)", "updatedAt", "sp.updated_at"),
                "sp.updated_at", "sp.id DESC"));
        modules.put("order", new ModuleSpec(
                "SELECT o.id, o.order_no AS `orderNo`, o.external_order_no AS `externalOrderNo`, c.customer_name AS `customerName`, "
                        + "o.total_amount AS `totalAmount`, o.order_date AS `orderDate`, o.order_type AS `orderType`, o.salesperson, o.status, o.receipt_confirmed_at AS `receiptConfirmedAt`, "
                        + "o.shipped_at AS `shippedAt`, o.carrier, o.tracking_no AS `trackingNo`, o.created_at AS `createdAt`, o.updated_at AS `updatedAt`, o.version",
                "FROM sales_order o JOIN customer c ON c.id=o.customer_id",
                "LOCATE(?, COALESCE(o.order_no,''))>0 OR LOCATE(?, COALESCE(o.external_order_no,''))>0 OR LOCATE(?, COALESCE(c.customer_name,''))>0 OR LOCATE(?, COALESCE(o.status,''))>0", 4,
                sorts("id", "o.id", "orderNo", "o.order_no", "customerName", "c.customer_name", "orderType", "CASE WHEN o.order_type IN ('工程订单','零售订单','前置订单') THEN 0 ELSE 1 END ASC, CASE o.order_type WHEN '工程订单' THEN 10 WHEN '零售订单' THEN 20 WHEN '前置订单' THEN 30 ELSE 999 END", "totalAmount", "o.total_amount", "status", "CASE o.status WHEN 'DRAFT' THEN 10 WHEN 'PENDING_CUSTOMER_PAYMENT' THEN 20 WHEN 'WAITING_STOCK' THEN 30 WHEN 'READY_TO_SHIP' THEN 40 WHEN 'SHIPPED' THEN 50 ELSE 999 END", "orderDate", "o.order_date", "salesperson", "o.salesperson", "createdAt", "o.created_at", "updatedAt", "o.updated_at"),
                "o.updated_at", "o.id DESC"));
        modules.put("afterSales", new ModuleSpec(
                "SELECT a.id,a.after_sales_no AS `afterSalesNo`,o.order_no AS `orderNo`,c.customer_name AS `customerName`,o.order_type AS `orderType`,"
                        + "a.after_sales_type AS `afterSalesType`,"
                        + "(SELECT COALESCE(SUM(r.requested_quantity),0) FROM after_sales_return_line r WHERE r.after_sales_order_id=a.id) AS `returnQuantity`,"
                        + "(SELECT COALESCE(SUM(x.planned_quantity),0) FROM after_sales_replacement_line x WHERE x.after_sales_order_id=a.id) AS `replacementQuantity`,"
                        + "a.status,a.application_date AS `applicationDate`,a.updated_at AS `updatedAt`,a.version",
                "FROM after_sales_order a JOIN sales_order o ON o.id=a.sales_order_id JOIN customer c ON c.id=a.customer_id",
                "LOCATE(?,COALESCE(a.after_sales_no,''))>0 OR LOCATE(?,COALESCE(o.order_no,''))>0 OR LOCATE(?,COALESCE(c.customer_name,''))>0 "
                        + "OR EXISTS(SELECT 1 FROM after_sales_return_line r WHERE r.after_sales_order_id=a.id AND (LOCATE(?,COALESCE(r.customer_part_number,''))>0 OR LOCATE(?,COALESCE(r.product_name,''))>0)) "
                        + "OR EXISTS(SELECT 1 FROM after_sales_replacement_line x WHERE x.after_sales_order_id=a.id AND (LOCATE(?,COALESCE(x.customer_part_number,''))>0 OR LOCATE(?,COALESCE(x.product_name,''))>0))", 7,
                sorts("id","a.id","afterSalesNo","a.after_sales_no","orderNo","o.order_no","customerName","c.customer_name","orderType","CASE WHEN o.order_type IN ('工程订单','零售订单','前置订单') THEN 0 ELSE 1 END ASC, CASE o.order_type WHEN '工程订单' THEN 10 WHEN '零售订单' THEN 20 WHEN '前置订单' THEN 30 ELSE 999 END","afterSalesType","a.after_sales_type","returnQuantity","(SELECT COALESCE(SUM(r.requested_quantity),0) FROM after_sales_return_line r WHERE r.after_sales_order_id=a.id)","replacementQuantity","(SELECT COALESCE(SUM(x.planned_quantity),0) FROM after_sales_replacement_line x WHERE x.after_sales_order_id=a.id)","status","CASE a.status WHEN 'WAITING_RETURN' THEN 10 WHEN 'RETURN_RECEIVED' THEN 20 WHEN 'WAITING_REPLACEMENT' THEN 30 WHEN 'COMPLETED' THEN 40 WHEN 'CANCELLED' THEN 50 ELSE 999 END","applicationDate","a.application_date","updatedAt","a.updated_at"),
                "a.updated_at", "a.id DESC"));        modules.put("inventory", new ModuleSpec(
                "SELECT b.id, b.sku_id AS `skuId`, s.product_code AS `productCode`, s.customer_part_number AS `customerPartNumber`, s.model, s.product_type AS `productType`, s.product_configuration AS `productConfiguration`, s.configuration, s.unit, "
                        + "b.actual_quantity AS `actualQuantity`, b.locked_quantity AS `lockedQuantity`, "

                        + "(b.actual_quantity-b.locked_quantity) AS `availableQuantity`, b.in_transit_quantity AS `inTransitQuantity`, "
                        + "(SELECT COUNT(*) FROM inventory_transaction tx WHERE tx.warehouse_id=b.warehouse_id AND tx.sku_id=b.sku_id AND tx.business_type='EXCEL_IMPORT_HISTORY') AS `movementCount`, "
                        + "b.source_supplier_name AS `sourceSupplierName`, b.inventory_remark AS `inventoryRemark`, b.updated_at AS `updatedAt`, b.version",
                "FROM inventory_balance b JOIN sku s ON s.id=b.sku_id",
                "LOCATE(?, COALESCE(s.customer_part_number,''))>0 OR LOCATE(?, COALESCE(s.model,''))>0 OR LOCATE(?, COALESCE(s.product_code,''))>0", 3,
                sorts("id", "b.id", "productCode", "s.product_code", "customerPartNumber", "s.customer_part_number", "model", "s.model", "productType", "s.product_type", "unit", "s.unit", "actualQuantity", "b.actual_quantity", "availableQuantity", "(b.actual_quantity-b.locked_quantity)", "oldestStockDate", "(SELECT MIN(tx.operated_at) FROM inventory_transaction tx WHERE tx.warehouse_id=b.warehouse_id AND tx.sku_id=b.sku_id AND tx.actual_delta>0)", "inventoryAgeDays", "DATEDIFF(CURRENT_DATE,(SELECT MIN(tx.operated_at) FROM inventory_transaction tx WHERE tx.warehouse_id=b.warehouse_id AND tx.sku_id=b.sku_id AND tx.actual_delta>0))", "lockedQuantity", "b.locked_quantity", "inTransitQuantity", "b.in_transit_quantity", "pendingDeliveryQuantity", "(SELECT COALESCE(SUM(GREATEST(i.quantity-i.shipped_quantity,0)),0) FROM sales_order_item i JOIN sales_order o2 ON o2.id=i.sales_order_id WHERE o2.status<>'CANCELLED' AND i.sku_id=b.sku_id)", "supplyDemandSurplus", "b.actual_quantity+b.in_transit_quantity-(SELECT COALESCE(SUM(GREATEST(i.quantity-i.shipped_quantity,0)),0) FROM sales_order_item i JOIN sales_order o2 ON o2.id=i.sales_order_id WHERE o2.status<>'CANCELLED' AND i.sku_id=b.sku_id)", "sourceSupplierName", "b.source_supplier_name", "updatedAt", "b.updated_at"),
                "b.updated_at", "b.id DESC"));
        String purchaseView = "FROM ("
                + "SELECT po.id, 'PURCHASE' AS record_type, po.purchase_no, po.supplier_id, po.manual_entry, sp.supplier_name, "
                + "GROUP_CONCAT(poi.sku_id ORDER BY poi.line_no SEPARATOR ', ') AS product_ids, "
                + "GROUP_CONCAT(COALESCE(NULLIF(s.product_name,''), '未命名产品') ORDER BY poi.line_no SEPARATOR '；') AS product_summary, "
                + "po.status, po.total_amount, "
                + "COALESCE((SELECT SUM(pay.amount) FROM supplier_payment pay WHERE pay.purchase_order_id=po.id),0) AS paid_amount, "
                + "COALESCE(SUM(poi.quantity),0) AS ordered_quantity, COALESCE(SUM(poi.received_quantity),0) AS received_quantity, "
                + "po.expected_arrival_date, po.delivery_address, po.created_at, po.updated_at, po.version "
                + "FROM purchase_order po JOIN supplier sp ON sp.id=po.supplier_id "
                + "LEFT JOIN purchase_order_item poi ON poi.purchase_order_id=po.id LEFT JOIN sku s ON s.id=poi.sku_id "
                + "GROUP BY po.id,po.purchase_no,po.supplier_id,po.manual_entry,sp.supplier_name,po.status,po.total_amount,po.expected_arrival_date,po.delivery_address,po.created_at,po.updated_at,po.version "
                + "UNION ALL "
                + "SELECT ps.id, 'SUGGESTION', ps.suggestion_no, psi.supplier_id, NULL, sp.supplier_name, "
                + "GROUP_CONCAT(psi.sku_id ORDER BY psi.id SEPARATOR ', '), "
                + "GROUP_CONCAT(COALESCE(NULLIF(s.product_name,''), '未命名产品') ORDER BY psi.id SEPARATOR '；'), "
                + "ps.status, SUM(psi.suggested_quantity*psi.purchase_price), 0, SUM(psi.suggested_quantity), 0, MAX(psi.expected_arrival_date), NULL, ps.created_at, ps.updated_at, ps.version "
                + "FROM procurement_suggestion ps JOIN procurement_suggestion_item psi ON psi.suggestion_id=ps.id "
                + "JOIN supplier sp ON sp.id=psi.supplier_id JOIN sku s ON s.id=psi.sku_id "
                + "WHERE ps.status='DRAFT' "
                + "GROUP BY ps.id,ps.suggestion_no,psi.supplier_id,sp.supplier_name,ps.status,ps.created_at,ps.updated_at,ps.version) p";
        modules.put("purchase", new ModuleSpec(
                  "SELECT p.id, p.record_type AS `recordType`, p.purchase_no AS `purchaseNo`, p.manual_entry AS `manualEntry`, "
                        + "p.supplier_name AS `supplierName`, p.product_summary AS `productSummary`, p.status, p.total_amount AS `totalAmount`, "
                        + "p.paid_amount AS `paidAmount`, GREATEST(p.total_amount-p.paid_amount,0) AS `outstandingAmount`, "
                        + "CASE WHEN p.paid_amount<=0 THEN 'UNPAID' WHEN p.paid_amount>=p.total_amount THEN 'PAID' ELSE 'PARTIALLY_PAID' END AS `paymentStatus`, "
                        + "p.ordered_quantity AS `orderedQuantity`, p.received_quantity AS `receivedQuantity`, GREATEST(p.ordered_quantity-p.received_quantity,0) AS `remainingQuantity`, "
                        + "CASE WHEN p.received_quantity<=0 THEN 'UNRECEIVED' WHEN p.received_quantity>=p.ordered_quantity THEN 'RECEIVED' ELSE 'PARTIALLY_RECEIVED' END AS `receiptStatus`, "
                        + "p.expected_arrival_date AS `expectedArrivalDate`, p.delivery_address AS `deliveryAddress`, p.created_at AS `createdAt`, p.updated_at AS `updatedAt`, p.version",
                purchaseView,
                "LOCATE(?, COALESCE(p.purchase_no,''))>0 OR LOCATE(?, COALESCE(p.supplier_name,''))>0 OR LOCATE(?, COALESCE(p.product_summary,''))>0 OR LOCATE(?, COALESCE(p.status,''))>0", 4,
                sorts("id", "p.id", "purchaseNo", "p.purchase_no", "supplierName", "p.supplier_name", "totalAmount", "p.total_amount", "paymentStatus", "CASE WHEN p.paid_amount<=0 THEN 10 WHEN p.paid_amount<p.total_amount THEN 20 ELSE 30 END", "receiptStatus", "CASE WHEN p.received_quantity<=0 THEN 10 WHEN p.received_quantity<p.ordered_quantity THEN 20 ELSE 30 END", "status", "CASE p.status WHEN 'DRAFT' THEN 10 WHEN 'PENDING_SUPPLIER_PAYMENT' THEN 20 WHEN 'EXECUTING' THEN 30 WHEN 'RECEIVED' THEN 40 WHEN 'COMPLETED' THEN 50 ELSE 999 END", "expectedArrivalDate", "p.expected_arrival_date", "createdAt", "p.created_at", "updatedAt", "p.updated_at"),
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
                sorts("id", "f.id", "businessNo", "f.business_no", "businessType", "f.business_type", "counterparty", "f.counterparty", "amount", "f.amount", "settledAmount", "f.settled_amount", "outstandingAmount", "(f.amount-f.settled_amount)", "status", "CASE WHEN f.settled_amount<f.amount THEN 10 ELSE 30 END", "updatedAt", "f.updated_at"),
                "f.updated_at", "f.cash_direction, f.id DESC"));
    }

    private Map<String, String> sorts(String... values) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(values[i], values[i + 1]);
        }
        return Map.copyOf(result);
    }

    private record InventoryTransactionRow(long inventoryId, long id, java.time.LocalDateTime operatedAt,
                                           String transactionType, int actualDelta) {
    }

    private record ModuleSpec(String selectClause, String fromClause, String keywordPredicate,
                              int keywordParameterCount, Map<String, String> sortColumns,
                              String defaultSort, String tieBreaker) {
    }
}

