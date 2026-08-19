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
class ProductImportTemplateApiTest {
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    MockMvc mvc;

    @Test
    void downloadsTheProductImportTemplateWithRulesSheet() throws Exception {
        var response = mvc.perform(get("/api/imports/templates/PRODUCT.xlsx"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(XLSX_MEDIA_TYPE))
                .andReturn().getResponse();

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("PRODUCT.xlsx");

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(workbook.getSheetName(0)).isEqualTo("GMT库存产品清单");
            assertThat(workbook.getSheetName(1)).isEqualTo("贝朗库存产品清单");
            assertThat(workbook.getSheetName(2)).isEqualTo("STANLEY库存产品清单");
            assertThat(workbook.getSheetName(3)).isEqualTo("规则说明");

            var sheet = workbook.getSheetAt(0);
            var expectedHeaders = List.of(
                    "产品分类", "物料类型", "客户料号", "产品编号（不用写）", "品牌", "系列", "物料颜色",
                    "锁体类型", "联网方式", "销售渠道", "运营主体", "语言", "编码后缀",
                    "型号（系列号+第几代）", "物料规格（不用写，会自动生成）", "产品配置",
                    "销售最小起订量", "供应商名称", "供应商含税价",
                    "实际库存数量", "已锁定数量", "在途数量", "库存供应商", "库存备注");
            assertThat(sheet.getRow(0).getPhysicalNumberOfCells()).isEqualTo(expectedHeaders.size());
            for (int column = 0; column < expectedHeaders.size(); column++) {
                assertThat(sheet.getRow(0).getCell(column).getStringCellValue())
                        .isEqualTo(expectedHeaders.get(column));
            }

            var rules = workbook.getSheetAt(3);
            assertThat(rules.getRow(1).getCell(0).getStringCellValue())
                    .contains("产品编号 = 品牌 + 系列 + 物料颜色 + 锁体类型 + 联网方式 + 销售渠道 + 运营主体 + 语言 + 编码后缀");
            assertThat(rules.getRow(2).getCell(0).getStringCellValue())
                    .contains("物料规格 = 品牌 + 型号 + 物料颜色 + 锁体类型 + 语言");
            assertThat(rules.getRow(3).getCell(0).getStringCellValue())
                    .contains("产品编号、物料规格不用填写");
            assertThat(rules.getRow(6).getCell(0).getStringCellValue())
                    .contains("实际库存数量").contains("已锁定数量").contains("在途数量").contains("库存供应商").contains("库存备注");
        }
    }
}
