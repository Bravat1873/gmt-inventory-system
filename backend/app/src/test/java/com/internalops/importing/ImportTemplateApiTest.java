package com.internalops.importing;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class ImportTemplateApiTest {
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Test
    void downloadsTheSalesOrderImportTemplateAsAValidWorkbook() throws Exception {
        var response = mvc.perform(get("/api/imports/templates/ORDER.xlsx"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX_MEDIA_TYPE))
                .andReturn().getResponse();

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("%E9%94%80%E5%94%AE%E8%AE%A2%E5%8D%95%E6%89%B9%E9%87%8F%E5%AF%BC%E5%85%A5%E6%A8%A1%E6%9D%BF.xlsx");
        assertThat(response.getContentAsByteArray()).isNotEmpty();

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("订单导入");
            assertThat(workbook.getSheetName(1)).isEqualTo("填写说明");

            var orderSheet = workbook.getSheetAt(0);
            var expectedHeaders = List.of(
                    "外部订单号", "客户编码", "订单日期", "订单类型", "订单状态", "销售员", "产品编号",
                    "客户料号", "订单数量", "含税单价", "业务联系人", "业务联系电话", "订单联系人",
                    "订单联系电话", "财务联系人", "财务联系电话", "收货地址", "收货联系人",
                    "收货联系电话", "发货方式", "订单备注");
            assertThat(orderSheet.getRow(0).getPhysicalNumberOfCells()).isEqualTo(expectedHeaders.size());
            for (int column = 0; column < expectedHeaders.size(); column++) {
                assertThat(orderSheet.getRow(0).getCell(column).getStringCellValue())
                        .isEqualTo(expectedHeaders.get(column));
            }

            assertThat(orderSheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("DEMO-ORDER-001");
            assertThat(orderSheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("DEMO-ORDER-001");
            assertThat(orderSheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("DEMO-ORDER-002");
            assertThat(orderSheet.getRow(1).getCell(4).getStringCellValue()).isEqualTo("正式订单");
            assertThat(orderSheet.getRow(3).getCell(4).getStringCellValue()).isEqualTo("草稿");
            assertThat(orderSheet.getPaneInformation()).isNotNull();
            assertThat(orderSheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(orderSheet.getTables()).hasSize(1);
            assertThat(orderSheet.getTables().get(0).getCTTable().isSetAutoFilter()).isTrue();

            assertThat(orderSheet.getDataValidations()).hasSize(2);
            assertThat(orderSheet.getDataValidations())
                    .extracting(validation -> validation.getValidationConstraint().getFormula1())
                    .containsExactlyInAnyOrder("\"工程订单,零售订单,前置订单\"", "\"正式订单,草稿\"");
            assertThat(orderSheet.getRow(1).getCell(0).getCellStyle().getDataFormatString()).isEqualTo("@");
            assertThat(orderSheet.getRow(1).getCell(2).getCellStyle().getDataFormatString()).isEqualTo("yyyy-mm-dd");
            assertThat(orderSheet.getRow(1).getCell(8).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(orderSheet.getRow(1).getCell(9).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
            assertThat(orderSheet.getLastRowNum()).isLessThanOrEqualTo(100);
            assertThat(new SalesOrderExcelParser().parse(workbook)).hasSize(3);
        }
    }
}
