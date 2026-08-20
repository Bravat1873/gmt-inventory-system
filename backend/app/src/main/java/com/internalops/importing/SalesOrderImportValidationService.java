package com.internalops.importing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SalesOrderImportValidationService {
    private static final String[][] ORDER_LEVEL_FIELDS = {
            {"customerCode", "客户编码"}, {"orderDate", "订单日期"}, {"orderType", "订单类型"},
            {"orderStatus", "订单状态"}, {"salesperson", "销售员"},
            {"businessContactName", "商务联系人"}, {"businessContactPhone", "商务联系人电话"},
            {"orderContactName", "订单联系人"}, {"orderContactPhone", "订单联系人电话"},
            {"financeContactName", "财务联系人"}, {"financeContactPhone", "财务联系人电话"},
            {"deliveryAddress", "收货地址"}, {"deliveryContact", "收货联系人"},
            {"deliveryPhone", "收货联系电话"}, {"shippingMethod", "运输方式"}, {"remark", "备注"}
    };

    private final JdbcTemplate jdbc;

    public SalesOrderImportValidationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ParsedImportRow> validateAll(List<ParsedImportRow> rows) {
        List<ParsedImportRow> result = new ArrayList<>();
        for (ParsedImportRow row : rows) result.add(row.status() == ImportRowStatus.VALID ? validate(row) : row);
        return validateOrderGroups(result);
    }

    private List<ParsedImportRow> validateOrderGroups(List<ParsedImportRow> rows) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            ParsedImportRow row = rows.get(index);
            if (row.status() != ImportRowStatus.IGNORED) {
                groups.computeIfAbsent(SalesOrderImportGroupKey.from(row.data()), ignored -> new ArrayList<>()).add(index);
            }
        }
        for (Map.Entry<String, List<Integer>> group : groups.entrySet()) validateOrderGroup(rows, group.getKey(), group.getValue());
        return rows;
    }

    private void validateOrderGroup(List<ParsedImportRow> rows, String externalOrderNo, List<Integer> indexes) {
        if (indexes.stream().anyMatch(index -> rows.get(index).status() == ImportRowStatus.ERROR)) {
            for (int index : indexes) {
                ParsedImportRow row = rows.get(index);
                if (row.status() == ImportRowStatus.VALID) rows.set(index, error(row, copyData(row), "该订单存在错误明细，整单不可提交"));
            }
            return;
        }
        if (!externalOrderNo.isBlank() && externalOrderNoExists(externalOrderNo)) {
            markOrderConflict(rows, indexes, externalOrderNo);
            return;
        }
        ParsedImportRow first = rows.get(indexes.get(0));
        for (String[] field : ORDER_LEVEL_FIELDS) {
            String expected = normalizedOrderField(first.data(), field[0]);
            if (indexes.stream().map(rows::get).map(row -> normalizedOrderField(row.data(), field[0])).anyMatch(value -> !expected.equals(value))) {
                markGroupError(rows, indexes, "同一外部订单号的订单级字段不一致：" + field[1]);
                return;
            }
        }
    }

    private void markOrderConflict(List<ParsedImportRow> rows, List<Integer> indexes, String externalOrderNo) {
        for (int index : indexes) {
            ParsedImportRow row = rows.get(index);
            Map<String, Object> data = copyData(row);
            data.put("_conflict", true);
            data.put("_conflictField", "externalOrderNo");
            data.put("_conflictGroup", externalOrderNo);
            data.put("_conflictLabel", "外部订单号：" + externalOrderNo);
            data.put("_conflictAction", "UNRESOLVED");
            rows.set(index, new ParsedImportRow(row.sheetName(), row.rowNumber(), ImportRowStatus.VALID, data, null));
        }
    }

    private boolean externalOrderNoExists(String externalOrderNo) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE external_order_no=?", Integer.class, externalOrderNo);
        return count != null && count > 0;
    }

    private void markGroupError(List<ParsedImportRow> rows, List<Integer> indexes, String message) {
        for (int index : indexes) {
            ParsedImportRow row = rows.get(index);
            Map<String, Object> data = copyData(row);
            if (message.equals("外部订单号已存在")) {
                String orderNo = text(data, "externalOrderNo");
                data.put("_conflict", true);
                data.put("_conflictField", "externalOrderNo");
                data.put("_conflictGroup", orderNo);
                data.put("_conflictLabel", "订单号：" + orderNo);
                data.put("_conflictAction", "SKIP");
            }
            rows.set(index, error(row, data, message));
        }
    }

    private Map<String, Object> copyData(ParsedImportRow row) { return new LinkedHashMap<>(row.data()); }
    private String normalizedOrderField(Map<String, Object> data, String key) { return text(data, key).replaceAll("\\s+", " "); }

    private ParsedImportRow validate(ParsedImportRow row) {
        Map<String, Object> data = new LinkedHashMap<>(row.data());
        resolveCustomerCode(data);
        String required = required(data);
        if (required != null) return error(row, data, required);
        LocalDate date;
        int quantity;
        BigDecimal price;
        try { date = parseDate(text(data, "orderDate")); } catch (DateTimeParseException exception) { return error(row, data, "订单日期格式错误"); }
        if (!List.of("工程订单", "零售订单", "前置订单").contains(text(data, "orderType"))) return error(row, data, "订单类型必须为工程订单、零售订单或前置订单");
        String normalizedStatus = normalizedStatus(text(data, "orderStatus"));
        if (normalizedStatus == null) return error(row, data, "订单状态必须为草稿或正式订单");
        try { quantity = new BigDecimal(text(data, "quantity")).intValueExact(); } catch (NumberFormatException | ArithmeticException exception) { return error(row, data, "订单数量必须为正整数"); }
        if (quantity <= 0) return error(row, data, "订单数量必须为正整数");
        try { price = new BigDecimal(text(data, "salePrice").replace(",", "")); } catch (NumberFormatException exception) { return error(row, data, "含税单价格式错误"); }
        if (price.signum() < 0) return error(row, data, "含税单价必须大于等于 0");
        List<Long> customers = jdbc.query("SELECT id FROM customer WHERE customer_code=? AND enabled=TRUE", (rs, index) -> rs.getLong(1), text(data, "customerCode"));
        if (customers.isEmpty()) return error(row, data, "客户编码不存在或未启用");
        if (customers.size() != 1) return error(row, data, "客户编码匹配不唯一");
        String productCode = text(data, "productCode");
        String materialCode = text(data, "customerPartNumber");
        List<Sku> skus = findSkus(productCode, materialCode);
        if (skus.isEmpty() && !materialCode.isBlank()) skus = findSkus(materialCode, productCode);
        if (skus.isEmpty()) return error(row, data, "产品编号不存在或未启用");
        if (skus.size() != 1) return error(row, data, "产品编号匹配不唯一");
        Sku sku = skus.get(0);
        data.put("orderDate", date.toString()); data.put("quantity", quantity); data.put("salePrice", price);
        data.put("_customerId", customers.get(0)); data.put("_skuId", sku.id()); data.put("_normalizedStatus", normalizedStatus);
        return new ParsedImportRow(row.sheetName(), row.rowNumber(), ImportRowStatus.VALID, data, null);
    }

    private List<Sku> findSkus(String productCode, String materialCode) {
        if (materialCode.isBlank()) {
            return jdbc.query("SELECT id, customer_part_number FROM sku WHERE product_code=? AND enabled=TRUE",
                    (rs, index) -> new Sku(rs.getLong(1), rs.getString(2)), productCode);
        }
        return jdbc.query("SELECT id, customer_part_number FROM sku WHERE product_code=? AND customer_part_number=? AND enabled=TRUE",
                (rs, index) -> new Sku(rs.getLong(1), rs.getString(2)), productCode, materialCode);
    }

    private String required(Map<String, Object> data) {
        String[][] fields = {{"customerCode", "客户编码"}, {"orderDate", "订单日期"}, {"orderType", "订单类型"}, {"salesperson", "销售员"}, {"productCode", "产品编号"}, {"quantity", "订单数量"}, {"salePrice", "含税单价"}};
        for (String[] field : fields) if (text(data, field[0]).isBlank()) return field[1] + "不能为空";
        return null;
    }

    private void resolveCustomerCode(Map<String, Object> data) {
        if (!text(data, "customerCode").isBlank() || text(data, "customerName").isBlank()) return;
        List<String> codes = jdbc.query("SELECT customer_code FROM customer WHERE customer_name=? AND enabled=TRUE",
                (rs, index) -> rs.getString(1), text(data, "customerName"));
        if (codes.size() == 1) data.put("customerCode", codes.get(0));
    }

    private LocalDate parseDate(String value) {
        String normalized = value.trim().replace('/', '-').replace('.', '-');
        for (DateTimeFormatter formatter : List.of(
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("yyyy-M-d"))) {
            try { return LocalDate.parse(normalized, formatter); } catch (DateTimeParseException ignored) { }
        }
        throw new DateTimeParseException("订单日期格式错误", value, 0);
    }
    private String normalizedStatus(String value) { return value.isBlank() || "正式订单".equals(value) ? "PENDING_CUSTOMER_PAYMENT" : "草稿".equals(value) ? "DRAFT" : null; }
    private String text(Map<String, Object> data, String key) { Object value = data.get(key); return value == null ? "" : value.toString().trim(); }
    private ParsedImportRow error(ParsedImportRow row, Map<String, Object> data, String message) { return new ParsedImportRow(row.sheetName(), row.rowNumber(), ImportRowStatus.ERROR, data, message); }
    private record Sku(long id, String customerPartNumber) { }
}
