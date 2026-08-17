package com.internalops.importing;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelImportParserTest {
    private final ExcelImportParser parser = new ExcelImportParser();

    @Test
    void parsesCustomerNamesFromFirstColumn() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue(" 测试客户 ");
            List<ParsedImportRow> rows = parser.parse(ImportType.CUSTOMER, input(workbook));
            assertEquals(1, rows.size());
            assertEquals("测试客户", rows.get(0).data().get("customerName"));
        }
    }

    @Test
    void parsesCostHeadersAndDecimal() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(1);
            String[] names = {"序号", "物料编号", "型号", "颜色", "锁体", "物料规格", "成本单价（含税）", "转厂价格", "差异：转厂价-原成本", "备注"};
            for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
            var row = sheet.createRow(2);
            row.createCell(0).setCellValue(1);
            row.createCell(1).setCellValue("SKU-1");
            row.createCell(2).setCellValue("P90");
            row.createCell(6).setCellValue(12.34);
            row.createCell(7).setCellValue(15.50);
            row.createCell(9).setCellValue("测试备注");
            ParsedImportRow parsed = parser.parse(ImportType.COST, input(workbook)).get(0);
            assertEquals("SKU-1", parsed.data().get("skuCode"));
            assertEquals(0, new BigDecimal(parsed.data().get("cost").toString()).compareTo(new BigDecimal("12.34")));
            assertEquals(0, new BigDecimal(parsed.data().get("factoryPrice").toString()).compareTo(new BigDecimal("15.5")));
            assertEquals("测试备注", parsed.data().get("remark"));
        }
    }

    @Test
    void parsesCostWorkbookWithCustomerMaterialNumberHeader() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("成本明细");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("客户料号");
            header.createCell(1).setCellValue("成本单价（含税）");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("CUSTOMER-SKU-1");
            row.createCell(1).setCellValue(88);

            ParsedImportRow parsed = parser.parse(ImportType.COST, input(workbook)).get(0);

            assertEquals("CUSTOMER-SKU-1", parsed.data().get("skuCode"));
            assertEquals(ImportRowStatus.VALID, parsed.status());
        }
    }

    @Test
    void continuesToParseCostWorkbookWithLegacyMaterialNumberHeader() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("历史成本明细");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("物料编号");
            header.createCell(1).setCellValue("成本单价（含税）");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("LEGACY-SKU-1");
            row.createCell(1).setCellValue(99);

            ParsedImportRow parsed = parser.parse(ImportType.COST, input(workbook)).get(0);

            assertEquals("LEGACY-SKU-1", parsed.data().get("skuCode"));
            assertEquals(ImportRowStatus.VALID, parsed.status());
        }
    }

    @Test
    void findsCostHeaderInAnySheetAndDoesNotRequireSerialNumber() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("成本明细");
            sheet.createRow(0).createCell(0).setCellValue("产品成本单价汇总");
            var header = sheet.createRow(3);
            String[] names = {"序号", "物料编号", "型号", "颜色", "锁体", "物料规格", "成本单价（含税）", "转厂价格", "差异：转厂价-原成本", "备注"};
            for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);
            var row = sheet.createRow(4);
            row.createCell(2).setCellValue("P90");
            row.createCell(3).setCellValue("宇宙黑");
            row.createCell(6).setCellValue(465);

            List<ParsedImportRow> rows = parser.parse(ImportType.COST, input(workbook));

            assertEquals(1, rows.size());
            assertEquals("P90", rows.get(0).data().get("model"));
            assertEquals(ImportRowStatus.VALID, rows.get(0).status());
        }
    }

    @Test
    void parsesInventoryAndClassifiesGroupAndMissingSkuRows() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("爱迪生");
            var header = sheet.createRow(1);
            header.createCell(1).setCellValue("物料编号\nSKU");
            header.createCell(8).setCellValue("实际\n库存\n数量");
            header.createCell(10).setCellValue("已锁定数量");
            header.createCell(15).setCellValue("在途\n数量");
            sheet.createRow(0).createCell(20).setCellValue(2026);
            sheet.createRow(2).createCell(20).setCellValue("0622");
            sheet.createRow(3).createCell(20).setCellValue("出库");

            var valid = sheet.createRow(4);
            valid.createCell(0).setCellValue(1);
            valid.createCell(1).setCellValue("SKU-1");
            valid.createCell(2).setCellValue("P90");
            valid.createCell(8).setCellValue(10);
            valid.createCell(10).setCellValue(1);
            valid.createCell(11).setCellValue(2);
            valid.createCell(15).setCellValue(5);
            valid.createCell(16).setCellValue("爱迪生");
            valid.createCell(17).setCellValue("出货需升级");
            valid.createCell(20).setCellValue(7);

            var group = sheet.createRow(5);
            group.createCell(0).setCellValue("STANLEY");
            group.createCell(8).setCellValue(100);

            var missing = sheet.createRow(6);
            missing.createCell(0).setCellValue(100);
            missing.createCell(3).setCellValue("工衣");
            missing.createCell(8).setCellValue(6);

            List<ParsedImportRow> rows = parser.parse(ImportType.INVENTORY, input(workbook));
            assertEquals(3, rows.size());
            assertEquals(ImportRowStatus.VALID, rows.get(0).status());
            assertEquals(3, rows.get(0).data().get("lockedQuantity"));
            assertEquals("爱迪生", rows.get(0).data().get("supplierName"));
            assertEquals("出货需升级", rows.get(0).data().get("remark"));
            var movements = (List<?>) rows.get(0).data().get("movements");
            assertEquals(1, movements.size());
            var movement = (java.util.Map<?, ?>) movements.get(0);
            assertEquals("2026-06-22", movement.get("date"));
            assertEquals("出库", movement.get("direction"));
            assertEquals(7, movement.get("quantity"));
            assertEquals("U:0622出库", movement.get("sourceColumn"));
            assertEquals(ImportRowStatus.IGNORED, rows.get(1).status());
            assertEquals(ImportRowStatus.ERROR, rows.get(2).status());
            assertEquals("实际库存行缺少客户料号 SKU；请根据来源行标识补齐 SKU 后再导入", rows.get(2).errorMessage());
        }
    }

    @Test
    void parsesInventoryWorkbookWithCustomerMaterialNumberSkuHeaders() throws Exception {
        for (String skuHeader : List.of("客户料号 SKU", "客户料号SKU")) {
            try (var workbook = new XSSFWorkbook()) {
                var sheet = workbook.createSheet("库存");
                var header = sheet.createRow(0);
                header.createCell(0).setCellValue(skuHeader);
                header.createCell(1).setCellValue("实际库存数量");
                header.createCell(2).setCellValue("已锁定数量");
                header.createCell(7).setCellValue("在途数量");
                var row = sheet.createRow(1);
                row.createCell(0).setCellValue("CUSTOMER-SKU-1");
                row.createCell(1).setCellValue(3);
                row.createCell(7).setCellValue(0);

                ParsedImportRow parsed = parser.parse(ImportType.INVENTORY, input(workbook)).get(0);

                assertEquals("CUSTOMER-SKU-1", parsed.data().get("skuCode"), skuHeader);
                assertEquals(ImportRowStatus.VALID, parsed.status(), skuHeader);
            }
        }
    }

    @Test
    void continuesToParseInventoryWorkbookWithLegacyMaterialNumberSkuHeader() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("历史库存");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("物料编号 SKU");
            header.createCell(1).setCellValue("实际库存数量");
            header.createCell(2).setCellValue("已锁定数量");
            header.createCell(7).setCellValue("在途数量");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("LEGACY-SKU-1");
            row.createCell(1).setCellValue(3);
            row.createCell(7).setCellValue(0);

            ParsedImportRow parsed = parser.parse(ImportType.INVENTORY, input(workbook)).get(0);

            assertEquals("LEGACY-SKU-1", parsed.data().get("skuCode"));
            assertEquals(ImportRowStatus.VALID, parsed.status());
        }
    }

    private ByteArrayInputStream input(XSSFWorkbook workbook) throws Exception {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return new ByteArrayInputStream(output.toByteArray());
    }
}
