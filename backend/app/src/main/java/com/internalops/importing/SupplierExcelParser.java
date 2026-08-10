package com.internalops.importing;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SupplierExcelParser {
    private static final Map<String, String> HEADER_FIELDS = Map.ofEntries(
            Map.entry("厂商分类", "manufacturerCategory"),
            Map.entry("厂商类型", "manufacturerType"),
            Map.entry("供应商地点", "supplierLocation"),
            Map.entry("产品属性", "productAttribute"),
            Map.entry("简称", "shortName"),
            Map.entry("供应商名称", "supplierName"),
            Map.entry("联系人", "contactName"),
            Map.entry("职称", "contactTitle"),
            Map.entry("联系方式", "phone"),
            Map.entry("供应商地址", "address"),
            Map.entry("币种", "currency"),
            Map.entry("税务登记号", "taxRegistrationNo"),
            Map.entry("开户地址", "bankAddress"),
            Map.entry("开户账户", "bankAccount"));

    public List<ParsedImportRow> parse(InputStream input) {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            return parse(workbook);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Excel 文件无法读取：" + exception.getMessage(), exception);
        }
    }

    List<ParsedImportRow> parse(Workbook workbook) {
        List<ParsedImportRow> rows = new ArrayList<>();
        Map<String, Integer> firstNames = new LinkedHashMap<>();
        boolean foundHeader = false;
        for (Sheet sheet : workbook) {
            Header header = findHeader(sheet);
            if (header == null) continue;
            foundHeader = true;
            for (int rowIndex = header.rowIndex() + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                Row source = sheet.getRow(rowIndex);
                Map<String, Object> data = readData(source, header.columns());
                if (data.values().stream().allMatch(value -> value.toString().isBlank())) continue;
                String name = data.get("supplierName").toString();
                if (name.isBlank()) {
                    rows.add(error(sheet, rowIndex, data, "供应商名称不能为空"));
                    continue;
                }
                Integer firstRow = firstNames.putIfAbsent(normalizeName(name), rowIndex + 1);
                if (firstRow != null) {
                    rows.add(error(sheet, rowIndex, data, "供应商名称重复，与第 " + firstRow + " 行相同"));
                    continue;
                }
                rows.add(new ParsedImportRow(sheet.getSheetName(), rowIndex + 1,
                        ImportRowStatus.VALID, data, null));
            }
        }
        if (!foundHeader) throw new IllegalArgumentException("缺少必填列：供应商名称");
        return rows;
    }

    private Header findHeader(Sheet sheet) {
        for (int rowIndex = sheet.getFirstRowNum(); rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;
            Map<Integer, String> columns = new LinkedHashMap<>();
            boolean hasSupplierName = false;
            for (int column = row.getFirstCellNum(); column >= 0 && column < row.getLastCellNum(); column++) {
                String field = HEADER_FIELDS.get(ExcelValueReader.text(row.getCell(column)));
                if (field == null) continue;
                columns.put(column, field);
                if ("supplierName".equals(field)) hasSupplierName = true;
            }
            if (hasSupplierName) return new Header(rowIndex, columns);
        }
        return null;
    }

    private Map<String, Object> readData(Row row, Map<Integer, String> columns) {
        Map<String, Object> data = new LinkedHashMap<>();
        HEADER_FIELDS.values().forEach(field -> data.put(field, ""));
        columns.forEach((column, field) -> data.put(field,
                row == null ? "" : ExcelValueReader.text(row.getCell(column))));
        return data;
    }

    private ParsedImportRow error(Sheet sheet, int rowIndex, Map<String, Object> data, String message) {
        return new ParsedImportRow(sheet.getSheetName(), rowIndex + 1, ImportRowStatus.ERROR, data, message);
    }

    private String normalizeName(String value) {
        return value.trim().replace('(', '（').replace(')', '）')
                .replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private record Header(int rowIndex, Map<Integer, String> columns) { }
}
