package com.internalops.importing;

import org.apache.poi.ss.usermodel.Workbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

final class CustomerExcelParser {
    List<ParsedImportRow> parse(Workbook workbook) {
        var sheet = workbook.getSheet("Sheet1");
        if (sheet == null) throw new IllegalArgumentException("缺少工作表：Sheet1");
        List<ParsedImportRow> result = new ArrayList<>();
        for (int index = sheet.getFirstRowNum(); index <= sheet.getLastRowNum(); index++) {
            var row = sheet.getRow(index);
            String type = row == null ? "" : ExcelValueReader.text(row.getCell(0)).trim();
            String name = row == null ? "" : ExcelValueReader.text(row.getCell(2)).trim();
            if ("客户类型".equals(type)) continue;
            if (name.isBlank()) {
                name = type;
                type = "";
            }
            if (name.isBlank()) continue;
            var data = new LinkedHashMap<String, Object>();
            data.put("customerType", customerType(type));
            data.put("customerCode", text(row, 1));
            data.put("customerName", name);
            data.put("address", text(row, 3));
            data.put("businessContactName", text(row, 4));
            data.put("businessContactPhone", text(row, 5));
            data.put("orderContactName", text(row, 6));
            data.put("orderContactPhone", text(row, 7));
            data.put("financeContactName", text(row, 8));
            data.put("financeContactPhone", text(row, 9));
            data.put("invoiceTitle", text(row, 10));
            data.put("taxpayerId", text(row, 11));
            data.put("invoiceAddress", text(row, 12));
            data.put("invoicePhone", text(row, 13));
            data.put("bankName", text(row, 14));
            data.put("bankAccount", text(row, 15));
            result.add(new ParsedImportRow(sheet.getSheetName(), index + 1, ImportRowStatus.VALID, data, null));
        }
        return result;
    }

    private String customerType(String value) {
        String trimmed = value.trim();
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        if ("内销".equals(trimmed) || "内销客户".equals(trimmed) || "DOMESTIC".equals(normalized)) return "DOMESTIC";
        if ("外销".equals(trimmed) || "外销客户".equals(trimmed) || "EXPORT".equals(normalized)) return "EXPORT";
        return normalized;
    }

    private String text(org.apache.poi.ss.usermodel.Row row, int column) {
        String value = row == null ? "" : ExcelValueReader.text(row.getCell(column)).trim();
        return "—".equals(value) || "-".equals(value) ? "" : value;
    }
}
