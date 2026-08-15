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
                    .containsEntry("sourceProductCode", "")
                    .containsEntry("model", "D51-GEN2")
                    .containsEntry("materialSpecification", "7068锁体")
                    .containsEntry("salesMinimumOrderQuantity", java.math.BigDecimal.ONE)
                    .containsEntry("supplierTaxPrice", java.math.BigDecimal.ZERO));
        }
    }

    private void addHeadersAndBlankCodeProduct(org.apache.poi.ss.usermodel.Sheet sheet) {
        var header = sheet.createRow(0);
        String[] headers = {"产品编号（不用写）", "客户料号", "品牌", "型号\n（系列号+第几代）",
                "物料规格\n（不用写，会自动生成）", "产品配置", "销售最小起订量", "供应商含税价"};
        for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);

        var product = sheet.createRow(1);
        product.createCell(1).setCellValue("D1213K-D51");
        product.createCell(2).setCellValue("BR");
        product.createCell(3).setCellValue("D51-GEN2");
        product.createCell(4).setCellValue("7068锁体");
        product.createCell(5).setCellValue("一字锁");
    }
}
