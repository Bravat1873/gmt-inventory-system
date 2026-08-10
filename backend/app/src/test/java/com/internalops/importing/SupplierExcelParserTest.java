package com.internalops.importing;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierExcelParserTest {
    private static final String[] HEADERS = {
            "厂商分类", "厂商类型", "供应商地点", "产品属性", "简称", "供应商名称", "联系人",
            "职称", "联系方式", "供应商地址", "币种", "税务登记号", "开户地址", "开户账户"
    };

    @Test
    void discoversNonFirstHeaderAndMapsAllSupplierFieldsFromXlsx() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("供应商资料");
            sheet.createRow(0).createCell(0).setCellValue("供应商联系方式");
            addHeaders(sheet, 2);
            var row = sheet.createRow(3);
            String[] values = {
                    "一级", "制造商", "深圳", "智能锁", "星云", "星云科技", "张三", "经理",
                    "0755 1234 5678", "深圳市南山区", "CNY", "91440300 ABC", "深圳科技园", "0012 3456 7890"
            };
            for (int index = 0; index < values.length; index++) row.createCell(index).setCellValue(values[index]);

            ParsedImportRow parsed = parse(workbook).get(0);

            assertThat(parsed.status()).isEqualTo(ImportRowStatus.VALID);
            assertThat(parsed.sheetName()).isEqualTo("供应商资料");
            assertThat(parsed.rowNumber()).isEqualTo(4);
            assertThat(parsed.data()).containsAllEntriesOf(java.util.Map.ofEntries(
                    java.util.Map.entry("manufacturerCategory", "一级"),
                    java.util.Map.entry("manufacturerType", "制造商"),
                    java.util.Map.entry("supplierLocation", "深圳"),
                    java.util.Map.entry("productAttribute", "智能锁"),
                    java.util.Map.entry("shortName", "星云"),
                    java.util.Map.entry("supplierName", "星云科技"),
                    java.util.Map.entry("contactName", "张三"),
                    java.util.Map.entry("contactTitle", "经理"),
                    java.util.Map.entry("phone", "0755 1234 5678"),
                    java.util.Map.entry("address", "深圳市南山区"),
                    java.util.Map.entry("currency", "CNY"),
                    java.util.Map.entry("taxRegistrationNo", "91440300 ABC"),
                    java.util.Map.entry("bankAddress", "深圳科技园"),
                    java.util.Map.entry("bankAccount", "0012 3456 7890")));
        }
    }

    @Test
    void readsDisplayedPhoneTaxAndBankTextFromXls() throws Exception {
        try (Workbook workbook = new HSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            sheet.createRow(0).createCell(0).setCellValue("供应商通讯录");
            addHeaders(sheet, 1);
            var row = sheet.createRow(2);
            row.createCell(5).setCellValue("数值文本供应商");
            setDisplayedNumber(workbook, row.createCell(8), 7551234, "00000000000");
            setDisplayedNumber(workbook, row.createCell(11), 123456, "000000000000000000");
            setDisplayedNumber(workbook, row.createCell(13), 987654, "0000000000000000");

            ParsedImportRow parsed = parse(workbook).get(0);

            assertThat(parsed.data())
                    .containsEntry("phone", "00007551234")
                    .containsEntry("taxRegistrationNo", "000000000000123456")
                    .containsEntry("bankAccount", "0000000000987654");
        }
    }

    @Test
    void reportsMissingAndDuplicateSupplierNames() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            addHeaders(sheet, 0);
            var missing = sheet.createRow(1);
            missing.createCell(6).setCellValue("只有联系人");
            sheet.createRow(2).createCell(5).setCellValue("星云 (深圳)");
            sheet.createRow(3).createCell(5).setCellValue(" 星云（深圳） ");

            List<ParsedImportRow> rows = parse(workbook);

            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).status()).isEqualTo(ImportRowStatus.ERROR);
            assertThat(rows.get(0).errorMessage()).isEqualTo("供应商名称不能为空");
            assertThat(rows.get(1).status()).isEqualTo(ImportRowStatus.VALID);
            assertThat(rows.get(2).status()).isEqualTo(ImportRowStatus.ERROR);
            assertThat(rows.get(2).errorMessage()).isEqualTo("供应商名称重复，与第 3 行相同");
        }
    }

    private void addHeaders(org.apache.poi.ss.usermodel.Sheet sheet, int rowIndex) {
        var header = sheet.createRow(rowIndex);
        for (int index = 0; index < HEADERS.length; index++) header.createCell(index).setCellValue(HEADERS[index]);
    }

    private void setDisplayedNumber(Workbook workbook, org.apache.poi.ss.usermodel.Cell cell, double value, String format) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        cell.setCellStyle(style);
        cell.setCellValue(value);
    }

    private List<ParsedImportRow> parse(Workbook workbook) throws Exception {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return new SupplierExcelParser().parse(new ByteArrayInputStream(output.toByteArray()));
    }
}
