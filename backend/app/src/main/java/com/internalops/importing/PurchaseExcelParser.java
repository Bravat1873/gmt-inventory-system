package com.internalops.importing;

import org.apache.poi.ss.usermodel.*;
import java.util.*;

final class PurchaseExcelParser {
    private static final Map<String, String> FIELDS = Map.ofEntries(
            Map.entry("采购分组号", "purchaseGroup"), Map.entry("供应商名称", "supplierName"),
            Map.entry("产品编号", "productCode"), Map.entry("客户料号", "customerPartNumber"),
            Map.entry("数量", "quantity"), Map.entry("含税单价", "purchasePrice"),
            Map.entry("采购报价ID", "purchaseInfoId"), Map.entry("预计到货日期", "expectedArrivalDate"),
            Map.entry("交货地址", "deliveryAddress"), Map.entry("采购备注", "remark"));

    List<ParsedImportRow> parse(Workbook workbook) {
        for (Sheet sheet : workbook) {
            Row header = sheet.getRow(0);
            if (header == null) continue;
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (Cell cell : header) {
                String field = FIELDS.get(ExcelValueReader.text(cell).trim());
                if (field != null && columns.putIfAbsent(field, cell.getColumnIndex()) != null)
                    throw new IllegalArgumentException("采购导入存在重复表头：" + ExcelValueReader.text(cell));
            }
            if (!columns.keySet().containsAll(List.of("supplierName", "productCode", "quantity", "purchasePrice"))) continue;
            List<ParsedImportRow> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                Map<String, Object> data = new LinkedHashMap<>();
                for (String field : FIELDS.values()) {
                    Cell cell = columns.containsKey(field) ? row.getCell(columns.get(field)) : null;
                    String value = cell != null && field.equals("expectedArrivalDate")
                            && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)
                            ? cell.getLocalDateTimeCellValue().toLocalDate().toString() : ExcelValueReader.text(cell).trim();
                    data.put(field, value);
                }
                if (data.values().stream().allMatch(value -> value.toString().isBlank())) continue;
                rows.add(new ParsedImportRow(sheet.getSheetName(), i + 1, ImportRowStatus.VALID, data, null));
            }
            if (rows.isEmpty()) throw new IllegalArgumentException("采购导入没有数据行");
            return rows;
        }
        throw new IllegalArgumentException("采购导入缺少必填表头：供应商名称、产品编号、数量、含税单价");
    }
}
