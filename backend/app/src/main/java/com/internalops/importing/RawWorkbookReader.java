package com.internalops.importing;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

final class RawWorkbookReader {
    private final DataFormatter formatter = new DataFormatter();

    RawWorkbookSnapshot read(byte[] fileContent) {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(fileContent))) {
            List<RawWorkbookSheet> sheets = new ArrayList<>();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                var sheet = workbook.getSheetAt(sheetIndex);
                int columnCount = maxColumnCount(sheet);
                List<RawWorkbookRow> rows = new ArrayList<>();
                for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    List<String> cells = new ArrayList<>();
                    for (int columnIndex = 0; columnIndex < columnCount; columnIndex++) {
                        cells.add(row == null ? "" : formatter.formatCellValue(row.getCell(columnIndex)).trim());
                    }
                    rows.add(new RawWorkbookRow(rowIndex + 1, cells));
                }
                sheets.add(new RawWorkbookSheet(sheetIndex, sheet.getSheetName(), columnCount, rows));
            }
            return new RawWorkbookSnapshot(fileContent, sheets);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Excel 文件无法读取：" + exception.getMessage(), exception);
        }
    }

    private int maxColumnCount(org.apache.poi.ss.usermodel.Sheet sheet) {
        int max = 0;
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) max = Math.max(max, row.getLastCellNum());
        }
        return Math.max(max, 0);
    }
}
