package com.internalops.importing;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerExcelParserTest {
    @Test
    void parsesCustomerBankFieldsFromTheTemplateLayout() throws Exception {
        try (var workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            String[] headers = {
                    "客户类型", "客户编码", "客户名称", "地址", "业务联系人", "业务联系电话", "订单联系人",
                    "订单联系电话", "财务联系人", "财务联系电话", "发票抬头", "纳税人识别号", "开票地址",
                    "开票电话", "开户银行", "银行账号"
            };
            for (int index = 0; index < headers.length; index++) header.createCell(index).setCellValue(headers[index]);

            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("内销客户");
            row.createCell(1).setCellValue("A.3123123123");
            row.createCell(2).setCellValue("测试");
            row.createCell(11).setCellValue("123123123123123");
            row.createCell(14).setCellValue("中国银行珠海分行");
            row.createCell(15).setCellValue("6222-0001");

            List<ParsedImportRow> rows = new ExcelImportParser().parse(ImportType.CUSTOMER, input(workbook));

            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).data())
                    .containsEntry("customerType", "DOMESTIC")
                    .containsEntry("customerCode", "A.3123123123")
                    .containsEntry("customerName", "测试")
                    .containsEntry("bankName", "中国银行珠海分行")
                    .containsEntry("bankAccount", "6222-0001");
        }
    }

    private ByteArrayInputStream input(XSSFWorkbook workbook) throws Exception {
        var output = new ByteArrayOutputStream();
        workbook.write(output);
        return new ByteArrayInputStream(output.toByteArray());
    }
}
