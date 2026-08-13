package com.internalops.workbench;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Sql(scripts = "/sales-command-schema.sql")
class SalesMinimumOrderQuantityApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test void exposesSalesMinimumOrderQuantityAndRejectsSmallerOrderLines() throws Exception {
        jdbc.update("UPDATE sku SET sales_minimum_order_quantity=5 WHERE id=1");

        mvc.perform(get("/api/orders/skus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].salesMinimumOrderQuantity").value(5));

        String order="{\"customerId\":1,\"orderDate\":\"2026-08-06\",\"orderType\":\"PROJECT\",\"status\":\"DRAFT\",\"salesperson\":\"Admin\",\"items\":[{\"skuId\":1,\"quantity\":4,\"salePrice\":12.50}]}";
        mvc.perform(post("/api/orders").contentType("application/json").content(order))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("产品 S1 的订单数量不能小于销售最小起订量 5"));
    }
}
