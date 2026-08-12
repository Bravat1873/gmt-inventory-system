package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Sql("/customer-contract-schema.sql")
class CustomerContractApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test void savesContractsAndReturnsTheirProductPrices() throws Exception {
        String response = mvc.perform(post("/api/customers").contentType("application/json").content("""
                {"customerType":"DOMESTIC","customerName":"合同客户","taxpayerId":"91350100MABTRQEC91","orderContactName":"李经理","orderContactPhone":"13800000000",
                 "contracts":[{"contractNo":"HT-2026-01","startDate":"2026-08-01","endDate":"2027-07-31","remark":"年度合同",
                 "prices":[{"skuId":1,"salePrice":500.00},{"skuId":2,"salePrice":680.00}]}]}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contracts[0].contractNo").value("HT-2026-01"))
                .andExpect(jsonPath("$.data.contracts[0].prices[0].salePrice").value(500.0))
                .andReturn().getResponse().getContentAsString();
        long id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_contract WHERE customer_id=?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM customer_contract_price WHERE contract_id IN (SELECT id FROM customer_contract WHERE customer_id=?)", Integer.class, id)).isEqualTo(2);
    }

    @Test void rejectsOverlappingPricesForTheSameSku() throws Exception {
        mvc.perform(post("/api/customers").contentType("application/json").content("""
                {"customerType":"DOMESTIC","customerName":"重叠客户","taxpayerId":"91350100ABCDEFGHIJ","contracts":[
                 {"contractNo":"A","startDate":"2026-01-01","endDate":"2026-12-31","prices":[{"skuId":1,"salePrice":500}]},
                 {"contractNo":"B","startDate":"2026-06-01","endDate":"2027-05-31","prices":[{"skuId":1,"salePrice":520}]}]}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("同一产品的合同有效期不能重叠"));
    }

    @Test void hidesExpiredContractCustomersAndResolvesCurrentPrice() throws Exception {
        mvc.perform(get("/api/orders/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.customerCode == 'EXPIRED')]").isEmpty());

        mvc.perform(get("/api/orders/contract-price").param("customerId", "1").param("skuId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.salePrice").value(456.78));
    }
}
