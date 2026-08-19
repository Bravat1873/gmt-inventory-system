package com.internalops.importing;

import org.junit.jupiter.api.Test;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductWorkbookExcelParserTest {

    @Test
    void parsesOnlyTheTwoProductSheetsAndAllowsBlankSourceProductCode() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/import/product-workbook-two-sheets.xlsx")) {
            List<ParsedImportRow> rows = new ExcelImportParser().parse(ImportType.PRODUCT, input);

            assertThat(rows).hasSize(17);
            assertThat(rows).extracting(ParsedImportRow::sheetName)
                    .containsOnly("GMT库存产品清单", "贝朗库存产品清单");
            assertThat(rows).allSatisfy(row -> assertThat(row.rowNumber()).isGreaterThanOrEqualTo(9));
        }
    }

    @Test
    void acceptsBlankSourceProductCodeAndNormalizesSplitHeaders() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            addHeadersAndBlankCodeProduct(workbook.createSheet("GMT库存产品清单"));
            addHeadersAndBlankCodeProduct(workbook.createSheet("贝朗库存产品清单"));

            var output = new ByteArrayOutputStream();
            workbook.write(output);
            List<ParsedImportRow> rows = new ExcelImportParser().parse(
                    ImportType.PRODUCT, new java.io.ByteArrayInputStream(output.toByteArray()));

            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(row -> assertThat(row.data())
                    .containsEntry("sourceProductCode", null)
                    .containsEntry("bodyColor", null)
                    .containsEntry("brand", "BR")
                    .containsEntry("model", "D51-GEN2")
                    .containsEntry("materialSpecification", "7068锁体")
                    .containsEntry("salesMinimumOrderQuantity", java.math.BigDecimal.ONE)
                    .containsEntry("supplierTaxPrice", java.math.BigDecimal.ZERO)
                    .containsEntry("actualQuantity", 12)
                    .containsEntry("lockedQuantity", 3)
                    .containsEntry("inTransitQuantity", 4)
                    .containsEntry("sourceSupplierName", "测试供应商")
                    .containsEntry("inventoryRemark", "现货"));
        }
    }

    private void addHeadersAndBlankCodeProduct(org.apache.poi.ss.usermodel.Sheet sheet) {
        var header = sheet.createRow(0);
        String[] headers = {"产品编号（不用写）", "客户料号", "品牌", "型号\n（系列号+第几代）",
                "物料规格\n（不用写，会自动生成）", "产品配置", "销售最小起订量", "供应商含税价"};
        String[] inventoryHeaders = {"实际库存数量", "已锁定数量", "在途数量", "库存供应商", "库存备注"};
        for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);
        for (int index = 0; index < inventoryHeaders.length; index++) header.createCell(headers.length + index).setCellValue(inventoryHeaders[index]);

        var product = sheet.createRow(1);
        product.createCell(1).setCellValue("D1213K-D51");
        product.createCell(2).setCellValue("BR");
        product.createCell(3).setCellValue("D51-GEN2");
        product.createCell(4).setCellValue("7068锁体");
        product.createCell(5).setCellValue("一字锁");
        product.createCell(8).setCellValue(12);
        product.createCell(9).setCellValue(3);
        product.createCell(10).setCellValue(4);
        product.createCell(11).setCellValue("测试供应商");
        product.createCell(12).setCellValue("现货");
    }

    @Test
    void skipsLeadingLegendRowsButImportsLaterRowsWithOnlyCodeElements() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            addLegendAndCodeOnlyRows(workbook.createSheet("GMT库存产品清单"));
            addLegendAndCodeOnlyRows(workbook.createSheet("贝朗库存产品清单"));
            var output = new ByteArrayOutputStream();
            workbook.write(output);

            List<ParsedImportRow> rows = new ExcelImportParser().parse(
                    ImportType.PRODUCT, new java.io.ByteArrayInputStream(output.toByteArray()));

            assertThat(rows).hasSize(4);
            assertThat(rows).extracting(ParsedImportRow::rowNumber).containsExactlyInAnyOrder(3, 4, 3, 4);
        }
    }

    private void addLegendAndCodeOnlyRows(org.apache.poi.ss.usermodel.Sheet sheet) {
        var header = sheet.createRow(0);
        header.createCell(0).setCellValue("品牌");
        header.createCell(1).setCellValue("客户料号");
        sheet.createRow(1).createCell(0).setCellValue("BRAVAT（BR）");
        sheet.createRow(2).createCell(1).setCellValue("DETAIL");
        sheet.createRow(3).createCell(0).setCellValue("BR");
    }

    @Test
    void importsFirstCodeOnlyProductButSkipsExplicitLeadingLegend() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            addLeadingLegendAndFirstCodeOnlyProduct(workbook.createSheet("GMT库存产品清单"));
            addLeadingLegendAndFirstCodeOnlyProduct(workbook.createSheet("贝朗库存产品清单"));
            var output = new ByteArrayOutputStream();
            workbook.write(output);

            List<ParsedImportRow> rows = new ExcelImportParser().parse(
                    ImportType.PRODUCT, new java.io.ByteArrayInputStream(output.toByteArray()));

            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(ParsedImportRow::rowNumber).containsOnly(3);
            assertThat(rows).allSatisfy(row -> assertThat(row.data()).containsEntry("brand", "BR"));
        }
    }

    private void addLeadingLegendAndFirstCodeOnlyProduct(org.apache.poi.ss.usermodel.Sheet sheet) {
        var header = sheet.createRow(0);
        header.createCell(0).setCellValue("品牌");

        // This exact text is the brand coding legend in both approved source sheets.
        sheet.createRow(1).createCell(0).setCellValue("BRAVAT（BR）");
        sheet.createRow(2).createCell(0).setCellValue("BR");
    }
}
