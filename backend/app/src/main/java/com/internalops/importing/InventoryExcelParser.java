package com.internalops.importing;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class InventoryExcelParser {
    private static final List<String> CUSTOMER_PART_NUMBER_HEADERS = List.of("客户料号SKU");
    private static final Pattern MMDD = Pattern.compile("^(\\d{2})(\\d{2})$");
    private static final Pattern YEAR = Pattern.compile(".*?(20\\d{2}).*");
    private static final List<String> LOCKED_SOURCES = List.of("铭爱钧乔", "博乐龙米", "老挝", "贝朗", "马来西亚");

    List<ParsedImportRow> parse(Workbook workbook) {
        Sheet sheet = findInventorySheet(workbook);
        int headerRowIndex = findHeaderRow(sheet);
        ColumnMap columns = resolveColumns(sheet, headerRowIndex);
        int firstDataRow = findFirstDataRow(sheet, headerRowIndex, columns);
        HeaderRange headers = new HeaderRange(headerRowIndex, firstDataRow);
        columns = resolveSecondaryColumns(sheet, columns, headers);
        Integer workbookYear = findWorkbookYear(sheet, headers);
        List<MovementColumn> movementColumns = resolveMovementColumns(sheet, headers, workbookYear);

        List<ParsedImportRow> result = new ArrayList<>();
        for (int rowIndex = firstDataRow; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isCompletelyBlank(row)) continue;
            String customerPartNumber = ExcelValueReader.text(row.getCell(columns.customerPartNumber()));
            if (customerPartNumber.isBlank()) {
                Map<String, Object> data = missingSkuData(row, columns);
                if (isGroupRow(row)) {
                    result.add(new ParsedImportRow(sheet.getSheetName(), rowIndex + 1, ImportRowStatus.IGNORED, data,
                            "分组、标题或无库存汇总行"));
                } else if (hasActualInventory(data)) {
                    result.add(new ParsedImportRow(sheet.getSheetName(), rowIndex + 1, ImportRowStatus.ERROR, data,
                            "实际库存行缺少客户料号 SKU；请根据来源行标识补齐 SKU 后再导入"));
                } else {
                    result.add(new ParsedImportRow(sheet.getSheetName(), rowIndex + 1, ImportRowStatus.IGNORED, data,
                            "分组、标题或无库存汇总行"));
                }
                continue;
            }
            try {
                result.add(parseDataRow(sheet, row, rowIndex, columns, movementColumns));
            } catch (IllegalArgumentException exception) {
                result.add(new ParsedImportRow(sheet.getSheetName(), rowIndex + 1, ImportRowStatus.ERROR,
                        sourceData(row, columns), exception.getMessage()));
            }
        }
        return result;
    }

    private ParsedImportRow parseDataRow(Sheet sheet, Row row, int rowIndex, ColumnMap columns,
                                         List<MovementColumn> movementColumns) {
        var data = new LinkedHashMap<String, Object>();
        data.put("customerPartNumber", ExcelValueReader.text(cell(row, columns.customerPartNumber())));
        data.put("model", ExcelValueReader.text(cell(row, columns.model())));
        data.put("configuration", ExcelValueReader.text(cell(row, columns.configuration())));
        data.put("version", ExcelValueReader.text(cell(row, columns.version())));
        data.put("color", ExcelValueReader.text(cell(row, columns.color())));
        data.put("lockBody", ExcelValueReader.text(cell(row, columns.lockBody())));
        data.put("unit", ExcelValueReader.text(cell(row, columns.unit())));
        data.put("actualQuantity", ExcelValueReader.integer(cell(row, columns.actualQuantity())));
        data.put("sourceAvailableQuantity", ExcelValueReader.integer(cell(row, columns.sourceAvailableQuantity())));
        int locked = 0;
        for (int index = 0; index < columns.lockedColumns().size(); index++) {
            int amount = ExcelValueReader.integer(cell(row, columns.lockedColumns().get(index)));
            data.put("lockedBreakdown" + (index + 1), amount);
            locked += amount;
        }
        data.put("lockedQuantity", locked);
        data.put("inTransitQuantity", ExcelValueReader.integer(cell(row, columns.inTransitQuantity())));
        data.put("supplierName", ExcelValueReader.text(cell(row, columns.supplierName())));
        data.put("remark", ExcelValueReader.text(cell(row, columns.remark())));
        List<Map<String, Object>> movements = movements(row, movementColumns);
        data.put("movements", movements);
        data.put("movementSummary", movementSummary(movements));
        return new ParsedImportRow(sheet.getSheetName(), rowIndex + 1, ImportRowStatus.VALID, data, null);
    }

    private Map<String, Object> missingSkuData(Row row, ColumnMap columns) {
        Map<String, Object> data = sourceData(row, columns);
        data.put("customerPartNumber", "");
        return data;
    }

    private Map<String, Object> sourceData(Row row, ColumnMap columns) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerPartNumber", ExcelValueReader.text(cell(row, columns.customerPartNumber())));
        data.put("model", ExcelValueReader.text(cell(row, columns.model())));
        data.put("configuration", ExcelValueReader.text(cell(row, columns.configuration())));
        data.put("version", ExcelValueReader.text(cell(row, columns.version())));
        data.put("color", ExcelValueReader.text(cell(row, columns.color())));
        data.put("lockBody", ExcelValueReader.text(cell(row, columns.lockBody())));
        data.put("actualQuantity", ExcelValueReader.text(cell(row, columns.actualQuantity())));
        data.put("sourceRowIdentity", "型号=" + data.get("model") + "；配置=" + data.get("configuration")
                + "；版本=" + data.get("version") + "；颜色=" + data.get("color") + "；锁体=" + data.get("lockBody"));
        return data;
    }

    private boolean hasActualInventory(Map<String, Object> data) {
        String actual = String.valueOf(data.get("actualQuantity")).trim();
        return !actual.isBlank();
    }

    private boolean isGroupRow(Row row) {
        String firstCell = ExcelValueReader.text(row.getCell(0));
        return !firstCell.isBlank() && !firstCell.matches("[+-]?\\d+(?:\\.\\d+)?");
    }

    private int findFirstDataRow(Sheet sheet, int headerRowIndex, ColumnMap columns) {
        for (int rowIndex = headerRowIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            // An inventory value marks the beginning of data even when it is malformed.
            // The normal row parser must see that row so it can expose an ERROR instead
            // of silently treating it as part of the multi-row header area.
            if (!ExcelValueReader.text(row.getCell(columns.customerPartNumber())).isBlank()
                    || !ExcelValueReader.text(row.getCell(columns.actualQuantity())).isBlank()) {
                return rowIndex;
            }
        }
        return headerRowIndex + 1;
    }

    private boolean isCompletelyBlank(Row row) {
        for (int column = Math.max(0, row.getFirstCellNum()); column < row.getLastCellNum(); column++) {
            if (!ExcelValueReader.text(row.getCell(column)).isBlank()) return false;
        }
        return true;
    }

    private List<Map<String, Object>> movements(Row row, List<MovementColumn> columns) {
        List<Map<String, Object>> movements = new ArrayList<>();
        for (MovementColumn column : columns) {
            String raw = ExcelValueReader.text(row.getCell(column.index()));
            if (raw.isBlank()) continue;
            int quantity = ExcelValueReader.integer(row.getCell(column.index()));
            if (quantity == 0) continue;
            Map<String, Object> movement = new LinkedHashMap<>();
            movement.put("date", column.date().toString());
            movement.put("direction", column.direction());
            movement.put("quantity", quantity);
            movement.put("sourceColumn", column.sourceColumn());
            movements.add(movement);
        }
        return movements;
    }

    private String movementSummary(List<Map<String, Object>> movements) {
        return movements.stream().map(movement -> movement.get("sourceColumn") + " " + movement.get("quantity"))
                .reduce((left, right) -> left + "；" + right).orElse("");
    }

    private Sheet findInventorySheet(Workbook workbook) {
        for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
            Sheet candidate = workbook.getSheetAt(index);
            try { findHeaderRow(candidate); return candidate; } catch (IllegalArgumentException ignored) { }
        }
        throw new IllegalArgumentException("缺少包含客户料号 SKU 的库存工作表");
    }

    private int findHeaderRow(Sheet sheet) {
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null && skuColumn(row) >= 0) return rowIndex;
        }
        throw new IllegalArgumentException("缺少表头：客户料号 SKU");
    }

    private ColumnMap resolveColumns(Sheet sheet, int headerRowIndex) {
        Row header = sheet.getRow(headerRowIndex);
        int skuColumn = skuColumn(header);
        if (skuColumn < 0) throw new IllegalArgumentException("缺少表头：客户料号 SKU");
        return new ColumnMap(skuColumn, optionalColumn(header, "型号"),
                optionalColumn(header, "产品配置"), optionalColumn(header, "版本"), optionalColumn(header, "颜色"),
                optionalColumn(header, "锁体"), optionalColumn(header, "单位"), requiredColumn(header, "实际库存数量"),
                optionalColumn(header, "可用库存数量"), List.of(), requiredColumn(header, "在途数量"), -1, -1);
    }

    private ColumnMap resolveSecondaryColumns(Sheet sheet, ColumnMap base, HeaderRange headers) {
        List<Integer> lockedColumns = new ArrayList<>();
        for (String source : LOCKED_SOURCES) lockedColumns.add(optionalColumn(sheet, headers, source));
        if (lockedColumns.stream().allMatch(column -> column < 0)) {
            int legacyLockedTotal = optionalColumn(sheet, headers, "已锁定数量");
            lockedColumns.clear();
            for (int offset = 0; offset < LOCKED_SOURCES.size(); offset++) lockedColumns.add(legacyLockedTotal + offset);
        }
        int supplierColumn = optionalColumn(sheet, headers, "供应商");
        int remarkColumn = optionalColumn(sheet, headers, "备注");
        if (supplierColumn < 0 && base.inTransitQuantity() >= 0) supplierColumn = base.inTransitQuantity() + 1;
        if (remarkColumn < 0 && base.inTransitQuantity() >= 0) remarkColumn = base.inTransitQuantity() + 2;
        return new ColumnMap(base.customerPartNumber(), base.model(), base.configuration(), base.version(), base.color(), base.lockBody(),
                base.unit(), base.actualQuantity(), base.sourceAvailableQuantity(), lockedColumns, base.inTransitQuantity(),
                supplierColumn, remarkColumn);
    }

    private int skuColumn(Row header) {
        return CUSTOMER_PART_NUMBER_HEADERS.stream().mapToInt(label -> columnOf(header, label)).filter(column -> column >= 0)
                .findFirst().orElse(-1);
    }

    private Integer findWorkbookYear(Sheet sheet, HeaderRange headers) {
        for (int rowIndex = 0; rowIndex < headers.endExclusive(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            for (int column = Math.max(0, row.getFirstCellNum()); column < row.getLastCellNum(); column++) {
                Matcher matcher = YEAR.matcher(ExcelValueReader.text(row.getCell(column)));
                if (matcher.matches()) return Integer.parseInt(matcher.group(1));
            }
        }
        return null;
    }

    private List<MovementColumn> resolveMovementColumns(Sheet sheet, HeaderRange headers, Integer year) {
        List<MovementColumn> result = new ArrayList<>();
        int maxColumns = maxColumns(sheet, headers);
        for (int column = 0; column < maxColumns; column++) {
            String dateLabel = headerValue(sheet, headers, column, MMDD);
            if (dateLabel == null) continue;
            String direction = headerDirection(sheet, headers, column);
            if (direction == null) continue;
            if (year == null) throw new IllegalArgumentException("缺少库存日期列所属年份");
            Matcher matcher = MMDD.matcher(dateLabel);
            try {
                if (!matcher.matches()) throw new IllegalArgumentException("日期列表头无效：" + dateLabel);
                LocalDate date = LocalDate.of(year, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
                result.add(new MovementColumn(column, date, direction, excelColumnName(column) + ":" + dateLabel + direction));
            } catch (DateTimeException exception) {
                throw new IllegalArgumentException("日期列表头无效：" + dateLabel, exception);
            }
        }
        return result;
    }

    private String headerValue(Sheet sheet, HeaderRange headers, int column, Pattern pattern) {
        for (int rowIndex = headers.start(); rowIndex < headers.endExclusive(); rowIndex++) {
            String value = ExcelValueReader.text(cell(sheet, rowIndex, column));
            if (pattern.matcher(value).matches()) return value;
        }
        return null;
    }

    private String headerDirection(Sheet sheet, HeaderRange headers, int column) {
        for (int rowIndex = headers.start(); rowIndex < headers.endExclusive(); rowIndex++) {
            String value = ExcelValueReader.text(cell(sheet, rowIndex, column));
            if ("入库".equals(value) || "出库".equals(value)) return value;
        }
        return null;
    }

    private int maxColumns(Sheet sheet, HeaderRange headers) {
        int max = 0;
        for (int rowIndex = headers.start(); rowIndex < headers.endExclusive(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) max = Math.max(max, row.getLastCellNum());
        }
        return max;
    }

    private int requiredColumn(Sheet sheet, HeaderRange headers, String expected) {
        for (int rowIndex = headers.start(); rowIndex < headers.endExclusive(); rowIndex++) {
            int column = columnOf(sheet.getRow(rowIndex), expected);
            if (column >= 0) return column;
        }
        throw new IllegalArgumentException("缺少表头：" + expected);
    }

    private int requiredColumn(Row row, String expected) {
        int column = columnOf(row, expected);
        if (column < 0) throw new IllegalArgumentException("缺少表头：" + expected);
        return column;
    }

    private int optionalColumn(Row row, String expected) {
        return columnOf(row, expected);
    }

    private int optionalColumn(Sheet sheet, HeaderRange headers, String expected) {
        for (int rowIndex = headers.start(); rowIndex < headers.endExclusive(); rowIndex++) {
            int column = columnOf(sheet.getRow(rowIndex), expected);
            if (column >= 0) return column;
        }
        return -1;
    }

    private int columnOf(Row row, String expected) {
        if (row == null) return -1;
        String normalizedExpected = normalizeHeader(expected);
        for (int column = Math.max(0, row.getFirstCellNum()); column < row.getLastCellNum(); column++) {
            if (normalizeHeader(ExcelValueReader.text(row.getCell(column))).contains(normalizedExpected)) return column;
        }
        return -1;
    }

    private Cell cell(Sheet sheet, int rowIndex, int column) { Row row = sheet.getRow(rowIndex); return cell(row, column); }
    private Cell cell(Row row, int column) { return row == null || column < 0 ? null : row.getCell(column); }
    private String normalizeHeader(String value) { return value.replaceAll("\\s+", "").trim(); }
    private String excelColumnName(int zeroBasedColumn) { StringBuilder value = new StringBuilder(); for (int column = zeroBasedColumn + 1; column > 0; column = (column - 1) / 26) value.insert(0, (char) ('A' + (column - 1) % 26)); return value.toString(); }

    private record HeaderRange(int start, int endExclusive) { }
    private record ColumnMap(int customerPartNumber, int model, int configuration, int version, int color, int lockBody, int unit,
                             int actualQuantity, int sourceAvailableQuantity, List<Integer> lockedColumns,
                             int inTransitQuantity, int supplierName, int remark) { }
    private record MovementColumn(int index, LocalDate date, String direction, String sourceColumn) { }
}
