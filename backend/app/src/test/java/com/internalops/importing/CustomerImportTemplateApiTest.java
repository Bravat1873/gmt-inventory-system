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
class CustomerImportTemplateApiTest {
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Test
    void downloadsTheCustomerImportTemplateWithBankFields() throws Exception {
        var response = mvc.perform(get("/api/imports/templates/CUSTOMER.xlsx"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX_MEDIA_TYPE))
                .andReturn().getResponse();

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("CUSTOMER.xlsx");
        assertThat(response.getContentAsByteArray()).isNotEmpty();

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(1);
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("Sheet1");

            var expectedHeaders = List.of(
                    "客户类型", "客户编码", "客户名称", "地址", "业务联系人", "业务联系电话", "订单联系人",
                    "订单联系电话", "财务联系人", "财务联系电话", "发票抬头", "纳税人识别号", "开票地址",
                    "开票电话", "开户银行", "银行账号");
            assertThat(sheet.getRow(0).getPhysicalNumberOfCells()).isEqualTo(expectedHeaders.size());
            for (int column = 0; column < expectedHeaders.size(); column++) {
                assertThat(sheet.getRow(0).getCell(column).getStringCellValue())
                        .isEqualTo(expectedHeaders.get(column));
            }
        }
    }
}
