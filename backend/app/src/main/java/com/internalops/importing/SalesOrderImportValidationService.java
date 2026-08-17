package com.internalops.importing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SalesOrderImportValidationService {
    private final JdbcTemplate jdbc;

    public SalesOrderImportValidationService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<ParsedImportRow> validateAll(List<ParsedImportRow> rows) {
        List<ParsedImportRow> result = new ArrayList<>();
        for (ParsedImportRow row : rows) result.add(row.status() == ImportRowStatus.VALID ? validate(row) : row);
        return result;
    }

    private ParsedImportRow validate(ParsedImportRow row) {
        Map<String, Object> data = new LinkedHashMap<>(row.data());
        String required = required(data);
        if (required != null) return error(row, data, required);
        LocalDate date;
        int quantity;
        BigDecimal price;
        try { date = LocalDate.parse(text(data, "orderDate")); } catch (DateTimeParseException exception) { return error(row, data, "订单日期格式错误"); }
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
        List<Sku> skus = jdbc.query("SELECT id, sku_code, sales_minimum_order_quantity FROM sku WHERE product_code=? AND enabled=TRUE", (rs, index) -> new Sku(rs.getLong(1), rs.getString(2), rs.getInt(3)), text(data, "productCode"));
        if (skus.isEmpty()) return error(row, data, "产品编号不存在或未启用");
        if (skus.size() != 1) return error(row, data, "产品编号匹配不唯一");
        Sku sku = skus.get(0);
        if (quantity < sku.minimumQuantity()) return error(row, data, "订单数量不得低于销售最小起订量");
        String materialCode = text(data, "customerMaterialCode");
        if (!materialCode.isBlank() && !materialCode.equals(sku.skuCode())) return error(row, data, "客户料号与产品档案不一致");
        data.put("orderDate", date.toString()); data.put("quantity", quantity); data.put("salePrice", price);
        data.put("_customerId", customers.get(0)); data.put("_skuId", sku.id()); data.put("_normalizedStatus", normalizedStatus);
        return new ParsedImportRow(row.sheetName(), row.rowNumber(), ImportRowStatus.VALID, data, null);
    }

    private String required(Map<String, Object> data) {
        String[][] fields = {{"externalOrderNo", "外部订单号"}, {"customerCode", "客户编码"}, {"orderDate", "订单日期"}, {"orderType", "订单类型"}, {"salesperson", "销售员"}, {"productCode", "产品编号"}, {"quantity", "订单数量"}, {"salePrice", "含税单价"}};
        for (String[] field : fields) if (text(data, field[0]).isBlank()) return field[1] + "不能为空";
        return null;
    }
    private String normalizedStatus(String value) { return value.isBlank() || "正式订单".equals(value) ? "PENDING_CUSTOMER_PAYMENT" : "草稿".equals(value) ? "DRAFT" : null; }
    private String text(Map<String, Object> data, String key) { Object value = data.get(key); return value == null ? "" : value.toString().trim(); }
    private ParsedImportRow error(ParsedImportRow row, Map<String, Object> data, String message) { return new ParsedImportRow(row.sheetName(), row.rowNumber(), ImportRowStatus.ERROR, data, message); }
    private record Sku(long id, String skuCode, int minimumQuantity) { }
}
