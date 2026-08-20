package com.internalops.importing;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SalesOrderExcelParserTest {
    @Test
    void acceptsLegacyOrderExportHeadersAndFillsSafeDefaults() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet1");
            String[] headers = {"客户名称", "外部订单号", "订单日期", "订单类型", "产品编号", "客户料号", "数量", "含税单价", "收货地址", "联系人", "联系电话", "发货方式", "订单备注"};
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("客户一");
            row.createCell(2).setCellValue("2026/01/01");
            row.createCell(3).setCellValue("工程订单");
            row.createCell(4).setCellValue("P-001");
            row.createCell(5).setCellValue("CM-001");
            row.createCell(6).setCellValue(2);
            row.createCell(7).setCellValue(10.5);
            workbook.write(output);

            ParsedImportRow parsed = new ExcelImportParser().parse(ImportType.ORDER,
                    new ByteArrayInputStream(output.toByteArray())).get(0);

            assertThat(parsed.data()).containsEntry("customerName", "客户一")
                    .containsEntry("customerCode", "")
                    .containsEntry("quantity", "2")
                    .containsEntry("orderStatus", "正式订单")
                    .containsEntry("salesperson", "Excel导入");
        }
    }

    @Test
    void acceptsTheFirstNonEmptySheetWhenWorkbookUsesSheet1AndReadsExcelDates() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet1");
            String[] headers = {"客户编码", "订单日期", "订单类型", "订单状态", "销售员", "客户料号", "产品编号", "订单数量", "含税单价"};
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("C-001");
            var date = row.createCell(1);
            date.setCellValue(java.sql.Date.valueOf(LocalDate.of(2026, 1, 1)));
            date.setCellStyle(workbook.createCellStyle());
            date.getCellStyle().setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            row.createCell(2).setCellValue("工程订单");
            row.createCell(3).setCellValue("正式订单");
            row.createCell(4).setCellValue("张三");
            row.createCell(5).setCellValue("CM-001");
            row.createCell(6).setCellValue("P-001");
            row.createCell(7).setCellValue(2);
            row.createCell(8).setCellValue(10.5);
            workbook.write(output);

            List<ParsedImportRow> rows = new ExcelImportParser().parse(ImportType.ORDER,
                    new ByteArrayInputStream(output.toByteArray()));

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).data()).containsEntry("orderDate", "2026-01-01")
                    .containsEntry("externalOrderNo", "");
        }
    }

    @Test
    void parsesThreeOrderDetailRowsUsingTheOrderImportHeaders() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("订单导入");
            String[] headers = {"外部订单号", "客户编码", "订单日期", "订单类型", "订单状态", "销售员", "客户料号", "产品编号", "订单数量", "含税单价", "业务联系人", "业务联系电话", "订单联系人", "订单联系电话", "财务联系人", "财务联系电话", "收货地址", "收货联系人", "收货联系电话", "发货方式", "订单备注"};
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);
            writeRow(sheet.createRow(1), "EXT-001", "C-001", "2026-08-17", "工程订单", "正式订单", "张三", "CM-01", "P-001", 2, 19.8);
            writeRow(sheet.createRow(2), "EXT-001", "C-001", "2026-08-17", "工程订单", "正式订单", "张三", "CM-02", "P-002", 3, 20);
            writeRow(sheet.createRow(3), "EXT-002", "C-002", "2026-08-18", "零售订单", "草稿", "李四", "CM-03", "P-003", 1, 0);
            workbook.write(output);

            List<ParsedImportRow> rows = new ExcelImportParser().parse(ImportType.ORDER,
                    new ByteArrayInputStream(output.toByteArray()));

            assertThat(rows).hasSize(3);
            assertThat(rows.get(0).sheetName()).isEqualTo("订单导入");
            assertThat(rows.get(0).rowNumber()).isEqualTo(2);
            assertThat(rows.get(0).data()).containsEntry("externalOrderNo", "EXT-001")
                    .containsEntry("customerCode", "C-001")
                    .containsEntry("orderDate", "2026-08-17")
                    .containsEntry("productCode", "P-001")
                    .containsEntry("quantity", "2")
                    .containsEntry("salePrice", "19.8")
                    .containsEntry("deliveryAddress", "上海市")
                    .containsEntry("remark", "备注");
            assertThat(rows.get(2).data()).containsEntry("orderDate", "2026-08-18")
                    .containsEntry("orderStatus", "草稿")
                    .containsEntry("salePrice", "0.0");
        }
    }

    private void writeRow(org.apache.poi.ss.usermodel.Row row, String externalOrderNo, String customerCode,
                          String date, String type, String status, String salesperson, String customerPartNumber,
                          String productCode, int quantity, double salePrice) {
        String[] values = {externalOrderNo, customerCode, date, type, status, salesperson, customerPartNumber,
                productCode, String.valueOf(quantity), String.valueOf(salePrice), "业务", "13800000000", "订单", "13800000001",
                "财务", "13800000002", "上海市", "收货人", "13800000003", "快递", "备注"};
        for (int index = 0; index < values.length; index++) row.createCell(index).setCellValue(values[index]);
    }
}
