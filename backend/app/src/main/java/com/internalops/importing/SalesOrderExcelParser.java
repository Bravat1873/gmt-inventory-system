package com.internalops.importing;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SalesOrderExcelParser {
    private static final Map<String, String> FIELDS = Map.ofEntries(
            Map.entry("外部订单号", "externalOrderNo"), Map.entry("客户编码", "customerCode"), Map.entry("订单日期", "orderDate"),
            Map.entry("订单类型", "orderType"), Map.entry("订单状态", "orderStatus"), Map.entry("销售员", "salesperson"),
            Map.entry("客户料号", "customerMaterialCode"), Map.entry("产品编号", "productCode"), Map.entry("订单数量", "quantity"),
            Map.entry("含税单价", "salePrice"), Map.entry("业务联系人", "businessContactName"), Map.entry("业务联系电话", "businessContactPhone"),
            Map.entry("订单联系人", "orderContactName"), Map.entry("订单联系电话", "orderContactPhone"), Map.entry("财务联系人", "financeContactName"),
            Map.entry("财务联系电话", "financeContactPhone"), Map.entry("收货地址", "deliveryAddress"), Map.entry("收货联系人", "deliveryContact"),
            Map.entry("收货联系电话", "deliveryPhone"), Map.entry("发货方式", "shippingMethod"), Map.entry("订单备注", "remark"));

    List<ParsedImportRow> parse(Workbook workbook) {
        Sheet sheet = workbook.getSheet("订单导入");
        if (sheet == null) throw new IllegalArgumentException("缺少工作表：订单导入");
        Map<String, Integer> columns = columns(sheet.getRow(0));
        if (!columns.containsKey("externalOrderNo") || !columns.containsKey("customerCode") || !columns.containsKey("productCode")) {
            throw new IllegalArgumentException("缺少订单导入必填表头");
        }
        List<ParsedImportRow> result = new ArrayList<>();
        for (int index = 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null || columns.values().stream().allMatch(column -> text(row, column).isBlank())) continue;
            Map<String, Object> data = new LinkedHashMap<>();
            for (String field : FIELDS.values()) data.put(field, text(row, columns.get(field)));
            result.add(new ParsedImportRow(sheet.getSheetName(), index + 1, ImportRowStatus.VALID, data, null));
        }
        return result;
    }

    private Map<String, Integer> columns(Row header) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (header == null) return result;
        for (int index = Math.max(0, header.getFirstCellNum()); index < header.getLastCellNum(); index++) {
            int column = index;
            String label = normalize(ExcelValueReader.text(header.getCell(index)));
            FIELDS.forEach((headerName, field) -> { if (normalize(headerName).equals(label)) result.putIfAbsent(field, column); });
        }
        return result;
    }

    private String text(Row row, Integer column) {
        Cell cell = row == null || column == null ? null : row.getCell(column);
        return ExcelValueReader.text(cell);
    }

    private String normalize(String value) { return value.replaceAll("\\s+", ""); }
}
