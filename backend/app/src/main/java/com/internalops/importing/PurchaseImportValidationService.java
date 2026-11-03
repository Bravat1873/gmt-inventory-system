package com.internalops.importing;

import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

final class PurchaseImportValidationService {
    private final JdbcTemplate jdbc;
    PurchaseImportValidationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static String text(Map<String, Object> data, String key) {
        return Objects.toString(data.get(key), "").trim();
    }

    static String groupKey(ParsedImportRow row) {
        String group = text(row.data(), "purchaseGroup");
        return group.isBlank() ? "ROW:" + row.sheetName() + ":" + row.rowNumber() : "GROUP:" + group;
    }

    List<ParsedImportRow> validateAll(List<ParsedImportRow> rows) {
        Map<String, List<String>> headers = new HashMap<>();
        Set<String> inconsistent = new HashSet<>();
        for (ParsedImportRow row : rows) {
            List<String> header = List.of("supplierName", "expectedArrivalDate", "deliveryAddress", "remark")
                    .stream().map(key -> text(row.data(), key)).toList();
            List<String> prior = headers.putIfAbsent(groupKey(row), header);
            if (prior != null && !prior.equals(header)) inconsistent.add(groupKey(row));
        }
        List<ParsedImportRow> result = new ArrayList<>();
        for (ParsedImportRow row : rows) {
            Map<String, Object> data = new LinkedHashMap<>(row.data());
            data.keySet().removeIf(key -> key.startsWith("_"));
            try {
                if (inconsistent.contains(groupKey(row))) throw new IllegalArgumentException("同一采购分组的供应商、到货日期、地址和备注必须一致");
                validate(data);
                result.add(new ParsedImportRow(row.sheetName(), row.rowNumber(), ImportRowStatus.VALID, data, null));
            } catch (IllegalArgumentException exception) {
                result.add(new ParsedImportRow(row.sheetName(), row.rowNumber(), ImportRowStatus.ERROR, data, exception.getMessage()));
            }
        }
        return result;
    }

    private void validate(Map<String, Object> data) {
        String supplier = text(data, "supplierName"), product = text(data, "productCode");
        if (supplier.isBlank() || product.isBlank()) throw new IllegalArgumentException("供应商名称、产品编号不能为空");
        int quantity;
        BigDecimal price;
        try {
            quantity = new BigDecimal(text(data, "quantity")).intValueExact();
            price = new BigDecimal(text(data, "purchasePrice"));
        } catch (RuntimeException exception) { throw new IllegalArgumentException("数量须为正整数，含税单价须为有效数字"); }
        if (quantity <= 0) throw new IllegalArgumentException("采购数量必须为正整数");
        if (price.signum() < 0 || price.scale() > 4 || price.compareTo(new BigDecimal("99999999999999.9999")) > 0)
            throw new IllegalArgumentException("含税单价须为非负数、最多四位小数且不超出金额范围");
        String date = text(data, "expectedArrivalDate");
        try { if (!date.isBlank()) LocalDate.parse(date); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("预计到货日期须为 yyyy-MM-dd 格式"); }
        if (text(data, "deliveryAddress").length() > 1000 || text(data, "remark").length() > 500)
            throw new IllegalArgumentException("交货地址不能超过 1000 字，采购备注不能超过 500 字");
        List<Long> suppliers = jdbc.queryForList("SELECT id FROM supplier WHERE supplier_name=? AND enabled=TRUE", Long.class, supplier);
        if (suppliers.size() != 1) throw new IllegalArgumentException("供应商不存在、已停用或名称不唯一");
        String part = text(data, "customerPartNumber");
        List<Long> skus = part.isBlank()
                ? jdbc.queryForList("SELECT id FROM sku WHERE product_code=? AND enabled=TRUE", Long.class, product)
                : jdbc.queryForList("SELECT id FROM sku WHERE product_code=? AND customer_part_number=? AND enabled=TRUE", Long.class, product, part);
        if (skus.size() != 1) throw new IllegalArgumentException("产品不存在、已停用或不唯一；请核对产品编号和客户料号");
        String quoteId = text(data, "purchaseInfoId");
        Long requestedId = null;
        try { if (!quoteId.isBlank()) requestedId = Long.valueOf(quoteId); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("采购报价ID须为整数"); }
        var quotes = jdbc.queryForList("""
                SELECT pi.id,pi.moq FROM sku_supplier_purchase_info pi
                JOIN sku_supplier_config cfg ON cfg.id=pi.supplier_product_config_id
                WHERE cfg.supplier_id=? AND cfg.sku_id=? AND cfg.enabled=TRUE AND pi.enabled=TRUE
                  AND pi.purchase_price=? AND pi.moq IS NOT NULL AND pi.lead_time_days IS NOT NULL
                """, suppliers.get(0), skus.get(0), price);
        final Long selectedId = requestedId;
        if (selectedId != null) quotes = quotes.stream().filter(q -> ((Number) q.get("id")).longValue() == selectedId).toList();
        if (quotes.size() != 1) throw new IllegalArgumentException("未找到唯一有效采购报价；请核对供应商、产品、含税单价，或填写采购报价ID");
        if (quantity < ((Number) quotes.get(0).get("moq")).intValue()) throw new IllegalArgumentException("采购数量不能低于最小起订量 " + quotes.get(0).get("moq"));
        data.put("_supplierId", suppliers.get(0));
        data.put("_skuId", skus.get(0));
        data.put("_purchaseInfoId", quotes.get(0).get("id"));
        data.put("quantity", quantity);
        data.put("purchasePrice", price.toPlainString());
    }
}
