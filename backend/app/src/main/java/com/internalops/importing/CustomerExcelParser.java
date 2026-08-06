package com.internalops.importing;

import org.apache.poi.ss.usermodel.Workbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class CustomerExcelParser {
    List<ParsedImportRow> parse(Workbook workbook) {
        var sheet = workbook.getSheet("Sheet1");
        if (sheet == null) throw new IllegalArgumentException("缺少工作表：Sheet1");
        List<ParsedImportRow> result = new ArrayList<>();
        for (int index = sheet.getFirstRowNum(); index <= sheet.getLastRowNum(); index++) {
            var row = sheet.getRow(index);
            String name = row == null ? "" : ExcelValueReader.text(row.getCell(0));
            if (name.isBlank()) continue;
            var data = new LinkedHashMap<String, Object>();
            data.put("customerName", name);
            result.add(new ParsedImportRow(sheet.getSheetName(), index + 1, ImportRowStatus.VALID, data, null));
        }
        return result;
    }
}
