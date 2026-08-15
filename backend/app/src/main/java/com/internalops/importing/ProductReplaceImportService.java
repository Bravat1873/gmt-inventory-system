package com.internalops.importing;

import com.internalops.auth.CurrentUser;
import com.internalops.auth.UserRole;
import com.internalops.productcode.ProductCodeSelection;
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
        for (SelectedProduct product : selected) insertProduct(product.row(), product.resolved());

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

        Map<String, List<SelectedProduct>> byCode = new LinkedHashMap<>();
        for (ImportRowView row : batch.rows()) {
            if (row.status() != ImportRowStatus.VALID) continue;
            ProductImportCodeResolver.ResolvedProductCode resolved = codeResolver.resolve(row.data());
            if (!resolved.productCode().equals(text(row.data(), "productCode"))
                    || !resolved.ruleFingerprint().equals(text(row.data(), "_ruleFingerprint"))) {
                throw new IllegalArgumentException("产品编码规则已变化，请重新预览后再提交");
            }
            byCode.computeIfAbsent(resolved.productCode().toLowerCase(Locale.ROOT), ignored -> new ArrayList<>())
                    .add(new SelectedProduct(row, resolved));
        }

        List<SelectedProduct> selected = new ArrayList<>();
        Set<Long> usedActions = new LinkedHashSet<>();
        for (List<SelectedProduct> group : byCode.values()) {
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
                if (action == null) throw new IllegalArgumentException("存在未处理的产品编号冲突");
                usedActions.add(product.row().id());
                if (action == ProductConflictAction.KEEP) {
                    kept++;
                    selected.add(product);
                }
            }
            if (kept > 1) throw new IllegalArgumentException("同一产品编号冲突组只能保留一行");
        }
        if (!usedActions.containsAll(actions.keySet())) throw new IllegalArgumentException("冲突决策与当前批次不一致");
        return selected;
    }

    private void insertProduct(ImportRowView row, ProductImportCodeResolver.ResolvedProductCode resolved) {
        Map<String, Object> data = row.data();
        ProductCodeSelection rules = resolved.selection();
        String model = nullableText(data, "model");
        String configuration = nullableText(data, "materialSpecification");
        String productConfiguration = nullableText(data, "productConfiguration");
        String productName = firstNonBlank(model, productConfiguration, resolved.productCode());
        int salesMoq = positiveInt(data.get("salesMinimumOrderQuantity"), 1);
        String materialType = materialType(text(data, "materialType"));
        String productType = productType(text(data, "productCategory"));

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sku(product_code,product_type,material_type,sku_code,code_suffix,model,product_name,
                        color,lock_body,configuration,product_configuration,unit,sales_minimum_order_quantity,enabled,
                        brand_rule_id,series_rule_id,body_color_rule_id,lock_type_rule_id,connectivity_rule_id,
                        sales_channel_rule_id,operating_entity_rule_id,language_rule_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,TRUE,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, resolved.productCode());
            statement.setString(2, productType);
            statement.setString(3, materialType);
            statement.setObject(4, nullableText(data, "customerMaterialCode"));
            statement.setObject(5, nullableText(data, "codeSuffix"));
            statement.setObject(6, model);
            statement.setString(7, productName);
            statement.setObject(8, nullableText(data, "bodyColor"));
            statement.setObject(9, nullableText(data, "lockType"));
            statement.setObject(10, configuration);
            statement.setObject(11, productConfiguration);
            statement.setString(12, "件");
            statement.setInt(13, salesMoq);
            statement.setLong(14, rules.brandRuleId());
            statement.setLong(15, rules.seriesRuleId());
            statement.setLong(16, rules.bodyColorRuleId());
            statement.setLong(17, rules.lockTypeRuleId());
            statement.setLong(18, rules.connectivityRuleId());
            statement.setLong(19, rules.salesChannelRuleId());
            statement.setLong(20, rules.operatingEntityRuleId());
            statement.setLong(21, rules.languageRuleId());
            return statement;
        }, keys);
        if (keys.getKey() == null) throw new IllegalStateException("新增产品失败，未生成数据编号");
        insertSupplierQuote(keys.getKey().longValue(), data);
    }

    private void insertSupplierQuote(long skuId, Map<String, Object> data) {
        String supplierName = text(data, "supplierName");
        if (supplierName.isBlank()) return;
        List<Long> suppliers = jdbc.query("SELECT id FROM supplier WHERE supplier_name=? AND enabled=TRUE",
                (rs, index) -> rs.getLong(1), supplierName);
        if (suppliers.size() != 1) throw new IllegalArgumentException("供应商不存在或名称不唯一：" + supplierName);
        jdbc.update("INSERT INTO sku_supplier_config(sku_id,supplier_id,purchase_price,moq,lead_time_days,enabled) VALUES(?,?,?,?,0,TRUE)",
                skuId, suppliers.get(0), decimalOrZero(data.get("supplierTaxPrice")), 1);
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

    private BigDecimal decimalOrZero(Object value) {
        BigDecimal price = value == null || value.toString().isBlank()
                ? BigDecimal.ZERO
                : new BigDecimal(value.toString());
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

    private record SelectedProduct(ImportRowView row, ProductImportCodeResolver.ResolvedProductCode resolved) { }
}
