package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Sql("/customer-contract-schema.sql")
class CustomerCodeGenerationApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void generatesDomesticAndExportCodesFromLastTenTaxpayerCharacters() throws Exception {
        mvc.perform(post("/api/customers").contentType("application/json").content("""
                {"customerType":"DOMESTIC","customerName":"内销客户","taxpayerId":"91350100MABTRQEC91","contracts":[]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerCode").value("A.MABTRQEC91"));

        mvc.perform(post("/api/customers").contentType("application/json").content("""
                {"customerType":"EXPORT","customerName":"外销客户","taxpayerId":"91350100ABCDEF1234","contracts":[]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerCode").value("B.ABCDEF1234"));
    }

    @Test
    void regeneratesCodeWhenCustomerTypeOrTaxpayerIdChanges() throws Exception {
        mvc.perform(put("/api/customers/1").contentType("application/json").content("""
                {"customerType":"EXPORT","customerName":"更新客户","taxpayerId":"91350100ZYXWVUT987","contracts":[],"version":0}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerCode").value("B.ZYXWVUT987"));
        assertThat(jdbc.queryForObject("SELECT customer_code FROM customer WHERE id=1", String.class))
                .isEqualTo("B.ZYXWVUT987");
    }

    @Test
    void rejectsTaxpayerIdShorterThanTenCharacters() throws Exception {
        mvc.perform(post("/api/customers").contentType("application/json").content("""
                {"customerType":"DOMESTIC","customerName":"无效客户","taxpayerId":"123456789","contracts":[]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("纳税人识别号至少需要10位"));
    }
}
