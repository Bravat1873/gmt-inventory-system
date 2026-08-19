package com.internalops.importing;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import com.internalops.productcode.ProductCodeSelection;
import com.internalops.productcode.ProductUniqueId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class ProductReplaceImportService {
    private static final List<String> DELETE_ORDER = List.of(
            "after_sales_replacement_line", "after_sales_event", "after_sales_return_line", "after_sales_order",
            "sales_shipment_item", "sales_shipment", "customer_receipt", "sales_invoice", "exception_case",
            "shortage_coverage", "goods_receipt_item", "goods_receipt", "supplier_payment",
            "purchase_order_item", "purchase_order", "procurement_suggestion_item", "procurement_suggestion",
            "sales_order_item", "sales_order",
            "inventory_locked_allocation", "inventory_transaction", "inventory_balance",
            "sku_cost_history", "customer_contract_price", "product_image",
            "sku_supplier_purchase_info", "sku_supplier_config", "sku");

    private final JdbcTemplate jdbc;
    private final ImportBatchRepository repository;
    private final ProductImportCodeResolver codeResolver;
    private final boolean replaceEnabled;

    public ProductReplaceImportService(JdbcTemplate jdbc, ImportBatchRepository repository,
                                       ProductImportCodeResolver codeResolver,
                                       @Value("${app.import.product-replace-enabled:false}") boolean replaceEnabled) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.codeResolver = codeResolver;
        this.replaceEnabled = replaceEnabled;
    }

    @Transactional
    public ImportBatchView replace(ImportBatchView batch, Map<Long, ProductConflictAction> requestedActions) {
        requireAllowed(batch);
        Map<Long, ProductConflictAction> actions = requestedActions == null ? Map.of() : Map.copyOf(requestedActions);
        List<SelectedProduct> selected = validateAndSelect(batch, actions);
        if (selected.isEmpty()) throw new IllegalArgumentException("没有可导入的产品");

        DELETE_ORDER.forEach(table -> jdbc.update("DELETE FROM " + table));
        for (SelectedProduct product : selected) {
            long skuId = insertProduct(product.row(), product.resolved());
            insertInventory(skuId, product.row().data());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", selected.size());
        result.put("updated", 0);
        result.put("committed", selected.size());
        result.put("skipped", batch.validRows() - selected.size());
        result.put("errors", 0);
        result.put("ignored", batch.ignoredRows());
        result.put("mode", "PRODUCT_REPLACE_ALL");
        repository.markCommitted(batch.batchId(), selected.size(), result);
        return repository.findBatch(batch.batchId());
    }

    private void requireAllowed(ImportBatchView batch) {
        if (!replaceEnabled) throw new IllegalStateException("当前环境未启用产品全量替换");
        if (CurrentUser.required().role() != UserRole.ADMIN) {
            throw new IllegalArgumentException("仅管理员可执行产品全量替换");
        }
        if (batch.importType() != ImportType.PRODUCT) throw new IllegalArgumentException("仅产品批次可执行产品全量替换");
        if (!"PREVIEW".equals(batch.status())) throw new IllegalArgumentException("产品导入批次状态不允许提交");
        if (batch.errorRows() > 0 || batch.rows().stream().anyMatch(row -> row.status() == ImportRowStatus.ERROR)) {
            throw new IllegalArgumentException("产品导入存在错误行，请先修正");
        }
    }

    private List<SelectedProduct> validateAndSelect(ImportBatchView batch, Map<Long, ProductConflictAction> actions) {
        Set<Long> batchRowIds = new HashSet<>();
        batch.rows().forEach(row -> batchRowIds.add(row.id()));
        for (Long rowId : actions.keySet()) {
            if (!batchRowIds.contains(rowId)) throw new IllegalArgumentException("冲突决策行不属于当前批次");
        }

        Map<String, List<SelectedProduct>> byBusinessUniqueId = new LinkedHashMap<>();
        for (ImportRowView row : batch.rows()) {
            if (row.status() != ImportRowStatus.VALID) continue;
            ProductImportCodeResolver.ResolvedProductCode resolved = codeResolver.resolve(row.data());
            String businessUniqueId = ProductUniqueId.from(resolved.productCode(), text(row.data(), "customerPartNumber"));
            if (!resolved.productCode().equals(text(row.data(), "productCode"))
                    || !resolved.ruleFingerprint().equals(text(row.data(), "_ruleFingerprint"))) {
                throw new IllegalArgumentException("产品编码规则已变化，请重新预览后再提交");
            }
            if (!text(row.data(), "_businessUniqueId").isBlank() && !businessUniqueId.equals(text(row.data(), "_businessUniqueId"))) {
                throw new IllegalArgumentException("产品唯一ID已变化，请重新预览后再提交");
            }
            byBusinessUniqueId.computeIfAbsent(businessUniqueId, ignored -> new ArrayList<>())
                    .add(new SelectedProduct(row, resolved));
        }

        List<SelectedProduct> selected = new ArrayList<>();
        Set<Long> usedActions = new LinkedHashSet<>();
        for (List<SelectedProduct> group : byBusinessUniqueId.values()) {
            if (group.size() == 1) {
                SelectedProduct product = group.get(0);
                ProductConflictAction action = actions.get(product.row().id());
                if (action == ProductConflictAction.SKIP) usedActions.add(product.row().id());
                else {
                    if (action == ProductConflictAction.KEEP) usedActions.add(product.row().id());
                    selected.add(product);
                }
                continue;
            }
            int kept = 0;
            for (SelectedProduct product : group) {
                ProductConflictAction action = actions.get(product.row().id());
                if (action == null) throw new IllegalArgumentException("存在未处理的产品编号和客户料号组合冲突");
                usedActions.add(product.row().id());
                if (action == ProductConflictAction.KEEP) {
                    kept++;
                    selected.add(product);
                }
            }
            if (kept > 1) throw new IllegalArgumentException("同一产品编号和客户料号组合冲突组只能保留一行");
        }
        if (!usedActions.containsAll(actions.keySet())) throw new IllegalArgumentException("冲突决策与当前批次不一致");
        return selected;
    }

    private long insertProduct(ImportRowView row, ProductImportCodeResolver.ResolvedProductCode resolved) {
        Map<String, Object> data = row.data();
        ProductCodeSelection rules = resolved.selection();
        String model = nullableText(data, "model");
        String configuration = materialSpecification(rules, model);
        String productConfiguration = nullableText(data, "productConfiguration");
        String productName = firstNonBlank(model, productConfiguration, resolved.productCode());
        int salesMoq = positiveInt(data.get("salesMinimumOrderQuantity"), 1);
        String materialType = materialType(text(data, "materialType"));
        String productType = productType(text(data, "productCategory"));

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sku(product_code,business_unique_id,product_type,material_type,customer_part_number,code_suffix,model,product_name,
                        color,lock_body,configuration,product_configuration,unit,sales_minimum_order_quantity,enabled,
                        brand_rule_id,series_rule_id,body_color_rule_id,lock_type_rule_id,connectivity_rule_id,
                        sales_channel_rule_id,operating_entity_rule_id,language_rule_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, resolved.productCode());
            statement.setString(2, ProductUniqueId.from(resolved.productCode(), text(data, "customerPartNumber")));
            statement.setString(3, productType);
            statement.setString(4, materialType);
            statement.setString(5, text(data, "customerPartNumber"));
            statement.setObject(6, nullableText(data, "codeSuffix"));
            statement.setObject(7, model);
            statement.setString(8, productName);
            statement.setObject(9, nullableText(data, "bodyColor"));
            statement.setObject(10, nullableText(data, "lockType"));
            statement.setObject(11, configuration);
            statement.setObject(12, productConfiguration);
            statement.setString(13, "件");
            statement.setInt(14, salesMoq);
            statement.setLong(15, rules.brandRuleId());
            statement.setLong(16, rules.seriesRuleId());
            statement.setLong(17, rules.bodyColorRuleId());
            statement.setLong(18, rules.lockTypeRuleId());
            statement.setLong(19, rules.connectivityRuleId());
            statement.setLong(20, rules.salesChannelRuleId());
            statement.setLong(21, rules.operatingEntityRuleId());
            statement.setLong(22, rules.languageRuleId());
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("新增产品失败，未生成数据编号");
        insertSupplierQuote(keys.getKey().longValue(), data);
        return keys.getKey().longValue();
    }

    private void insertInventory(long skuId, Map<String, Object> data) {
        Long warehouseId = jdbc.queryForObject("SELECT id FROM warehouse WHERE is_default=TRUE ORDER BY id LIMIT 1", Long.class);
        if (warehouseId == null) throw new IllegalStateException("未配置默认仓库");
        int actual = inventoryQuantity(data.get("actualQuantity"));
        int locked = inventoryQuantity(data.get("lockedQuantity"));
        int transit = inventoryQuantity(data.get("inTransitQuantity"));
        if (actual < 0 || locked < 0 || transit < 0 || locked > actual + transit) {
            throw new IllegalArgumentException("库存数量无效或锁定库存大于实际与在途库存之和");
        }
        String sourceSupplierName = text(data, "sourceSupplierName");
        if (sourceSupplierName.isBlank()) sourceSupplierName = text(data, "supplierName");
        String inventoryRemark = text(data, "inventoryRemark");
        if (!sourceSupplierName.isBlank() && findByNormalizedName("supplier", "supplier_name", sourceSupplierName) == null) {
            jdbc.update("INSERT INTO supplier(supplier_code,supplier_name,enabled) VALUES(?,?,TRUE)",
                    nextCode("supplier", "supplier_code", "SUP"), sourceSupplierName);
        }
        List<Long> balances = jdbc.query(
                "SELECT id FROM inventory_balance WHERE warehouse_id=? AND sku_id=?",
                (rs, index) -> rs.getLong(1), warehouseId, skuId);
        if (balances.isEmpty()) {
            jdbc.update("""
                    INSERT INTO inventory_balance(
                        warehouse_id,sku_id,actual_quantity,locked_quantity,in_transit_quantity,source_supplier_name,inventory_remark)
                    VALUES(?,?,?,?,?,?,?)
                    """, warehouseId, skuId, actual, locked, transit, emptyToNull(sourceSupplierName), emptyToNull(inventoryRemark));
        } else {
            jdbc.update("""
                    UPDATE inventory_balance SET
                        actual_quantity=?,locked_quantity=?,in_transit_quantity=?,source_supplier_name=?,inventory_remark=?,version=version+1
                    WHERE warehouse_id=? AND sku_id=?
                    """, actual, locked, transit, emptyToNull(sourceSupplierName), emptyToNull(inventoryRemark), warehouseId, skuId);
        }
    }

    private void insertSupplierQuote(long skuId, Map<String, Object> data) {
        String supplierName = text(data, "supplierName");
        if (supplierName.isBlank()) return;
        List<Long> suppliers = jdbc.query("SELECT id FROM supplier WHERE supplier_name=? AND enabled=TRUE",
                (rs, index) -> rs.getLong(1), supplierName);
        if (suppliers.size() != 1) throw new IllegalArgumentException("供应商不存在或名称不唯一：" + supplierName);
        BigDecimal purchasePrice = nullableDecimal(data.get("supplierTaxPrice"));
        Integer purchaseMoq = purchasePrice == null ? null : 1;
        Integer leadTimeDays = purchasePrice == null ? null : 0;
        KeyHolder relationKeys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sku_supplier_config(
                        sku_id,supplier_id,purchase_price,moq,lead_time_days,enabled)
                    VALUES(?,?,?,?,?,TRUE)
                    """, new String[]{"id"});
            statement.setLong(1, skuId);
            statement.setLong(2, suppliers.get(0));
            statement.setObject(3, purchasePrice);
            statement.setObject(4, purchaseMoq);
            statement.setObject(5, leadTimeDays);
            return statement;
        }, relationKeys);
        long relationId = Objects.requireNonNull(relationKeys.getKey()).longValue();
        jdbc.update("""
                INSERT INTO sku_supplier_purchase_info(
                    supplier_product_config_id,purchase_price,moq,lead_time_days,enabled)
                VALUES(?,?,?,?,TRUE)
                """, relationId, purchasePrice, purchaseMoq, leadTimeDays);
    }

    private String materialSpecification(ProductCodeSelection rules, String model) {
        return String.join(" / ", List.of(
                ruleDisplayName(rules.brandRuleId(), "BRAND"),
                model == null ? "" : model.trim(),
                ruleDisplayName(rules.bodyColorRuleId(), "BODY_COLOR"),
                ruleDisplayName(rules.lockTypeRuleId(), "LOCK_TYPE"),
                ruleDisplayName(rules.languageRuleId(), "LANGUAGE")
        ).stream().filter(value -> !value.isBlank()).toList());
    }

    private String ruleDisplayName(long ruleId, String category) {
        List<String> names = jdbc.queryForList(
                "SELECT display_name FROM product_code_rule WHERE id=? AND category=?",
                String.class, ruleId, category);
        if (names.isEmpty()) throw new IllegalArgumentException("产品编号规则无效：" + category);
        return names.get(0).trim();
    }

    private String text(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private String nullableText(Map<String, Object> data, String key) {
        String value = text(data, key);
        return value.isBlank() ? null : value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        throw new IllegalArgumentException("产品名称不能为空");
    }

    private int positiveInt(Object value, int fallback) {
        if (value == null || value.toString().isBlank()) return fallback;
        int parsed = new BigDecimal(value.toString()).intValueExact();
        if (parsed <= 0) throw new IllegalArgumentException("销售最小起订量必须大于0");
        return parsed;
    }

    private int inventoryQuantity(Object value) {
        if (value == null || value.toString().isBlank()) return 0;
        int parsed = new BigDecimal(value.toString()).intValueExact();
        if (parsed < 0) throw new IllegalArgumentException("库存数量不能为负数");
        return parsed;
    }

    private BigDecimal nullableDecimal(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        BigDecimal price = new BigDecimal(value.toString());
        if (price.signum() < 0) throw new IllegalArgumentException("供应商含税价不能为负数");
        return price;
    }

    private String materialType(String value) {
        if (value.isBlank() || "成品".equals(value) || "FINISHED_PRODUCT".equalsIgnoreCase(value)) return "FINISHED_PRODUCT";
        if ("配件".equals(value) || "零件".equals(value)
                || "PART".equalsIgnoreCase(value) || "ACCESSORY".equalsIgnoreCase(value)) return "PART";
        return value;
    }

    private String productType(String value) {
        if (value.contains("锁") || value.isBlank()) return "SMART_LOCK";
        return "UNCLASSIFIED";
    }

    private Long findByNormalizedName(String table, String column, String name) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id," + column + " AS business_name FROM " + table);
        String normalized = normalizeName(name);
        for (Map<String, Object> row : rows) {
            if (normalizeName(String.valueOf(row.get("business_name"))).equals(normalized)) {
                return ((Number) row.get("id")).longValue();
            }
        }
        return null;
    }

    private String nextCode(String table, String column, String prefix) {
        int max = 0;
        for (String code : jdbc.query("SELECT " + column + " FROM " + table + " WHERE " + column + " LIKE ?",
                (rs, index) -> rs.getString(1), prefix + "%")) {
            try { max = Math.max(max, Integer.parseInt(code.substring(prefix.length()))); } catch (RuntimeException ignored) { }
        }
        return prefix + String.format("%06d", max + 1);
    }

    private String normalizeName(String value) {
        return value.trim().replace('(', '（').replace(')', '）').replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private Object emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record SelectedProduct(ImportRowView row, ProductImportCodeResolver.ResolvedProductCode resolved) { }
}
