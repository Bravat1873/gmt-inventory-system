package com.internalops.importing;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses only the three approved source-product worksheets, never the inventory worksheet. */
public final class ProductWorkbookExcelParser {
    private static final List<String> SHEETS = List.of("GMT库存产品清单", "贝朗库存产品清单", "STANLEY库存产品清单");
    private static final List<String> TEXT_FIELDS = List.of(
            "sourceProductCode", "productCategory", "materialType", "customerPartNumber", "brand", "series",
            "bodyColor", "lockType", "connectivity", "salesChannel", "operatingEntity", "language", "codeSuffix",
            "model", "materialSpecification", "productConfiguration", "supplierName", "sourceSupplierName",
            "inventoryRemark");
    private static final List<String> CODE_FIELDS = List.of(
            "brand", "series", "bodyColor", "lockType", "connectivity", "salesChannel", "operatingEntity", "language",
            "codeSuffix");
    private static final List<String> PRODUCT_DETAIL_FIELDS = List.of(
            "customerPartNumber", "model", "materialSpecification", "productConfiguration", "supplierName");
    // These are the fixed coding-legend entries in rows 2-8 of both approved product sheets.
    private static final Set<String> LEADING_LEGEND_VALUES = Set.of(
            "BRAVAT（BR）", "STANLEY(SXSEL)", "GMT(G)",
            "宇宙黑YZH", "红古铜HGT", "富贵金FGJ", "摩卡金MKJ", "瀑布银PBY", "星空黑XKH", "宝石蓝BSL");


    public List<ParsedImportRow> parse(Workbook workbook) {
        List<ParsedImportRow> result = new ArrayList<>();
        for (String sheetName : SHEETS) {
            Sheet sheet = workbook.getSheet(sheetName);
            if (sheet == null) continue;
            Map<String, Integer> headers = normalizedHeaders(sheet.getRow(0));
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                if (isCancelledRow(sheet.getRow(index))) continue;
                Map<String, Object> data = readProductRow(sheet.getRow(index), headers);
                if (isBlank(data.get("sourceSupplierName"))) {
                    data.put("sourceSupplierName", data.get("supplierName"));
                }
                if (isLeadingLegend(data)) continue;
                if (!isBusinessRow(data)) continue;
                result.add(new ParsedImportRow(sheetName, index + 1, ImportRowStatus.VALID, data, null));
            }
        }
        return result;
    }

    private Map<String, Integer> normalizedHeaders(Row header) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (header == null) return result;
        for (int column = Math.max(0, header.getFirstCellNum()); column < header.getLastCellNum(); column++) {
            String field = fieldForHeader(ExcelValueReader.text(header.getCell(column)));
            if (field != null) result.putIfAbsent(field, column);
        }
        return result;
    }

    private String fieldForHeader(String value) {
        String header = normalizeHeader(value);
        if (header.startsWith("产品编号")) return "sourceProductCode";
        if (header.contains("产品分类")) return "productCategory";
        if (header.contains("物料类型")) return "materialType";
        if (header.contains("客户料号")) return "customerPartNumber";
        if (header.equals("品牌")) return "brand";
        if (header.equals("系列")) return "series";
        if (header.contains("物料颜色")) return "bodyColor";
        if (header.contains("锁体类型")) return "lockType";
        if (header.contains("联网方式")) return "connectivity";
        if (header.contains("销售渠道")) return "salesChannel";
        if (header.contains("运营主体")) return "operatingEntity";
        if (header.equals("语言")) return "language";
        if (header.contains("编码后缀")) return "codeSuffix";
        if (header.contains("物料规格")) return "materialSpecification";
        if (header.startsWith("型号")) return "model";
        if (header.contains("产品配置")) return "productConfiguration";
        if (header.contains("销售最小起订量")) return "salesMinimumOrderQuantity";
        if (header.contains("供应商含税价")) return "supplierTaxPrice";
        if (header.equals("供应商名称")) return "supplierName";
        if (header.contains("实际库存数量")) return "actualQuantity";
        if (header.contains("已锁定数量")) return "lockedQuantity";
        if (header.contains("在途数量")) return "inTransitQuantity";
        if (header.contains("库存供应商")) return "sourceSupplierName";
        if (header.contains("库存备注")) return "inventoryRemark";
        return null;
    }

    private Map<String, Object> readProductRow(Row row, Map<String, Integer> headers) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String field : TEXT_FIELDS) data.put(field, nullableText(row, headers.get(field)));
        data.put("salesMinimumOrderQuantity", decimalOrDefault(row, headers.get("salesMinimumOrderQuantity"), BigDecimal.ONE));
        data.put("supplierTaxPrice", decimalOrDefault(row, headers.get("supplierTaxPrice"), BigDecimal.ZERO));
        data.put("actualQuantity", integerOrDefault(row, headers.get("actualQuantity"), 0));
        data.put("lockedQuantity", integerOrDefault(row, headers.get("lockedQuantity"), 0));
        data.put("inTransitQuantity", integerOrDefault(row, headers.get("inTransitQuantity"), 0));
        return data;
    }

    private boolean isBusinessRow(Map<String, Object> data) {
        return hasValue(data, PRODUCT_DETAIL_FIELDS) || hasValue(data, CODE_FIELDS);
    }

    private boolean hasValue(Map<String, Object> data, List<String> fields) {
        return fields.stream().map(data::get).anyMatch(this::hasText);
    }

    private boolean isCancelledRow(Row row) {
        if (row == null) return false;
        for (int column = Math.max(0, row.getFirstCellNum()); column < row.getLastCellNum(); column++) {
            String value = ExcelValueReader.text(row.getCell(column));
            if (value.startsWith("取消") || value.contains("更换新料号")) {
                return true;
            }
        }
        return false;
    }


    private boolean isLeadingLegend(Map<String, Object> data) {
        return CODE_FIELDS.stream()
                .map(data::get)
                .filter(this::hasText)
                .map(Object::toString)
                .anyMatch(LEADING_LEGEND_VALUES::contains);
    }
    private boolean hasText(Object value) {
        return value != null && !value.toString().isBlank();
    }

    private boolean isBlank(Object value) {
        return value == null || value.toString().isBlank();
    }

    private BigDecimal decimalOrDefault(Row row, Integer column, BigDecimal defaultValue) {
        BigDecimal value = ExcelValueReader.decimal(cell(row, column));
        return value == null ? defaultValue : value;
    }

    private int integerOrDefault(Row row, Integer column, int defaultValue) {
        String value = ExcelValueReader.text(cell(row, column));
        if (value.isBlank()) return defaultValue;
        return ExcelValueReader.integer(cell(row, column));
    }

    private String nullableText(Row row, Integer column) {
        String value = ExcelValueReader.text(cell(row, column));
        return value.isBlank() ? null : value;
    }

    private Cell cell(Row row, Integer column) {
        return row == null || column == null || column < 0 ? null : row.getCell(column);
    }

    private String normalizeHeader(String value) {
        return value.replace('（', '(').replace('）', ')').replaceAll("[\\s()]+", "");
    }
}
