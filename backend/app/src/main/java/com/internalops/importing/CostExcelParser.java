package com.internalops.importing;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class CostExcelParser {
    List<ParsedImportRow> parse(Workbook workbook) {
        Sheet sheet = findCostSheet(workbook);
        int headerRowIndex = findHeaderRow(sheet);
        ColumnMap columns = resolveColumns(sheet.getRow(headerRowIndex));
        List<ParsedImportRow> result = new ArrayList<>();
        for (int index = headerRowIndex + 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null || isBlankDataRow(row, columns)) continue;
            var data = new LinkedHashMap<String, Object>();
            data.put("skuCode", text(row, columns.skuCode()));
            data.put("model", text(row, columns.model()));
            data.put("color", text(row, columns.color()));
            data.put("lockBody", text(row, columns.lockBody()));
            data.put("configuration", text(row, columns.configuration()));
            var cost = ExcelValueReader.decimal(cell(row, columns.cost()));
            data.put("cost", cost == null ? "" : cost);
            var factoryPrice = ExcelValueReader.decimal(cell(row, columns.factoryPrice()));
            data.put("factoryPrice", factoryPrice == null ? "" : factoryPrice);
            var priceDifference = ExcelValueReader.decimal(cell(row, columns.priceDifference()));
            data.put("priceDifference", priceDifference == null ? "" : priceDifference);
            data.put("remark", text(row, columns.remark()));
            ImportRowStatus status = cost != null && cost.signum() > 0 ? ImportRowStatus.VALID : ImportRowStatus.ERROR;
            String error = status == ImportRowStatus.ERROR ? "成本单价必须大于 0" : null;
            result.add(new ParsedImportRow(sheet.getSheetName(), index + 1, status, data, error));
        }
        return result;
    }

    private Sheet findCostSheet(Workbook workbook) {
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            Sheet candidate = workbook.getSheetAt(index);
            try {
                findHeaderRow(candidate);
                return candidate;
            } catch (IllegalArgumentException ignored) {
                // Try the next worksheet. Product workbooks often rename Sheet1.
            }
        }
        throw new IllegalArgumentException("缺少表头：物料编号和成本单价（含税）");
    }

    private int findHeaderRow(Sheet sheet) {
        for (int index = 0; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row != null && columnOf(row, "物料编号") >= 0 && columnOf(row, "成本单价") >= 0) return index;
        }
        throw new IllegalArgumentException("缺少表头：物料编号和成本单价（含税）");
    }

    private ColumnMap resolveColumns(Row header) {
        return new ColumnMap(
                optionalColumn(header, "物料编号"),
                optionalColumn(header, "型号"),
                optionalColumn(header, "颜色"),
                optionalColumn(header, "锁体"),
                optionalColumn(header, "物料规格"),
                requiredColumn(header, "成本单价"),
                optionalColumn(header, "转厂价格"),
                optionalColumn(header, "差异"),
                optionalColumn(header, "备注"));
    }

    private int requiredColumn(Row header, String label) {
        int column = columnOf(header, label);
        if (column < 0) throw new IllegalArgumentException("缺少表头：" + label);
        return column;
    }

    private int optionalColumn(Row header, String label) {
        return columnOf(header, label);
    }

    private int columnOf(Row header, String label) {
        for (int column = Math.max(0, header.getFirstCellNum()); column < header.getLastCellNum(); column++) {
            String value = ExcelValueReader.text(header.getCell(column)).replaceAll("\\s+", "");
            if (value.contains(label)) return column;
        }
        return -1;
    }

    private boolean isBlankDataRow(Row row, ColumnMap columns) {
        return text(row, columns.skuCode()).isBlank()
                && text(row, columns.model()).isBlank()
                && text(row, columns.color()).isBlank()
                && text(row, columns.lockBody()).isBlank()
                && text(row, columns.configuration()).isBlank()
                && text(row, columns.cost()).isBlank();
    }

    private String text(Row row, int column) {
        return ExcelValueReader.text(cell(row, column));
    }

    private Cell cell(Row row, int column) {
        return column < 0 ? null : row.getCell(column);
    }

    private record ColumnMap(int skuCode, int model, int color, int lockBody, int configuration, int cost,
                             int factoryPrice, int priceDifference, int remark) { }
}
