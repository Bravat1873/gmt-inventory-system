package com.internalops.productcode;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts={"/auth-schema.sql","/product-code-command-schema.sql"})
@DirtiesContext(classMode= DirtiesContext.ClassMode.AFTER_CLASS)
class ProductCodeCommandApiTest {
    @Autowired MockMvc mvc;

    @Test
    void createsFormalCodesAndAllowsDuplicateCustomerPartNumbers() throws Exception {
        Cookie session=login();
        mvc.perform(post("/api/workbench/product").cookie(session).contentType("application/json").content(body(2)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.productCode").value("BR_P90HGT60WPZE"))
                .andExpect(jsonPath("$.data.customerPartNumber").value("客户-重复"));
        mvc.perform(post("/api/workbench/product").cookie(session).contentType("application/json").content(body(9)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.productCode").value("BR_P50HGT60WPZE"))
                .andExpect(jsonPath("$.data.customerPartNumber").value("客户-重复"));
    }

    @Test
    void createsEntryDoorCode() throws Exception {
        Cookie session = login();
        String json = "{\"customerPartNumber\":\"门客户号\",\"productName\":\"入户门\",\"productType\":\"ENTRY_DOOR\",\"materialType\":\"FINISHED_PRODUCT\"," +
                "\"brandRuleId\":1,\"doorModelRuleId\":11,\"securityGradeRuleId\":12,\"baseMaterialRuleId\":13," +
                "\"thicknessRuleId\":14,\"finishColorRuleId\":15}";
        mvc.perform(post("/api/workbench/product").cookie(session).contentType("application/json").content(json))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.productCode").value("BR_MJ3080A"))
                .andExpect(jsonPath("$.data.customerPartNumber").value("门客户号"));
    }
    @Test
    void rejectsIncompleteNewProductAndPreservesHistoricalCodeOnIncompleteEdit() throws Exception {
        Cookie session=login();
        mvc.perform(post("/api/workbench/product").cookie(session).contentType("application/json").content("{\"productName\":\"不完整产品\",\"productType\":\"SMART_LOCK\",\"materialType\":\"FINISHED_PRODUCT\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("请补齐产品编码信息"));
        mvc.perform(put("/api/workbench/product/100").cookie(session).contentType("application/json")
                        .content("{\"productName\":\"历史产品更新\",\"customerPartNumber\":null,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.productCode").value("OLD_000100"));
    }

    private String body(long series) {
        return "{\"productCode\":\"HACKED\",\"customerPartNumber\":\"客户-重复\",\"productName\":\"正式产品\",\"productType\":\"SMART_LOCK\",\"materialType\":\"FINISHED_PRODUCT\","+
                "\"brandRuleId\":1,\"seriesRuleId\":"+series+",\"bodyColorRuleId\":3,\"lockTypeRuleId\":4,"+
                "\"connectivityRuleId\":5,\"salesChannelRuleId\":6,\"operatingEntityRuleId\":7,\"languageRuleId\":8}";
    }
    private Cookie login() throws Exception {
        return mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"username\":\"admin\",\"password\":\"123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("OPS_SESSION");
    }
}
